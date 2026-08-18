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

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredList: StateFlow<List<FiltroFechaEntity>> = combine(_desde, _hasta) { d, h ->
        d to h
    }.flatMapLatest { (d, h) ->
        if (d != null && h != null) {
            filtroDao.getItemsByRange(d, h)
        } else {
            filtroDao.getAll()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setRangoFecha(desde: Long?, hasta: Long?) {
        _desde.value = desde
        _hasta.value = hasta
    }

    /** Guarda el nuevo status localmente (Room) y lo marca dirty para subirlo al Sheet en el próximo sync. */
    fun guardarEstado(id: String, nuevoEstado: String) {
        viewModelScope.launch { filtroDao.updateEstadoLocal(id, nuevoEstado) }
    }
}