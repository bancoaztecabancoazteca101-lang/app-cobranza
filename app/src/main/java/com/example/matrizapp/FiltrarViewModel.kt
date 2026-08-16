package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FiltrarViewModel(
    private val filtrarDao: FiltrarDao
) : ViewModel() {
    val items: StateFlow<List<FiltrarEntity>> = filtrarDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun guardarGestion(id: String, nuevoEstado: String, obs: String) {
        viewModelScope.launch { filtrarDao.updateGestionLocal(id, nuevoEstado, obs) }
    }
}
