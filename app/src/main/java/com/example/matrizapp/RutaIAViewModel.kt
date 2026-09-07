package com.example.matrizapp

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    private val _configuracion = MutableStateFlow(FiltrosRutaIA())
    val configuracion: StateFlow<FiltrosRutaIA> = _configuracion
    private val _criterios = MutableStateFlow(listOf(CriterioOrdenRutaIA(CampoOrdenRutaIA.PERSONALIZADO, DireccionOrdenRutaIA.ASC)))
    val criterios: StateFlow<List<CriterioOrdenRutaIA>> = _criterios
    private val _procesando = MutableStateFlow(false)
    val procesando: StateFlow<Boolean> = _procesando
    private val _progreso = MutableStateFlow("")
    val progreso: StateFlow<String> = _progreso
    private val _ubicacionActual = MutableStateFlow<Pair<Double, Double>?>(null)
    val ubicacionActual: StateFlow<Pair<Double, Double>?> = _ubicacionActual

    init {
        viewModelScope.launch {
            val guardado = filtroDao.get()
            if (guardado != null) {
                _configuracion.value = parsearConfiguracionRutaIA(guardado.criteriosOrden)
                _criterios.value = parsearCriteriosRutaIA(guardado.criteriosOrden)
            }
        }
        viewModelScope.launch { parseLatLngOrden(obtenerUbicacionActual(context))?.let { _ubicacionActual.value = it } }
    }

    val rutaOrdenada: StateFlow<List<RutaIAEntity>> = combine(rutaList, _configuracion, _ubicacionActual) { items, config, ubicacion ->
        construirRutaIAConfigurada(items, ubicacion, config)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun actualizarConfiguracion(nueva: FiltrosRutaIA) {
        _configuracion.value = nueva
        _criterios.value = criteriosDesdeConfiguracion(nueva)
        viewModelScope.launch {
            filtroDao.guardar(RutaIAFiltroEntity(id = 1, criteriosOrden = serializarConfiguracionRutaIA(nueva)))
            if (rutaList.value.isNotEmpty()) guardarOrdenActualYSincronizar(nueva)
        }
    }

    private suspend fun guardarOrdenActualYSincronizar(config: FiltrosRutaIA) {
        val ubicacion = _ubicacionActual.value ?: parseLatLngOrden(obtenerUbicacionActual(context))
        if (ubicacion != null) _ubicacionActual.value = ubicacion
        val ordenados = construirRutaIAConfigurada(rutaList.value, ubicacion, config).mapIndexed { idx, item -> item.copy(orden = idx) }
        ordenados.forEach { rutaIADao.updateOrden(it.id, it.orden) }
        try {
            repository.reemplazarRutaIAEnSheet(ordenados)
            ordenados.forEach { repository.markRutaIAAsClean(it.id) }
        } catch (_: Exception) {
            programarSincronizacionRutaIA()
        }
    }

    fun actualizarCriterios(nuevos: List<CriterioOrdenRutaIA>) {
        _criterios.value = nuevos.ifEmpty { listOf(CriterioOrdenRutaIA(CampoOrdenRutaIA.PERSONALIZADO, DireccionOrdenRutaIA.ASC)) }
        viewModelScope.launch { filtroDao.guardar(RutaIAFiltroEntity(id = 1, criteriosOrden = serializarCriteriosRutaIA(_criterios.value))) }
    }

    fun refrescarUbicacion() {
        viewModelScope.launch { parseLatLngOrden(obtenerUbicacionActual(context))?.let { _ubicacionActual.value = it } }
    }

    fun importarJson(uri: Uri, configuracion: FiltrosRutaIA, onResult: (Boolean, String?, List<String>) -> Unit) {
        if (_procesando.value) return
        viewModelScope.launch {
            _procesando.value = true
            try {
                val importado = leerClientesRutaIAJson(context, uri)
                val ubicacion = parseLatLngOrden(obtenerUbicacionActual(context))
                if (ubicacion != null) _ubicacionActual.value = ubicacion
                val matrizActual = matrizDao.getAllMatriz().first()
                fun normalizarCu(valor: String?): String = valor.orEmpty().uppercase(java.util.Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
                fun buscarEnMatriz(cu: String?, nombre: String): MatrizEntity? {
                    val cuNormalizado = normalizarCu(cu)
                    if (cuNormalizado.isNotBlank()) matrizActual.firstOrNull { normalizarCu(it.folioP) == cuNormalizado }?.let { return it }
                    return matrizActual.firstOrNull { coincideBusqueda(it.nombre, nombre) || coincideBusqueda(nombre, it.nombre) }
                }
                _progreso.value = "Validando ${importado.clientes.size} clientes..."
                val fechaHoy = inicioDeHoy()
                val nuevos = importado.clientes.mapIndexed { idx, cliente ->
                    _progreso.value = "Ubicando ${idx + 1}/${importado.clientes.size}: ${cliente.nombre}"
                    val direccionCompleta = listOf(cliente.direccion, cliente.colonia, cliente.cp).filterNot { it.isNullOrBlank() }.joinToString(", ")
                    val coords = geocodificarDireccion(context, direccionCompleta)
                    val matchMatriz = buscarEnMatriz(cliente.cu, cliente.nombre)
                    RutaIAEntity(
                        id = java.util.UUID.randomUUID().toString().replace("-", "").take(12),
                        nombre = cliente.nombre,
                        cu = cliente.cu,
                        direccion = direccionCompleta,
                        diasAtraso = cliente.diasAtraso,
                        pagoRequerido = cliente.requerido,
                        saldoAtraso = cliente.saldo,
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
                filtroDao.guardar(RutaIAFiltroEntity(id = 1, criteriosOrden = serializarConfiguracionRutaIA(configuracion)))
                _configuracion.value = configuracion
                _criterios.value = criteriosDesdeConfiguracion(configuracion)
                rutaIADao.deleteAll()
                rutaIADao.insertAll(nuevos)
                val ordenados = construirRutaIAConfigurada(nuevos, ubicacion ?: _ubicacionActual.value, configuracion).mapIndexed { idx, item -> item.copy(orden = idx) }
                ordenados.forEach { rutaIADao.updateOrden(it.id, it.orden) }
                _progreso.value = "Sincronizando ruta..."
                try {
                    repository.reemplazarRutaIAEnSheet(ordenados)
                    ordenados.forEach { repository.markRutaIAAsClean(it.id) }
                } catch (_: Exception) { programarSincronizacionRutaIA() }
                val filtrados = nuevos.size - aplicarFiltrosRutaIA(nuevos, configuracion).size
                val mensaje = if (filtrados > 0) "Ruta generada con ${ordenados.size} clientes ($filtrados excluidos por filtros)" else "Ruta generada con ${ordenados.size} clientes"
                onResult(true, mensaje, importado.advertencias)
            } catch (e: Exception) {
                onResult(false, e.message ?: "No se pudo importar el JSON", emptyList())
            } finally {
                _procesando.value = false
                _progreso.value = ""
            }
        }
    }

    fun procesarFotos(uris: List<Uri>, onResult: (Boolean, String?) -> Unit) = onResult(false, "Ruta IA ahora usa un archivo JSON generado externamente. Usa 'Importar JSON'.")

    fun alternarVisitado(item: RutaIAEntity) {
        val nuevoEstado = if (item.estado.equals("Visitado", ignoreCase = true)) "Pendiente" else "Visitado"
        viewModelScope.launch { rutaIADao.updateEstadoLocal(item.id, nuevoEstado); programarSincronizacionRutaIA() }
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
            if (_configuracion.value.modoRuta != ModoRutaIA.MANUAL) {
                val manual = _configuracion.value.copy(modoRuta = ModoRutaIA.MANUAL)
                _configuracion.value = manual
                filtroDao.guardar(RutaIAFiltroEntity(id = 1, criteriosOrden = serializarConfiguracionRutaIA(manual)))
                _criterios.value = criteriosDesdeConfiguracion(manual)
            }
            programarSincronizacionRutaIA()
        }
    }

    fun moverSeleccionados(ids: Set<String>, delta: Int) {
        if (ids.isEmpty() || delta == 0) return
        viewModelScope.launch {
            val actual = rutaOrdenada.value.toMutableList()
            val seleccionados = ids.intersect(actual.map { it.id }.toSet())
            if (seleccionados.isEmpty()) return@launch

            if (delta < 0) {
                for (i in 1 until actual.size) {
                    if (actual[i].id in seleccionados && actual[i - 1].id !in seleccionados) {
                        val tmp = actual[i - 1]
                        actual[i - 1] = actual[i]
                        actual[i] = tmp
                    }
                }
            } else {
                for (i in actual.lastIndex - 1 downTo 0) {
                    if (actual[i].id in seleccionados && actual[i + 1].id !in seleccionados) {
                        val tmp = actual[i + 1]
                        actual[i + 1] = actual[i]
                        actual[i] = tmp
                    }
                }
            }

            actual.forEachIndexed { i, item -> rutaIADao.updateOrden(item.id, i) }
            if (_configuracion.value.modoRuta != ModoRutaIA.MANUAL) {
                val manual = _configuracion.value.copy(modoRuta = ModoRutaIA.MANUAL)
                _configuracion.value = manual
                filtroDao.guardar(RutaIAFiltroEntity(id = 1, criteriosOrden = serializarConfiguracionRutaIA(manual)))
                _criterios.value = criteriosDesdeConfiguracion(manual)
            }
            programarSincronizacionRutaIA()
        }
    }

    suspend fun buscarMatrizPorId(id: String): MatrizEntity? = matrizDao.getById(id)

    fun limpiarRutaAhora() {
        viewModelScope.launch { rutaIADao.deleteAll(); try { repository.reemplazarRutaIAEnSheet(emptyList()) } catch (_: Exception) { } }
    }

    private fun criteriosDesdeConfiguracion(config: FiltrosRutaIA): List<CriterioOrdenRutaIA> {
        if (config.modoRuta == ModoRutaIA.MANUAL) return listOf(CriterioOrdenRutaIA(CampoOrdenRutaIA.PERSONALIZADO, DireccionOrdenRutaIA.ASC))
        val lista = mutableListOf<CriterioOrdenRutaIA>()
        if (config.usarCercaniaEncadenada) lista += CriterioOrdenRutaIA(CampoOrdenRutaIA.DISTANCIA, config.direccionCercania)
        if (config.usarDiasAtraso) lista += CriterioOrdenRutaIA(CampoOrdenRutaIA.DIAS_ATRASO, config.direccionDiasAtraso)
        if (config.usarSaldoAtraso) lista += CriterioOrdenRutaIA(CampoOrdenRutaIA.PAGO_REQUERIDO, config.direccionSaldoAtraso)
        return lista.ifEmpty { listOf(CriterioOrdenRutaIA(CampoOrdenRutaIA.DISTANCIA, DireccionOrdenRutaIA.ASC)) }
    }

    private fun programarSincronizacionRutaIA() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("ruta_ia_sync", ExistingWorkPolicy.REPLACE, request)
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
