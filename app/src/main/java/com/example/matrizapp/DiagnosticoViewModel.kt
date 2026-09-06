package com.example.matrizapp
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DiagnosticoViewModel(
    private val repository: SheetsRepository,
    private val context: Context
) : ViewModel() {

    private val _verificando = MutableStateFlow(false)
    val verificando: StateFlow<Boolean> = _verificando
    private val _resultadoVerificacion = MutableStateFlow<List<Discrepancia>?>(null)
    val resultadoVerificacion: StateFlow<List<Discrepancia>?> = _resultadoVerificacion
    private val _errorVerificacion = MutableStateFlow<String?>(null)
    val errorVerificacion: StateFlow<String?> = _errorVerificacion

    private val _cargandoDispositivos = MutableStateFlow(false)
    val cargandoDispositivos: StateFlow<Boolean> = _cargandoDispositivos
    private val _dispositivos = MutableStateFlow<List<DispositivoInfo>>(emptyList())
    val dispositivos: StateFlow<List<DispositivoInfo>> = _dispositivos
    private val _errorDispositivos = MutableStateFlow<String?>(null)
    val errorDispositivos: StateFlow<String?> = _errorDispositivos

    /** Reporta este dispositivo (modelo + build) en la hoja "Dispositivos". Se llama solo al
     * entrar a la pantalla, sin que el usuario tenga que hacer nada. Silenciosa si falla: no
     * es información crítica y se vuelve a intentar la próxima vez que se abra la pantalla. */
    fun reportarEsteDispositivo() {
        viewModelScope.launch {
            try {
                repository.reportarDispositivo(DeviceInfo.androidId(context), DeviceInfo.modelo(), DeviceInfo.buildId)
            } catch (e: Exception) { }
        }
    }

    fun cargarDispositivos() {
        viewModelScope.launch {
            _cargandoDispositivos.value = true
            _errorDispositivos.value = null
            try {
                _dispositivos.value = repository.listarDispositivos()
            } catch (e: Exception) {
                _errorDispositivos.value = e.message ?: "Error desconocido"
            }
            _cargandoDispositivos.value = false
        }
    }

    fun verificarMatriz() {
        viewModelScope.launch {
            _verificando.value = true
            _errorVerificacion.value = null
            _resultadoVerificacion.value = null
            try {
                _resultadoVerificacion.value = repository.verificarConsistenciaMatriz()
            } catch (e: Exception) {
                _errorVerificacion.value = e.message ?: "Error desconocido"
            }
            _verificando.value = false
        }
    }
}
