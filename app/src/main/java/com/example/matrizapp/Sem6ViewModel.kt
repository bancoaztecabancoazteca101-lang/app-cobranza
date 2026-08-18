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
