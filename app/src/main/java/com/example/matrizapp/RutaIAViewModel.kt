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
                    val matchMatriz = buscarEnMatriz(cliente.cu, cliente.nombre)
                    // Si el cliente ya existe en Matriz con una ubicación GPS real (capturada en sitio,
                    // igual que en Solicitud), se usa esa coordenada en vez de geocodificar el texto de la
                    // dirección: el Geocoder nativo de Android interpola sobre la calle y puede desviar
                    // 100-200m, mientras que la de Matriz es la posición real confirmada en una visita previa.
                    val coordsMatriz = matchMatriz?.ubicacion?.let { parseLatLngOrden(it) }
                    val coords = coordsMatriz ?: geocodificarDireccion(context, direccionCompleta)
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

    /** Trae la ruta vigente de la hoja "Ruta IA" (la que generó otro dispositivo). Ver
     * SheetsRepository.refreshRutaIA para las reglas de qué gana si hay cambios locales. */
    fun sincronizarDesdeHoja(onResult: ((Result<Int>) -> Unit)? = null) {
        if (_procesando.value) return
        viewModelScope.launch {
            val resultado = runCatching { repository.refreshRutaIA() }
            onResult?.invoke(resultado)
        }
    }

    fun procesarFotos(uris: List<Uri>, onResult: (Boolean, String?) -> Unit) = onResult(false, "Ruta IA ahora usa un archivo JSON generado externamente. Usa 'Importar JSON'.")

    /** Un cliente "nuevo" (no encontrado en Matriz al importar el JSON de Gemini) NO se da de
     * alta en Matriz al importar la ruta -- solo hasta que Diego lo visita realmente y marca
     * "Visitado" aquí. Así Matriz nunca se llena de clientes que al final no se visitaron. */
    fun alternarVisitado(item: RutaIAEntity) {
        val nuevoEstado = if (item.estado.equals("Visitado", ignoreCase = true)) "Pendiente" else "Visitado"
        viewModelScope.launch {
            rutaIADao.updateEstadoLocal(item.id, nuevoEstado)
            if (nuevoEstado == "Visitado" && item.esNuevo && item.cuMatrizMatch == null) {
                val idMatriz = darDeAltaEnMatriz(item)
                rutaIADao.marcarAltaEnMatriz(item.id, idMatriz)
            }
            programarSincronizacionRutaIA()
        }
    }

    private suspend fun darDeAltaEnMatriz(item: RutaIAEntity): String {
        val idFinal = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        val semana = item.diasAtraso?.let { diasAtrasoASemana(it).toString() } ?: ""
        val requisito = (item.pagoRequerido ?: item.saldoAtraso)?.let { "%,.0f".format(it) } ?: ""
        val ubicacion = if (item.lat != null && item.lng != null) "${item.lat},${item.lng}" else null
        val ahora = System.currentTimeMillis()
        val hora = java.text.SimpleDateFormat("HH:mm", java.util.Locale("es", "MX")).format(java.util.Date(ahora))
        val nuevo = MatrizEntity(
            id = idFinal,
            nombre = item.nombre.trim().uppercase(java.util.Locale.ROOT),
            semana = semana,
            requisito = requisito,
            numTT = "",
            ref1 = "",
            ref2 = "",
            observaciones = "Alta automática desde Ruta IA al marcar visitado",
            estado = "",
            ubicacion = ubicacion,
            imagenUrl = null,
            imagenUrl2 = null,
            fecha = ahora,
            hora = hora,
            ruta = null,
            folioP = item.cu,
            isDirty = true
        )
        matrizDao.insertOne(nuevo)
        return idFinal
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

    /** Reordenamiento manual por arrastre (drag & drop) en la pantalla: recibe la lista completa
     * de ids ya en el orden final que dejó el arrastre y lo persiste de un jalón, igual que
     * moverManualmente pero para un reacomodo arbitrario en vez de mover 1 posición a la vez. */
    fun reordenarManual(idsEnOrden: List<String>) {
        viewModelScope.launch {
            idsEnOrden.forEachIndexed { i, id -> rutaIADao.updateOrden(id, i) }
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

    /** Liga una parada de la ruta con un registro de Matriz (recién creado a mano desde el formulario,
     * o cuyo ID se cambió al editar): la parada deja de ser "nueva" y se pinta como ya agregada. */
    fun vincularConMatriz(paradaId: String, matrizId: String) {
        viewModelScope.launch {
            rutaIADao.marcarAltaEnMatriz(paradaId, matrizId)
            programarSincronizacionRutaIA()
        }
    }

    /** Datos de la parada como registro de Matriz para precargar el formulario "Nuevo registro".
     * No se guarda nada: solo sirve de valores iniciales, el registro se crea al guardar el formulario. */
    fun prefillMatrizDesdeParada(item: RutaIAEntity): MatrizEntity {
        val semana = item.diasAtraso?.let { diasAtrasoASemana(it).toString() } ?: ""
        val requisito = (item.pagoRequerido ?: item.saldoAtraso)?.let { "%,.0f".format(it) } ?: ""
        val ubicacion = if (item.lat != null && item.lng != null) "${item.lat},${item.lng}" else null
        return MatrizEntity(
            id = "", nombre = item.nombre.trim().uppercase(java.util.Locale.ROOT), semana = semana, requisito = requisito,
            numTT = "", ref1 = "", ref2 = "", observaciones = null, estado = "", ubicacion = ubicacion,
            imagenUrl = null, imagenUrl2 = null, fecha = System.currentTimeMillis(), hora = null, ruta = null,
            folioP = item.cu, isDirty = false
        )
    }

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
