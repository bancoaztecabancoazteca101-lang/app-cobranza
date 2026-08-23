package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class FiltroFechaViewModel(
    private val repository: SheetsRepository,
    private val filtroDao: FiltroFechaDao,
    val driveHelper: DriveHelper,
    private val notificacionesHelper: NotificacionesHelper
) : ViewModel() {
    init {
        // Cada vez que cambian los datos de Filtro Fecha (sync, edición de estado/hora, etc.)
        // se revisan los "Retorno" con hora y se reprograman/cancelan las alarmas locales.
        viewModelScope.launch {
            filtroDao.getAll().collect { items ->
                notificacionesHelper.sincronizarAlarmasRetorno(items)
            }
        }
    }

    private val _desde = MutableStateFlow<Long?>(null)
    val desde: StateFlow<Long?> = _desde

    private val _hasta = MutableStateFlow<Long?>(null)
    val hasta: StateFlow<Long?> = _hasta

    private val _orden = MutableStateFlow(OrdenLista.ORIGINAL)
    val orden: StateFlow<OrdenLista> = _orden
    private val _miUbicacion = MutableStateFlow<Pair<Double, Double>?>(null)
    fun setOrden(o: OrdenLista, miUbicacion: Pair<Double, Double>? = null) {
        _orden.value = o
        if (miUbicacion != null) _miUbicacion.value = miUbicacion
    }

    private fun ordenar(list: List<FiltroFechaEntity>, o: OrdenLista, miUbicacion: Pair<Double, Double>?): List<FiltroFechaEntity> = when (o) {
        OrdenLista.FECHA_HORA_RECIENTE -> list.sortedByDescending { it.fecha }
        OrdenLista.FECHA_HORA_ANTIGUA -> list.sortedBy { it.fecha }
        OrdenLista.UBICACION_CERCA -> if (miUbicacion == null) list else list.sortedBy { distanciaOrNull(it.ubicacion, miUbicacion) ?: Double.MAX_VALUE }
        OrdenLista.UBICACION_LEJOS -> if (miUbicacion == null) list else list.sortedByDescending { distanciaOrNull(it.ubicacion, miUbicacion) ?: -1.0 }
        OrdenLista.ALFABETICO_AZ -> list.sortedBy { it.nombre.lowercase() }
        OrdenLista.ALFABETICO_ZA -> list.sortedByDescending { it.nombre.lowercase() }
        OrdenLista.ORIGINAL -> list
    }
    private fun distanciaOrNull(raw: String?, miUbicacion: Pair<Double, Double>): Double? =
        parseLatLngOrden(raw)?.let { distanciaKm(miUbicacion, it) }

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredList: StateFlow<List<FiltroFechaEntity>> = combine(_desde, _hasta, _orden, _miUbicacion) { d, h, o, loc ->
        Triple(d, h, o) to loc
    }.flatMapLatest { (t, loc) ->
        val (d, h, o) = t
        val base = if (d != null && h != null) {
            filtroDao.getItemsByRange(d, h)
        } else {
            filtroDao.getAll()
        }
        base.map { list -> ordenar(list, o, loc) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setRangoFecha(desde: Long?, hasta: Long?) {
        _desde.value = desde
        _hasta.value = hasta
    }

    /** Guarda el nuevo status localmente (Room) y lo marca dirty para subirlo al Sheet en el próximo sync. */
    fun guardarEstado(id: String, nuevoEstado: String) {
        viewModelScope.launch { filtroDao.updateEstadoLocal(id, nuevoEstado) }
    }

    /** Guarda status y hora localmente (Room) y los marca dirty para subirlos al Sheet
     * (columnas H y N respectivamente) en el próximo sync. */
    fun guardarEstadoYHora(id: String, nuevoEstado: String, nuevaHora: String, onResult: (String?) -> Unit = {}) {
        viewModelScope.launch {
            filtroDao.updateEstadoYHoraLocal(id, nuevoEstado, nuevaHora)
            onResult(notificacionesHelper.evaluarProgramacion(nuevoEstado, nuevaHora))
        }
    }
}