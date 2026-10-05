package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Antes leía de FiltroFechaEntity, una tabla aparte sincronizada desde una hoja de Sheets
 * ("Filtro Fecha") que a su vez se alimentaba de Matriz vía Apps Script -- un intermediario
 * lento y a veces desincronizado. Ahora lee directo de MatrizEntity (mismo dato que Matriz,
 * sin script ni hoja aparte de por medio), filtrado por defecto al día de hoy. Las alarmas de
 * Retorno/App ya las programa MatrizViewModel.sincronizarAlarmasRetornoMatriz -- no se duplica
 * aquí. */
class FiltroFechaViewModel(
    private val matrizDao: MatrizDao,
    val driveHelper: DriveHelper,
    private val repository: SheetsRepository,
    private val sem6CacheStore: Sem6CacheStore,
    private val visitaMapaDao: VisitaMapaDao,
    private val ticketPagoDao: TicketPagoDao
) : ViewModel() {

    private fun inicioDeHoy(): Long = java.time.LocalDate.now()
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun finDeHoy(): Long = java.time.LocalDate.now().plusDays(1)
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

    private val _desde = MutableStateFlow<Long?>(inicioDeHoy())
    val desde: StateFlow<Long?> = _desde

    private val _hasta = MutableStateFlow<Long?>(finDeHoy())
    val hasta: StateFlow<Long?> = _hasta

    private val _orden = MutableStateFlow(OrdenLista.ORIGINAL)
    val orden: StateFlow<OrdenLista> = _orden
    private val _miUbicacion = MutableStateFlow<Pair<Double, Double>?>(null)
    fun setOrden(o: OrdenLista, miUbicacion: Pair<Double, Double>? = null) {
        _orden.value = o
        if (miUbicacion != null) _miUbicacion.value = miUbicacion
    }

    // Filtro "Pagados" (ver FiltroFechaOrdenButton en SharedComponents.kt): muestra solo los
    // registros con Status="Pagado" del rango de fecha actual, junto con la sumatoria del
    // monto cobrado ese rango (ver totalCobradoRango más abajo).
    private val _soloPagados = MutableStateFlow(false)
    val soloPagados: StateFlow<Boolean> = _soloPagados
    fun toggleSoloPagados() { _soloPagados.value = !_soloPagados.value }

    private fun ordenar(list: List<MatrizEntity>, o: OrdenLista, miUbicacion: Pair<Double, Double>?): List<MatrizEntity> = when (o) {
        OrdenLista.FECHA_HORA_RECIENTE -> list.sortedByDescending { it.fecha ?: 0L }
        OrdenLista.FECHA_HORA_ANTIGUA -> list.sortedBy { it.fecha ?: 0L }
        OrdenLista.UBICACION_CERCA -> if (miUbicacion == null) list else list.sortedBy { distanciaOrNull(it.ubicacion, miUbicacion) ?: Double.MAX_VALUE }
        OrdenLista.UBICACION_LEJOS -> if (miUbicacion == null) list else list.sortedByDescending { distanciaOrNull(it.ubicacion, miUbicacion) ?: -1.0 }
        OrdenLista.ALFABETICO_AZ -> list.sortedBy { it.nombre.lowercase() }
        OrdenLista.ALFABETICO_ZA -> list.sortedByDescending { it.nombre.lowercase() }
        OrdenLista.ORIGINAL -> list
    }
    private fun distanciaOrNull(raw: String?, miUbicacion: Pair<Double, Double>): Double? =
        parseLatLngOrden(raw)?.let { distanciaKm(miUbicacion, it) }

    private data class FiltroParams(val desde: Long?, val hasta: Long?, val orden: OrdenLista, val loc: Pair<Double, Double>?, val soloPagados: Boolean)

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredList: StateFlow<List<MatrizEntity>> = combine(_desde, _hasta, _orden, _miUbicacion, _soloPagados) { d, h, o, loc, solo ->
        FiltroParams(d, h, o, loc, solo)
    }.flatMapLatest { p ->
        matrizDao.getAllMatriz().map { list ->
            val enRango = if (p.desde != null && p.hasta != null) {
                list.filter { val f = it.fecha; f != null && f in p.desde..p.hasta && !it.estado.equals("PASE", ignoreCase = true) }
            } else {
                list.filter { !it.estado.equals("PASE", ignoreCase = true) }
            }
            val filtrada = if (p.soloPagados) enRango.filter { it.estado.equals("Pagado", ignoreCase = true) } else enRango
            ordenar(filtrada, p.orden, p.loc)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Sumatoria de montoCobrado de los registros "Pagado" del rango de fecha actual (para el
     * encabezado que se muestra cuando el filtro "Pagados" está activo). Independiente de
     * orden/soloPagados a propósito: siempre refleja el total del rango visible. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val totalCobradoRango: StateFlow<Double> = combine(_desde, _hasta) { d, h -> d to h }
        .flatMapLatest { (d, h) ->
            matrizDao.getAllMatriz().map { list ->
                list.filter { val f = it.fecha; (d == null || h == null || (f != null && f in d..h)) && it.estado.equals("Pagado", ignoreCase = true) }
                    .sumOf { it.montoCobrado ?: 0.0 }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    /** Suma exclusivamente los montos de tickets leídos por OCR, independiente del status. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val totalTicketsRango: StateFlow<Double> = combine(_desde, _hasta) { d, h -> d to h }
        .flatMapLatest { (d, h) ->
            if (d != null && h != null) ticketPagoDao.totalEnRango(d, h)
            else flowOf(0.0)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    fun setRangoFecha(desde: Long?, hasta: Long?) {
        _desde.value = desde
        _hasta.value = hasta
    }

    /** Restaura el filtro al día de hoy (para el botón "Hoy" si se agrega uno más adelante). */
    fun filtrarHoy() = setRangoFecha(inicioDeHoy(), finDeHoy())

    /** Guarda status y hora localmente (Room) y los marca dirty para subirlos al Sheet de
     * Matriz en el próximo sync -- ya no hay una hoja "Filtro Fecha" aparte que actualizar. */
    fun guardarEstadoYHora(id: String, nuevoEstado: String, nuevaHora: String, notificacionesHelper: NotificacionesHelper, onResult: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val anterior = matrizDao.getById(id)
            matrizDao.updateEstadoYHora(id, nuevoEstado, nuevaHora)
            // Mapa: editar un registro cuenta como visita de hoy.
            if (anterior != null) visitaMapaDao.registrarVisitaHoy("M:$id", anterior.nombre, anterior.ubicacion, id)
            onResult(notificacionesHelper.evaluarProgramacion(nuevoEstado, nuevaHora))
        }
    }

    // Escaneo de ticket de cobranza (foto) -- ver FiltroFechaOrdenButton/ícono en el drawer
    // (AppNavigationDrawer.kt) y extraerDatosTicketDeImagen (SharedComponents.kt).
    private val _ticketPagoEnProgreso = MutableStateFlow(false)
    val ticketPagoEnProgreso: StateFlow<Boolean> = _ticketPagoEnProgreso
    private val _ticketPagoResultado = MutableStateFlow<String?>(null)
    val ticketPagoResultado: StateFlow<String?> = _ticketPagoResultado
    fun limpiarTicketPagoResultado() { _ticketPagoResultado.value = null }

    /** Lee la foto del ticket de cobranza (OCR) y busca al cliente en DOS lugares:
     * 1) Filtro Fecha (registros de hoy): si lo encuentra lo marca "Pagado" con el monto leído.
     * 2) Semana 6 (hoja de la semana actual): si lo encuentra lo marca "Recuperado" SOLO si el
     *    monto del ticket es igual o mayor al campo Abono del registro.
     * La búsqueda (CU, nombre tolerante, texto completo) es la misma en ambos. [onSem6Cambio] se
     * llama cuando se escribió algo en Semana 6, para que la pantalla recargue. */
    fun registrarPagoDesdeTicket(context: android.content.Context, uri: android.net.Uri, onSem6Cambio: () -> Unit = {}) {
        viewModelScope.launch {
            _ticketPagoEnProgreso.value = true
            val datos = extraerDatosTicketDeImagen(context, uri)
            val cuTicket = datos.cu?.filter { it.isDigit() }.orEmpty()
            if (datos.nombre.isNullOrBlank() && cuTicket.isBlank() && datos.textoCompleto.isBlank()) {
                _ticketPagoEnProgreso.value = false
                _ticketPagoResultado.value = "No se pudo leer el nombre del cliente en el ticket"
                return@launch
            }
            val mensajes = mutableListOf<String>()
            val montoTxt = datos.monto?.let { "$" + "%.2f".format(java.util.Locale.US, it) }

            // Cada ticket leído con monto se guarda como ticket independiente para el total.
            datos.monto?.let { monto ->
                ticketPagoDao.insertar(
                    TicketPagoEntity(
                        id = "T:" + System.currentTimeMillis() + ":" + kotlin.math.abs(datos.textoCompleto.hashCode()),
                        fecha = System.currentTimeMillis(),
                        nombre = datos.nombre,
                        cu = datos.cu,
                        monto = monto
                    )
                )
            }

            // ── 1) Filtro Fecha (hoy) ──
            val candidatosHoy = matrizDao.getMatrizEnRango(inicioDeHoy(), finDeHoy())
                .filter { !it.estado.equals("PASE", ignoreCase = true) }
            val busquedaFf = buscarClienteDeTicket(datos, candidatosHoy, { it.nombre }, { it.folioP })
            val matchFf = busquedaFf.match
            if (matchFf != null) {
                val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                matrizDao.marcarPagadoConMonto(matchFf.id, datos.monto ?: 0.0, hora)
                visitaMapaDao.registrarVisitaHoy("M:${matchFf.id}", matchFf.nombre, matchFf.ubicacion, matchFf.id)
                mensajes += if (datos.monto != null) "Marcado como Pagado: ${matchFf.nombre} ($montoTxt)"
                else "Marcado como Pagado: ${matchFf.nombre} (no se detectó el monto, revísalo manualmente)"
            } else if (busquedaFf.ambiguos.isNotEmpty()) {
                mensajes += if (busquedaFf.porTexto) "Varios clientes de hoy aparecen en el ticket: ${busquedaFf.ambiguos.joinToString()}. Márcalo manualmente"
                else "Varios clientes de hoy coinciden con \"${datos.nombre}\": ${busquedaFf.ambiguos.joinToString()}. Márcalo manualmente"
            }

            // ── 2) Semana 6 (hoja de la semana actual; si no hay red, la última copia guardada) ──
            val itemsSem6 = try { repository.fetchSem6Data(currentSem6SheetName()) } catch (e: Exception) { emptyList() }
                .ifEmpty { sem6CacheStore.load()?.first.orEmpty() }
            val busquedaS6 = buscarClienteDeTicket(datos, itemsSem6, { it.nombre }, { it.cu })
            val matchS6 = busquedaS6.match
            if (matchS6 != null) {
                val abono = matchS6.abono.replace("[^0-9.]".toRegex(), "").toDoubleOrNull()
                val monto = datos.monto
                when {
                    monto == null -> mensajes += "Semana 6: encontré a ${matchS6.nombre} pero no se detectó el monto, no se marcó Recuperado"
                    abono == null || abono <= 0.0 -> mensajes += "Semana 6: ${matchS6.nombre} no tiene Abono capturado, no se marcó Recuperado"
                    monto + 0.005 < abono -> mensajes += "Semana 6: ${matchS6.nombre} pagó $montoTxt, menos del Abono ($${"%.2f".format(java.util.Locale.US, abono)}); no se marcó Recuperado"
                    else -> {
                        val ok = try {
                            repository.updateSem6Susceptible(matchS6.id, "Recuperado", sheetName = currentSem6SheetName())
                        } catch (e: Exception) { false }
                        if (ok) {
                            sem6CacheStore.load()?.first?.let { guardados ->
                                sem6CacheStore.save(guardados.map { if (it.id == matchS6.id) it.copy(susceptible = "Recuperado") else it })
                            }
                            onSem6Cambio()
                            mensajes += "Semana 6: ${matchS6.nombre} marcado Recuperado ($montoTxt ≥ Abono)"
                        } else mensajes += "Semana 6: no se pudo marcar a ${matchS6.nombre} como Recuperado (revisa tu conexión)"
                    }
                }
            } else if (busquedaS6.ambiguos.isNotEmpty()) {
                mensajes += "Semana 6: varios clientes coinciden (${busquedaS6.ambiguos.joinToString()}). Márcalo manualmente"
            }

            _ticketPagoEnProgreso.value = false
            _ticketPagoResultado.value = if (mensajes.isEmpty())
                "No se encontró a \"${datos.nombre.orEmpty()}\" en Filtro Fecha (hoy) ni en Semana 6"
            else mensajes.joinToString("\n")
        }
    }
}

/** Resultado de buscar al cliente del ticket: [match] si hubo uno solo; [ambiguos] (nombres) si
 * varios empataron; [porTexto] indica que el empate fue por el respaldo de texto completo. */
internal class BusquedaTicket<T>(val match: T?, val ambiguos: List<String> = emptyList(), val porTexto: Boolean = false)

/** Busca al cliente del ticket entre [candidatos]: 1) CU exacto, 2) nombre tolerante (todas las
 * palabras del nombre más corto dentro del más largo), 3) respaldo: todas las palabras del nombre
 * del candidato en cualquier parte del texto del ticket. Misma lógica para Filtro Fecha y Semana 6. */
internal fun <T> buscarClienteDeTicket(
    datos: DatosTicketOcr,
    candidatos: List<T>,
    nombreDe: (T) -> String,
    cuDe: (T) -> String?
): BusquedaTicket<T> {
    val cuTicket = datos.cu?.filter { it.isDigit() }.orEmpty()
    // 1) CU (número de cliente): es único, así que si coincide es el cliente.
    if (cuTicket.length >= 8) {
        candidatos.firstOrNull { cuDe(it)?.filter { c -> c.isDigit() } == cuTicket }?.let { return BusquedaTicket(it) }
    }
    // 2) Nombre tolerante: el ticket trae el nombre completo (con apellido materno) y en la lista
    //    a veces está abreviado, o con otro orden/un typo del OCR.
    if (!datos.nombre.isNullOrBlank()) {
        val tokensTicket = tokensNombre(datos.nombre)
        val puntuados = candidatos.mapNotNull { c ->
            val tokensC = tokensNombre(nombreDe(c))
            val (corto, largo) = if (tokensC.size <= tokensTicket.size) tokensC to tokensTicket else tokensTicket to tokensC
            if (corto.size < 2) return@mapNotNull null
            val enComun = corto.count { t -> largo.any { l -> tokenParecido(t, l) } }
            if (enComun == corto.size) c to enComun else null
        }
        val mejor = puntuados.maxOfOrNull { it.second }
        val ganadores = puntuados.filter { it.second == mejor }.map { it.first }
        if (ganadores.size > 1) return BusquedaTicket(null, ganadores.map(nombreDe))
        ganadores.firstOrNull()?.let { return BusquedaTicket(it) }
    }
    // 3) Respaldo: nombre incompleto en el renglón (partido en varios renglones o mal leído).
    if (datos.textoCompleto.isNotBlank()) {
        val tokensTexto = tokensNombre(datos.textoCompleto)
        val puntuados = candidatos.mapNotNull { c ->
            val tokensC = tokensNombre(nombreDe(c))
            if (tokensC.size < 2) return@mapNotNull null
            if (tokensC.all { t -> tokensTexto.any { l -> tokenParecido(t, l) } }) c to tokensC.size else null
        }
        val mejor = puntuados.maxOfOrNull { it.second }
        val ganadores = puntuados.filter { it.second == mejor }.map { it.first }
        if (ganadores.size > 1) return BusquedaTicket(null, ganadores.map(nombreDe), porTexto = true)
        ganadores.firstOrNull()?.let { return BusquedaTicket(it) }
    }
    return BusquedaTicket(null)
}

/** Palabras del nombre en mayúsculas y sin acentos, sin puntuación ni partículas sueltas. */
internal fun tokensNombre(nombre: String): List<String> =
    quitarAcentos(nombre).uppercase().split(Regex("[^A-Z]+")).filter { it.length >= 2 }

/** Igual, o a 1 letra de diferencia (error típico de OCR) si la palabra tiene 5+ letras. */
internal fun tokenParecido(a: String, b: String): Boolean {
    if (a == b) return true
    if (a.length < 5 || b.length < 5 || kotlin.math.abs(a.length - b.length) > 1) return false
    var i = 0; var j = 0; var difs = 0
    while (i < a.length && j < b.length) {
        if (a[i] == b[j]) { i++; j++; continue }
        if (++difs > 1) return false
        when {
            a.length > b.length -> i++
            b.length > a.length -> j++
            else -> { i++; j++ }
        }
    }
    return difs + (a.length - i) + (b.length - j) <= 1
}
