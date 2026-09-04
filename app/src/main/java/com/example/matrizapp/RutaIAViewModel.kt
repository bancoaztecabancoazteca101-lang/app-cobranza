package com.example.matrizapp
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Arma la ruta del día a partir de fotos de la app de trabajo de Banco Azteca ("Clientes de
 * cobranza"): OCR local por foto -> geocodifica cada dirección -> cruza por CU contra Matriz
 * (local, Room) para traer lo que ya se conoce de ese cliente -> ordena según los criterios
 * activos -> guarda en Room y sube el lote completo a la hoja "Ruta IA" (reemplazando lo que
 * hubiera antes, para no acumular lotes/días viejos). 100% independiente de matriz_table: el
 * cruce es solo lectura, nunca modifica Matriz. */
class RutaIAViewModel(
    private val rutaIADao: RutaIADao,
    private val filtroDao: RutaIAFiltroDao,
    private val matrizDao: MatrizDao,
    private val repository: SheetsRepository,
    private val context: Context
) : ViewModel() {

    val rutaList: StateFlow<List<RutaIAEntity>> = rutaIADao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _criterios = MutableStateFlow(listOf(CriterioOrdenRutaIA(CampoOrdenRutaIA.DISTANCIA, DireccionOrdenRutaIA.ASC)))
    val criterios: StateFlow<List<CriterioOrdenRutaIA>> = _criterios

    private val _procesando = MutableStateFlow(false)
    val procesando: StateFlow<Boolean> = _procesando

    private val _progreso = MutableStateFlow("")
    val progreso: StateFlow<String> = _progreso

    private val _ubicacionActual = MutableStateFlow<Pair<Double, Double>?>(null)
    val ubicacionActual: StateFlow<Pair<Double, Double>?> = _ubicacionActual

    init {
        viewModelScope.launch {
            filtroDao.get()?.let { _criterios.value = parsearCriteriosRutaIA(it.criteriosOrden) }
        }
        viewModelScope.launch {
            parseLatLngOrden(obtenerUbicacionActual(context))?.let { _ubicacionActual.value = it }
        }
    }

    /** Lista ya reordenada según los criterios activos -- se recalcula en memoria (Room/Sheets
     * no se vuelven a tocar solo por cambiar el orden de vista). */
    val rutaOrdenada: StateFlow<List<RutaIAEntity>> = kotlinx.coroutines.flow.combine(
        rutaList, _criterios, _ubicacionActual
    ) { lista, crit, ubic -> ordenarRutaIA(lista, crit, ubic) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun actualizarCriterios(nuevos: List<CriterioOrdenRutaIA>) {
        _criterios.value = nuevos.ifEmpty { listOf(CriterioOrdenRutaIA(CampoOrdenRutaIA.DISTANCIA, DireccionOrdenRutaIA.ASC)) }
        viewModelScope.launch {
            filtroDao.guardar(RutaIAFiltroEntity(id = 1, criteriosOrden = serializarCriteriosRutaIA(_criterios.value)))
        }
    }

    fun refrescarUbicacion() {
        viewModelScope.launch {
            parseLatLngOrden(obtenerUbicacionActual(context))?.let { _ubicacionActual.value = it }
        }
    }

    /** Punto de entrada: recibe las 4-8 fotos tomadas al inicio del día y arma la ruta completa. */
    fun procesarFotos(uris: List<Uri>, onResult: (exito: Boolean, error: String?) -> Unit) {
        if (uris.isEmpty()) { onResult(false, "No hay fotos que procesar"); return }
        viewModelScope.launch {
            _procesando.value = true
            try {
                val ubicacion = parseLatLngOrden(obtenerUbicacionActual(context))
                if (ubicacion != null) _ubicacionActual.value = ubicacion

                // 1) OCR de cada foto -> lista plana de clientes extraídos
                _progreso.value = "Leyendo fotos (0/${uris.size})..."
                val extraidos = mutableListOf<Pair<ClienteRutaIAExtraido, Uri>>()
                uris.forEachIndexed { idx, uri ->
                    _progreso.value = "Leyendo fotos (${idx + 1}/${uris.size})..."
                    extraerClientesDeFoto(context, uri).forEach { extraidos.add(it to uri) }
                }
                if (extraidos.isEmpty()) {
                    onResult(false, "No se detectó ningún cliente en las fotos. Intenta con fotos más claras y de frente a la pantalla.")
                    return@launch
                }

                // 2) Traer Matriz local una sola vez (evita golpear Room por cada cliente extraído).
                // El cruce es por nombre normalizado (sin acentos/mayúsculas) porque el CU que
                // muestra la app de trabajo no es el mismo Id que usa Matriz internamente.
                val matrizActual = matrizDao.getAllMatriz().first()
                fun buscarEnMatriz(nombre: String): MatrizEntity? =
                    matrizActual.find { coincideBusqueda(it.nombre, nombre) || coincideBusqueda(nombre, it.nombre) }

                // 3) Geocodificar + cruzar cada cliente
                _progreso.value = "Ubicando direcciones (0/${extraidos.size})..."
                val fechaHoy = inicioDeHoy()
                val nuevos = extraidos.mapIndexed { idx, (cliente, fotoUri) ->
                    _progreso.value = "Ubicando direcciones (${idx + 1}/${extraidos.size})..."
                    val coords = geocodificarDireccion(context, cliente.direccion)
                    val matchMatriz = buscarEnMatriz(cliente.nombre)
                    RutaIAEntity(
                        id = java.util.UUID.randomUUID().toString().replace("-", "").take(12),
                        nombre = cliente.nombre,
                        cu = cliente.cu,
                        direccion = cliente.direccion,
                        diasAtraso = cliente.diasAtraso,
                        pagoRequerido = cliente.pagoRequerido,
                        lat = coords?.first,
                        lng = coords?.second,
                        orden = idx,
                        esNuevo = matchMatriz == null,
                        cuMatrizMatch = matchMatriz?.id,
                        fechaDia = fechaHoy,
                        fotoOrigenUrl = fotoUri.toString(),
                        isDirty = true
                    )
                }

                // 4) Ordenar según criterios activos y renumerar `orden`
                val ordenados = ordenarRutaIA(nuevos, _criterios.value, ubicacion ?: _ubicacionActual.value)
                    .mapIndexed { idx, item -> item.copy(orden = idx) }

                // 5) Reemplazar en Room (borra el lote anterior) y subir a Sheets
                _progreso.value = "Guardando ruta..."
                rutaIADao.deleteAll()
                rutaIADao.insertAll(ordenados)
                try {
                    repository.reemplazarRutaIAEnSheet(ordenados)
                    ordenados.forEach { repository.markRutaIAAsClean(it.id) }
                } catch (e: Exception) {
                    // Sin conexión o falla puntual de Sheets: la ruta ya quedó local (isDirty=1)
                    // y se sube después con SyncWorker, no se pierde nada.
                }
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message ?: "Error desconocido al procesar las fotos")
            } finally {
                _procesando.value = false
                _progreso.value = ""
            }
        }
    }

    /** Alterna entre "Visitado" y "Pendiente" -- antes solo marcaba y el botón quedaba
     * deshabilitado (no se podía deshacer un toque accidental). */
    fun alternarVisitado(item: RutaIAEntity) {
        val nuevoEstado = if (item.estado.equals("Visitado", ignoreCase = true)) "Pendiente" else "Visitado"
        viewModelScope.launch { rutaIADao.updateEstadoLocal(item.id, nuevoEstado) }
    }

    /** Mueve un cliente una posición arriba (-1) o abajo (+1) dentro de la lista visible
     * (`rutaOrdenada`, no `rutaList` -- así el intercambio siempre parte de lo que Diego está
     * viendo en pantalla, sin importar qué criterio estaba activo antes). Renumera el campo
     * `orden` de TODA la lista para que quede consistente con la posición visual actual, hace
     * el swap entre las dos posiciones afectadas, y cambia el criterio activo a PERSONALIZADO
     * para que ese orden manual no se pierda la próxima vez que se recalcule `rutaOrdenada`. */
    fun moverManualmente(id: String, delta: Int) {
        viewModelScope.launch {
            val actual = rutaOrdenada.value
            val idx = actual.indexOfFirst { it.id == id }
            val nuevoIdx = idx + delta
            if (idx == -1 || nuevoIdx < 0 || nuevoIdx >= actual.size) return@launch

            val reordenado = actual.toMutableList()
            val tmp = reordenado[idx]; reordenado[idx] = reordenado[nuevoIdx]; reordenado[nuevoIdx] = tmp

            reordenado.forEachIndexed { i, item -> rutaIADao.updateOrden(item.id, i) }
            actualizarCriterios(listOf(CriterioOrdenRutaIA(CampoOrdenRutaIA.PERSONALIZADO, DireccionOrdenRutaIA.ASC)))
        }
    }

    /** Para abrir el registro existente en Matriz cuando el cliente sí tuvo match (no es nuevo).
     * Si no hay match (cliente nuevo, sin datos en Matriz), devuelve null y la pantalla no
     * intenta abrir nada. */
    suspend fun buscarMatrizPorId(id: String): MatrizEntity? = matrizDao.getById(id)

    /** Borrado manual de la ruta actual (además del borrado automático de madrugada por
     * Apps Script) -- por si Diego quiere limpiar y volver a tomar fotos a media mañana. */
    fun limpiarRutaAhora() {
        viewModelScope.launch {
            rutaIADao.deleteAll()
            try { repository.reemplazarRutaIAEnSheet(emptyList()) } catch (e: Exception) { }
        }
    }

    private fun inicioDeHoy(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
