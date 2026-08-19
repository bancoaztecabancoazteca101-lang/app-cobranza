package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class Sem6ViewModel(
    private val repository: SheetsRepository,
    private val cacheStore: Sem6CacheStore,
    val driveHelper: DriveHelper
) : ViewModel() {
    private val _items = MutableStateFlow<List<Sem6Item>>(emptyList())
    val items: StateFlow<List<Sem6Item>> = _items

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _lastUpdated = MutableStateFlow<Long?>(null)
    val lastUpdated: StateFlow<Long?> = _lastUpdated

    private val _isFromCache = MutableStateFlow(false)
    val isFromCache: StateFlow<Boolean> = _isFromCache

    private val _isSavingNotas = MutableStateFlow(false)
    val isSavingNotas: StateFlow<Boolean> = _isSavingNotas

    private val _errorNotas = MutableStateFlow<String?>(null)
    val errorNotas: StateFlow<String?> = _errorNotas

    /** Guarda Se Contiene/Susceptible/Observaciones para un registro y actualiza la lista en
     * memoria de inmediato (optimista) para que el usuario vea el cambio sin esperar el refresh. */
    fun guardarNotas(id: String, seContiene: String, susceptible: String, observaciones: String, onDone: (Boolean) -> Unit = {}) {
        _isSavingNotas.value = true
        _errorNotas.value = null
        viewModelScope.launch {
            try {
                val ok = repository.updateSem6Notas(id, seContiene, susceptible, observaciones)
                if (ok) {
                    _items.value = _items.value.map { item ->
                        if (item.id == id) item.copy(seContiene = seContiene, susceptible = susceptible, observaciones = observaciones) else item
                    }
                    cacheStore.save(_items.value)
                } else {
                    _errorNotas.value = "No se encontró el registro en la hoja de esta semana"
                }
                onDone(ok)
            } catch (e: Exception) {
                _errorNotas.value = e.message ?: "No se pudo guardar"
                onDone(false)
            } finally {
                _isSavingNotas.value = false
            }
        }
    }

    init {
        // Muestra de inmediato lo último conocido (si hay) mientras llega la respuesta en vivo.
        cacheStore.load()?.let { (cachedItems, ts) ->
            _items.value = cachedItems
            _lastUpdated.value = ts
            _isFromCache.value = true
        }
        cargar()
    }

    fun cargar() {
        if (_isLoading.value) return
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val fresh = repository.fetchSem6Data()
                _items.value = fresh
                _isFromCache.value = false
                _lastUpdated.value = System.currentTimeMillis()
                cacheStore.save(fresh)
            } catch (e: Exception) {
                // No se borra lo que ya había en pantalla (cache o carga anterior).
                _error.value = e.message ?: "No se pudo actualizar"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
