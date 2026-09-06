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

class RutaIAViewModel(
    private val rutaIADao: RutaIADao,
    private val filtroDao: RutaIAFiltroDao,
    private val matrizDao: MatrizDao,
    private val repository: SheetsRepository,
    private val context: Context
) : ViewModel() {
    val rutaList: StateFlow<List<RutaIAEntity>> = rutaIADao.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _criterios = MutableStateFlow(listOf(CriterioOrdenRutaIA(CampoOrdenRutaIA.PERSONALIZADO, DireccionOrdenRutaIA.ASC)))
    val criterios: StateFlow<List<CriterioOrdenRutaIA>> = _criterios
    private val _procesando = MutableStateFlow(false)
    val procesando: StateFlow<Boolean> = _procesando
    private val _progreso = MutableStateFlow("")
    val progreso: StateFlow<String> = _progreso
    private val _ubicacionActual = MutableStateFlow<Pair<Double, Double>?>(null)
    val ubicacionActual: StateFlow<Pair<Double, Double>?> = _ubicacionActual

    init {
        viewModelScope.launch { filtroDao.get()?.let { _criterios.value = parsearCriteriosRutaIA(it.criteriosOrden) } }
        viewModelScope.launch { parseLatLngOrden(obtenerUbicacionActual(context))?.let { _ubicacionActual.value = it } }
    }

    val rutaOrdenada: StateFlow<List<RutaIAEntity>> = kotlinx.coroutines.flow.combine(rutaList, _criterios, _ubicacionActual) { lista, crit, ubic -> ordenarRutaIA(lista, crit, ubic) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun actualizarCriterios(nuevos: List<CriterioOrdenRutaIA>) {
        _criterios.value = nuevos.ifEmpty { listOf(CriterioOrdenRutaIA(CampoOrdenRutaIA.PERSONALIZADO, DireccionOrdenRutaIA.ASC)) }
        viewModelScope.launch { filtroDao.guardar(RutaIAFiltroEntity(id = 1, criteriosOrden = serializarCriteriosRutaIA(_criterios.value))) }
    }

    fun refrescarUbicacion() {
        viewModelScope.launch {
            parseLatLngOrden(obtenerUbicacionActual(context))?.let { _ubicacionActual.value = it }
        }
    }

    fun importarJson(uri: Uri, estrategia: EstrategiaRutaIA, onResult: (Boolean, String?, List<String>) -> Unit) {
        if (_procesando.value) return
        viewModelScope.launch {
            _procesando.value = true
            try {
                val importado = leerClientesRutaIAJson(context, uri)
                val ubicacion = parseLatLngOrden(obtenerUbicacionActual(context))
                if (ubicacion != null) _ubicacionActual.value = ubicacion

                val matrizActual = matrizDao.getAllMatriz().first()
                fun normalizarCu(valor: String?): String = valor.orEmpty()
                    .uppercase(java.util.Locale.ROOT)
                    .replace(Regex("[^A-Z0-9]"), "")

                fun buscarEnMatriz(cu: String?, nombre: String): MatrizEntity? {
                    val cuNormalizado = normalizarCu(cu)
                    if (cuNormalizado.isNotBlank()) {
                        matrizActual.firstOrNull { normalizarCu(it.folioP) == cuNormalizado }?.let { return it }
                    }
                    return matrizActual.firstOrNull {
                        coincideBusqueda(it.nombre, nombre) || coincideBusqueda(nombre, it.nombre)
                    }
                }

                _progreso.value = "Validando ${importado.clientes.size} clientes..."
                val fechaHoy = inicioDeHoy()
                val nuevos = importado.clientes.mapIndexed { idx, cliente ->
                    _progreso.value = "Ubicando ${idx + 1}/${importado.clientes.size}: ${cliente.nombre}"
                    val direccionCompleta = listOf(cliente.direccion, cliente.colonia, cliente.cp)
                        .filterNot { it.isNullOrBlank() }
                        .joinToString(", ")
                    val coords = geocodificarDireccion(context, direccionCompleta)
                    val matchMatriz = buscarEnMatriz(cliente.cu, cliente.nombre)
                    RutaIAEntity(
                        id = java.util.UUID.randomUUID().toString().replace("-", "").take(12),
                        nombre = cliente.nombre,
                        cu = cliente.cu,
                        direccion = direccionCompleta,
                        diasAtraso = cliente.diasAtraso,
                        pagoRequerido = cliente.requerido ?: cliente.saldo,
                        lat = coords?.first,
                        lng = coords?.second,
                        orden = idx,
                        esNuevo = matchMatriz == null,
                        cuMatrizMatch = matchMatriz?.id,
                        fechaDia = fechaHoy,
                        fotoOrigenUrl = null,
                        isDirty = true
                    )
                }

                _progreso.value = "Aplicando filtros y construyendo ruta..."
                val filtros = FiltrosRutaIA(
                    minimoDiasAtraso = null,
                    minimoRequerido = null,
                    exigirDireccion = true
                )
                val ordenados = construirRutaIAInteligente(
                    nuevos,
                    ubicacion ?: _ubicacionActual.value,
                    estrategia,
                    filtros
                ).mapIndexed { idx, item -> item.copy(orden = idx) }

                _progreso.value = "Guardando ruta..."
                rutaIADao.deleteAll()
                rutaIADao.insertAll(ordenados)
                actualizarCriterios(listOf(CriterioOrdenRutaIA(CampoOrdenRutaIA.PERSONALIZADO, DireccionOrdenRutaIA.ASC)))

                try {
                    repository.reemplazarRutaIAEnSheet(ordenados)
                    ordenados.forEach { repository.markRutaIAAsClean(it.id) }
                } catch (_: Exception) {
                    // La ruta local queda marcada dirty para que la sincronización pendiente
                    // pueda reintentarse sin perder los datos ya procesados.
                }

                onResult(true, "Ruta generada con ${ordenados.size} clientes", importado.advertencias)
            } catch (e: Exception) {
                onResult(false, e.message ?: "No se pudo importar el JSON", emptyList())
            } finally {
                _procesando.value = false
                _progreso.value = ""
            }
        }
    }

    fun procesarFotos(uris: List<Uri>, onResult: (Boolean, String?) -> Unit) =
        onResult(false, "Ruta IA ahora usa un archivo JSON generado externamente. Usa 'Importar JSON'.")

    fun alternarVisitado(item: RutaIAEntity) {
        val nuevoEstado = if (item.estado.equals("Visitado", ignoreCase = true)) "Pendiente" else "Visitado"
        viewModelScope.launch { rutaIADao.updateEstadoLocal(item.id, nuevoEstado) }
    }

    fun moverManualmente(id: String, delta: Int) {
        viewModelScope.launch {
            val actual = rutaOrdenada.value
            val idx = actual.indexOfFirst { it.id == id }
            val nuevoIdx = idx + delta
            if (idx == -1 || nuevoIdx < 0 || nuevoIdx >= actual.size) return@launch
            val reordenado = actual.toMutableList()
            val tmp = reordenado[idx]
            reordenado[idx] = reordenado[nuevoIdx]
            reordenado[nuevoIdx] = tmp
            reordenado.forEachIndexed { i, item -> rutaIADao.updateOrden(item.id, i) }
            actualizarCriterios(listOf(CriterioOrdenRutaIA(CampoOrdenRutaIA.PERSONALIZADO, DireccionOrdenRutaIA.ASC)))
        }
    }

    suspend fun buscarMatrizPorId(id: String): MatrizEntity? = matrizDao.getById(id)

    fun limpiarRutaAhora() {
        viewModelScope.launch {
            rutaIADao.deleteAll()
            try { repository.reemplazarRutaIAEnSheet(emptyList()) } catch (_: Exception) { }
        }
    }

    private fun inicioDeHoy(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
