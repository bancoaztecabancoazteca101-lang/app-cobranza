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
    val driveHelper: DriveHelper
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
            matrizDao.updateEstadoYHora(id, nuevoEstado, nuevaHora)
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

    /** Lee la foto del ticket de cobranza (OCR), busca el registro del día cuyo nombre
     * coincida (tolerante a acentos/mayúsculas, como el resto de la app) y lo marca "Pagado"
     * con el monto leído. Si hay más de un match por nombre, toma el primero -- no debería
     * haber nombres repetidos en Filtro Fecha. */
    fun registrarPagoDesdeTicket(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            _ticketPagoEnProgreso.value = true
            val datos = extraerDatosTicketDeImagen(context, uri)
            val cuTicket = datos.cu?.filter { it.isDigit() }.orEmpty()
            if (datos.nombre.isNullOrBlank() && cuTicket.isBlank() && datos.textoCompleto.isBlank()) {
                _ticketPagoEnProgreso.value = false
                _ticketPagoResultado.value = "No se pudo leer el nombre del cliente en el ticket"
                return@launch
            }
            val candidatos = matrizDao.getMatrizEnRango(inicioDeHoy(), finDeHoy())
                .filter { !it.estado.equals("PASE", ignoreCase = true) }
            // 1) CU (número de cliente): es único, así que si coincide es el cliente.
            var match = if (cuTicket.length >= 8) candidatos.firstOrNull { it.folioP?.filter { c -> c.isDigit() } == cuTicket } else null
            // 2) Nombre tolerante: el ticket trae el nombre completo (con apellido materno) y en
            //    Matriz a veces está abreviado, o con otro orden/un typo del OCR. Se compara por
            //    palabras: todas las palabras del nombre más corto deben estar en el más largo.
            if (match == null && !datos.nombre.isNullOrBlank()) {
                val tokensTicket = tokensNombre(datos.nombre)
                val puntuados = candidatos.mapNotNull { c ->
                    val tokensC = tokensNombre(c.nombre)
                    val (corto, largo) = if (tokensC.size <= tokensTicket.size) tokensC to tokensTicket else tokensTicket to tokensC
                    if (corto.size < 2) return@mapNotNull null
                    val enComun = corto.count { t -> largo.any { l -> tokenParecido(t, l) } }
                    if (enComun == corto.size) c to enComun else null
                }
                val mejor = puntuados.maxOfOrNull { it.second }
                val ganadores = puntuados.filter { it.second == mejor }.map { it.first }
                if (ganadores.size > 1) {
                    _ticketPagoEnProgreso.value = false
                    _ticketPagoResultado.value = "Varios clientes de hoy coinciden con \"${datos.nombre}\": ${ganadores.joinToString { it.nombre }}. Márcalo manualmente"
                    return@launch
                }
                match = ganadores.firstOrNull()
            }
            // 3) Respaldo: si el renglón del nombre salió incompleto (nombre largo partido en varios
            //    renglones o mal leído), se busca a qué cliente de hoy le aparecen TODAS las palabras
            //    de su nombre en cualquier parte del texto del ticket. Gana el de más palabras.
            if (match == null && datos.textoCompleto.isNotBlank()) {
                val tokensTexto = tokensNombre(datos.textoCompleto)
                val puntuados = candidatos.mapNotNull { c ->
                    val tokensC = tokensNombre(c.nombre)
                    if (tokensC.size < 2) return@mapNotNull null
                    if (tokensC.all { t -> tokensTexto.any { l -> tokenParecido(t, l) } }) c to tokensC.size else null
                }
                val mejor = puntuados.maxOfOrNull { it.second }
                val ganadores = puntuados.filter { it.second == mejor }.map { it.first }
                if (ganadores.size > 1) {
                    _ticketPagoEnProgreso.value = false
                    _ticketPagoResultado.value = "Varios clientes de hoy aparecen en el ticket: ${ganadores.joinToString { it.nombre }}. Márcalo manualmente"
                    return@launch
                }
                match = ganadores.firstOrNull()
            }
            _ticketPagoEnProgreso.value = false
            if (match == null) {
                _ticketPagoResultado.value = "No se encontró un registro de hoy con el nombre \"${datos.nombre.orEmpty()}\""
                return@launch
            }
            val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            matrizDao.marcarPagadoConMonto(match.id, datos.monto ?: 0.0, hora)
            _ticketPagoResultado.value = if (datos.monto != null)
                "Marcado como Pagado: ${match.nombre} ($${"%.2f".format(java.util.Locale.US, datos.monto)})"
            else "Marcado como Pagado: ${match.nombre} (no se detectó el monto, revísalo manualmente)"
        }
    }
}

/** Palabras del nombre en mayúsculas y sin acentos, sin puntuación ni partículas sueltas. */
private fun tokensNombre(nombre: String): List<String> =
    quitarAcentos(nombre).uppercase().split(Regex("[^A-Z]+")).filter { it.length >= 2 }

/** Igual, o a 1 letra de diferencia (error típico de OCR) si la palabra tiene 5+ letras. */
private fun tokenParecido(a: String, b: String): Boolean {
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
