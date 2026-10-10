package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class Sem6ViewModel(
    private val repository: SheetsRepository,
    private val cacheStore: Sem6CacheStore,
    val driveHelper: DriveHelper,
    private val matrizDao: MatrizDao,
    private val sem6Dao: Sem6Dao,
    private val appContext: android.content.Context
) : ViewModel() {
    /** Cartera de Matriz (local) para buscar al cliente al agregar un registro a Semana 6. */
    val matrizList: StateFlow<List<MatrizEntity>> = matrizDao.getAllMatriz()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Semana que se está mostrando. Arranca en la actual; el usuario puede elegir una semana pasada desde el selector.
    private val _semanaSeleccionada = MutableStateFlow(currentSem6SheetName())
    val semanaSeleccionada: StateFlow<String> = _semanaSeleccionada

    // Semana 6 vive en Room (tabla sem6_registro_table), ya no en la hoja Cont-Sem-NN: cualquier cambio en la tabla
    // se refleja solo en pantalla.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val _itemsRaw: StateFlow<List<Sem6Item>> = _semanaSeleccionada
        .flatMapLatest { hoja -> sem6Dao.observarHoja(hoja).map { lista -> lista.map { it.toItem() } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _orden = MutableStateFlow(OrdenLista.ORIGINAL)
    val orden: StateFlow<OrdenLista> = _orden
    private val _miUbicacion = MutableStateFlow<Pair<Double, Double>?>(null)
    fun setOrden(o: OrdenLista, miUbicacion: Pair<Double, Double>? = null) {
        _orden.value = o
        if (miUbicacion != null) _miUbicacion.value = miUbicacion
    }

    private val formatoFechaSem6 = java.text.SimpleDateFormat("d/M/yyyy HH:mm", java.util.Locale("es", "MX"))
    private fun distanciaOrNull(raw: String?, miUbicacion: Pair<Double, Double>): Double? =
        parseLatLngOrden(raw)?.let { distanciaKm(miUbicacion, it) }

    private fun ordenar(list: List<Sem6Item>, o: OrdenLista, miUbicacion: Pair<Double, Double>?): List<Sem6Item> = when (o) {
        OrdenLista.FECHA_HORA_RECIENTE -> list.sortedByDescending {
            try { formatoFechaSem6.parse(it.ultimaFechaVisita)?.time ?: 0L } catch (e: Exception) { 0L }
        }
        OrdenLista.FECHA_HORA_ANTIGUA -> list.sortedBy {
            try { formatoFechaSem6.parse(it.ultimaFechaVisita)?.time ?: Long.MAX_VALUE } catch (e: Exception) { Long.MAX_VALUE }
        }
        OrdenLista.UBICACION_CERCA -> if (miUbicacion == null) list else list.sortedBy { distanciaOrNull(it.ubicacion, miUbicacion) ?: Double.MAX_VALUE }
        OrdenLista.UBICACION_LEJOS -> if (miUbicacion == null) list else list.sortedByDescending { distanciaOrNull(it.ubicacion, miUbicacion) ?: -1.0 }
        OrdenLista.ALFABETICO_AZ -> list.sortedBy { it.nombre.lowercase() }
        OrdenLista.ALFABETICO_ZA -> list.sortedByDescending { it.nombre.lowercase() }
        OrdenLista.ORIGINAL -> list
    }

    val items: StateFlow<List<Sem6Item>> = combine(_itemsRaw, _orden, _miUbicacion) { list, o, loc -> ordenar(list, o, loc) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _lastUpdated = MutableStateFlow<Long?>(null)
    val lastUpdated: StateFlow<Long?> = _lastUpdated

    private val _isFromCache = MutableStateFlow(false)
    val isFromCache: StateFlow<Boolean> = _isFromCache

    private val _isSavingNotas = MutableStateFlow(false)
    val isSavingNotas: StateFlow<Boolean> = _isSavingNotas

    private val _errorNotas = MutableStateFlow<String?>(null)
    val errorNotas: StateFlow<String?> = _errorNotas

    private val _isGuardandoRegistro = MutableStateFlow(false)
    val isGuardandoRegistro: StateFlow<Boolean> = _isGuardandoRegistro

    private val _errorRegistro = MutableStateFlow<String?>(null)
    val errorRegistro: StateFlow<String?> = _errorRegistro

    private val _semanasDisponibles = MutableStateFlow<List<String>>(emptyList())
    val semanasDisponibles: StateFlow<List<String>> = _semanasDisponibles

    /** Trae la lista de hojas "Cont-Sem-NN" que ya existen, para poblar el selector. */
    fun cargarSemanasDisponibles() {
        viewModelScope.launch {
            try {
                val lista = sem6Dao.hojas()
                // Por si la semana actual aún no tiene hoja creada (p. ej. lunes muy
                // temprano), la agregamos igual para que se pueda seleccionar de regreso.
                _semanasDisponibles.value = (lista + currentSem6SheetName()).distinct()
                    .sortedByDescending { it.substringAfterLast("-").trim().toIntOrNull() ?: -1 }
            } catch (e: Exception) {
                // Si falla, el selector simplemente no se llena; no afecta lo que ya está en pantalla.
            }
        }
    }

    /** Cambia la semana que se está viendo y recarga sus datos. */
    fun seleccionarSemana(sheetName: String) {
        if (sheetName == _semanaSeleccionada.value) return
        _semanaSeleccionada.value = sheetName
    }

    /** Guarda Se Contiene/Susceptible/Observaciones para un registro y actualiza la lista en
     * memoria de inmediato (optimista) para que el usuario vea el cambio sin esperar el refresh. */
    fun guardarNotas(id: String, seContiene: String, susceptible: String, observaciones: String, capital: String, abono: String, onDone: (Boolean) -> Unit = {}) {
        _isSavingNotas.value = true
        _errorNotas.value = null
        viewModelScope.launch {
            try {
                val ok = sem6Dao.actualizarNotas(_semanaSeleccionada.value, id, seContiene, susceptible, observaciones, capital, abono) > 0
                if (!ok) _errorNotas.value = "No se encontró el registro en ${_semanaSeleccionada.value.replace("Cont-Sem-", "Semana ")}"
                onDone(ok)
            } catch (e: Exception) {
                _errorNotas.value = e.message ?: "No se pudo guardar"
                onDone(false)
            } finally {
                _isSavingNotas.value = false
            }
        }
    }

    private val _leyendoFotoCapital = MutableStateFlow(false)
    val leyendoFotoCapital: StateFlow<Boolean> = _leyendoFotoCapital
    private val _aplicandoCapital = MutableStateFlow(false)
    val aplicandoCapital: StateFlow<Boolean> = _aplicandoCapital

    /** Lee la foto y propone el capital de cada registro de la semana. No escribe nada todavía. */
    fun analizarFotoCapital(context: android.content.Context, uri: android.net.Uri, onResult: (cambios: List<CambioCapital>, filasLeidas: Int) -> Unit) {
        _leyendoFotoCapital.value = true
        viewModelScope.launch {
            try {
                val filas = leerCapitalesDeFoto(context, uri)
                onResult(emparejarCapitales(_itemsRaw.value, filas), filas.size)
            } finally {
                _leyendoFotoCapital.value = false
            }
        }
    }

    /** Escribe el capital (solo la columna P) de los registros indicados y actualiza la lista local. */
    fun aplicarCapitales(cambios: List<CambioCapital>, onDone: (ok: Int, fallidos: Int) -> Unit) {
        _aplicandoCapital.value = true
        viewModelScope.launch {
            var ok = 0; var fallidos = 0
            for (c in cambios) {
                try {
                    if (sem6Dao.actualizarCapital(_semanaSeleccionada.value, c.item.id, c.nuevoCapital) > 0) ok++ else fallidos++
                } catch (e: Exception) { fallidos++ }
            }
            _aplicandoCapital.value = false
            onDone(ok, fallidos)
        }
    }

    /** Agrega un registro nuevo a la hoja de la semana que se está viendo (antes esta hoja era
     * de solo lectura; Diego pidió poder agregar registros desde la app). */
    fun agregarRegistro(nombre: String, sem: String, req: String, cu: String, colonia: String, ubicacion: String, numTT: String, onDone: (Boolean) -> Unit = {}) {
        _isGuardandoRegistro.value = true
        _errorRegistro.value = null
        viewModelScope.launch {
            try {
                val id = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
                val fechaHora = java.text.SimpleDateFormat("d/M/yyyy HH:mm", java.util.Locale("es", "MX")).format(java.util.Date())
                sem6Dao.guardar(Sem6RegistroEntity(
                    hoja = _semanaSeleccionada.value, id = id, nombre = nombre, sem = sem, req = req, cu = cu, ubicacion = ubicacion,
                    colonia = colonia, visitas = 0, ultimaFechaVisita = fechaHora, numTT = numTT
                ))
                onDone(true)
            } catch (e: Exception) {
                _errorRegistro.value = e.message ?: "No se pudo agregar"
                onDone(false)
            } finally {
                _isGuardandoRegistro.value = false
            }
        }
    }

    /** Elimina un registro de la hoja de la semana que se está viendo. */
    fun eliminarRegistro(id: String, onDone: (Boolean) -> Unit = {}) {
        _isGuardandoRegistro.value = true
        _errorRegistro.value = null
        viewModelScope.launch {
            try {
                val ok = sem6Dao.eliminar(_semanaSeleccionada.value, id) > 0
                if (ok) {
                    // Marca permanente: el generador no debe volver a crear este registro desde Matriz.
                    cacheStore.marcarEliminado(id, _semanaSeleccionada.value)
                } else {
                    _errorRegistro.value = "No se encontró el registro en ${_semanaSeleccionada.value.replace("Cont-Sem-", "Semana ")}"
                }
                onDone(ok)
            } catch (e: Exception) {
                _errorRegistro.value = e.message ?: "No se pudo eliminar"
                onDone(false)
            } finally {
                _isGuardandoRegistro.value = false
            }
        }
    }

    init {
        // 1) Importación ÚNICA de lo que ya existe en las hojas Cont-Sem-NN (se reintenta al abrir hasta que salga bien).
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = appContext.getSharedPreferences("sem6_interna", android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean("importado_v1", false)) {
                val ok = Sem6Generador.importarDesdeHojas(repository, sem6Dao) { hoja, id -> cacheStore.estaEliminado(id, hoja) }
                if (ok) prefs.edit().putBoolean("importado_v1", true).apply()
            }
        }
        // 2) Reemplazo del script guardarRegistroSemana6: cada vez que cambia Matriz se actualiza Semana 6.
        viewModelScope.launch(Dispatchers.IO) {
            matrizDao.getAllMatriz().distinctUntilChanged().collect { lista ->
                try {
                    Sem6Generador.actualizar(sem6Dao, lista) { hoja, id -> cacheStore.estaEliminado(id, hoja) }
                    Sem6Generador.completarColonias(appContext, sem6Dao, currentSem6SheetName())
                } catch (_: Exception) { }
            }
        }
        _lastUpdated.value = System.currentTimeMillis()
        cargarSemanasDisponibles()
    }

    /** "Actualizar": vuelve a calcular Semana 6 desde Matriz (ya no se descarga nada de Google Sheets). */
    fun cargar() {
        if (_isLoading.value) return
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Sem6Generador.actualizar(sem6Dao, matrizDao.getAllMatriz().first()) { hoja, id -> cacheStore.estaEliminado(id, hoja) }
                Sem6Generador.completarColonias(appContext, sem6Dao, currentSem6SheetName())
                _isFromCache.value = false
                _lastUpdated.value = System.currentTimeMillis()
                cargarSemanasDisponibles()
            } catch (e: Exception) {
                _error.value = e.message ?: "No se pudo actualizar"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
