package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Pantalla "Penalización", colgada del submenú de Control -- muestra SOLO la fila de Diego
 * (GIC = Constants.GIC_PROPIETARIO) de la hoja "Tabla Velocidades", nunca la de sus compañeros.
 * Ver SheetsRepository.sincronizarVelocidad() para el mapeo de columnas. */
class PenalizacionViewModel(private val repository: SheetsRepository) : ViewModel() {
    val item: StateFlow<VelocidadEntity?> = repository.velocidadLocalFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { sincronizar() }

    fun sincronizar() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            _error.value = null
            repository.sincronizarVelocidad().onFailure { e -> _error.value = e.message ?: "Error al sincronizar" }
            _isSyncing.value = false
        }
    }

    fun limpiarError() { _error.value = null }
}
