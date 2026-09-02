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

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredList: StateFlow<List<MatrizEntity>> = combine(_desde, _hasta, _orden, _miUbicacion) { d, h, o, loc ->
        Triple(d, h, o) to loc
    }.flatMapLatest { (t, loc) ->
        val (d, h, o) = t
        matrizDao.getAllMatriz().map { list ->
            val enRango = if (d != null && h != null) {
                list.filter { val f = it.fecha; f != null && f in d..h && !it.estado.equals("PASE", ignoreCase = true) }
            } else {
                list.filter { !it.estado.equals("PASE", ignoreCase = true) }
            }
            ordenar(enRango, o, loc)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
}