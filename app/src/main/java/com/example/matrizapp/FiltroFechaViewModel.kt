package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class FiltroFechaViewModel(
    private val repository: SheetsRepository,
    private val filtroDao: FiltroFechaDao,
    val driveHelper: DriveHelper
) : ViewModel() {
    private val _desde = MutableStateFlow<Long?>(null)
    val desde: StateFlow<Long?> = _desde

    private val _hasta = MutableStateFlow<Long?>(null)
    val hasta: StateFlow<Long?> = _hasta

    private val _orden = MutableStateFlow(OrdenLista.ORIGINAL)
    val orden: StateFlow<OrdenLista> = _orden
    fun setOrden(o: OrdenLista) { _orden.value = o }

    private fun ordenar(list: List<FiltroFechaEntity>, o: OrdenLista): List<FiltroFechaEntity> = when (o) {
        OrdenLista.FECHA_HORA -> list.sortedByDescending { it.fecha }
        OrdenLista.UBICACION -> list.sortedBy { it.ubicacion?.lowercase() ?: "" }
        OrdenLista.ALFABETICO -> list.sortedBy { it.nombre.lowercase() }
        OrdenLista.ORIGINAL -> list
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredList: StateFlow<List<FiltroFechaEntity>> = combine(_desde, _hasta, _orden) { d, h, o ->
        Triple(d, h, o)
    }.flatMapLatest { (d, h, o) ->
        val base = if (d != null && h != null) {
            filtroDao.getItemsByRange(d, h)
        } else {
            filtroDao.getAll()
        }
        base.map { list -> ordenar(list, o) }
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
    fun guardarEstadoYHora(id: String, nuevoEstado: String, nuevaHora: String) {
        viewModelScope.launch { filtroDao.updateEstadoYHoraLocal(id, nuevoEstado, nuevaHora) }
    }
}