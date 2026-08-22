package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class Sem6ViewModel(
    private val repository: SheetsRepository,
    private val cacheStore: Sem6CacheStore,
    val driveHelper: DriveHelper
) : ViewModel() {
    private val _itemsRaw = MutableStateFlow<List<Sem6Item>>(emptyList())

    private val _orden = MutableStateFlow(OrdenLista.ORIGINAL)
    val orden: StateFlow<OrdenLista> = _orden
    private val _miUbicacion = MutableStateFlow<Pair<Double, Double>?>(null)
    fun setOrden(o: OrdenLista, miUbicacion: Pair<Double, Double>? = null) {
        _orden.value = o
        if (miUbicacion != null) _miUbicacion.value = miUbicacion
    }

    private val formatoFechaSem6 = java.text.SimpleDateFormat("d/M/yyyy HH:mm", java.util.Locale("es", "MX"))
    private fun distanciaOrNull(raw: String?, miUbicacion: Pair<Double, Double>): Double? =
        parseLatLngOrden(raw)?.let { distanciaKm(miUbicacion, it) }

    private fun ordenar(list: List<Sem6Item>, o: OrdenLista, miUbicacion: Pair<Double, Double>?): List<Sem6Item> = when (o) {
        OrdenLista.FECHA_HORA_RECIENTE -> list.sortedByDescending {
            try { formatoFechaSem6.parse(it.ultimaFechaVisita)?.time ?: 0L } catch (e: Exception) { 0L }
        }
        OrdenLista.FECHA_HORA_ANTIGUA -> list.sortedBy {
            try { formatoFechaSem6.parse(it.ultimaFechaVisita)?.time ?: Long.MAX_VALUE } catch (e: Exception) { Long.MAX_VALUE }
        }
        OrdenLista.UBICACION_CERCA -> if (miUbicacion == null) list else list.sortedBy { distanciaOrNull(it.ubicacion, miUbicacion) ?: Double.MAX_VALUE }
        OrdenLista.UBICACION_LEJOS -> if (miUbicacion == null) list else list.sortedByDescending { distanciaOrNull(it.ubicacion, miUbicacion) ?: -1.0 }
        OrdenLista.ALFABETICO_AZ -> list.sortedBy { it.nombre.lowercase() }
        OrdenLista.ALFABETICO_ZA -> list.sortedByDescending { it.nombre.lowercase() }
        OrdenLista.ORIGINAL -> list
    }

    val items: StateFlow<List<Sem6Item>> = combine(_itemsRaw, _orden, _miUbicacion) { list, o, loc -> ordenar(list, o, loc) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    fun guardarNotas(id: String, seContiene: String, susceptible: String, observaciones: String, capital: String, onDone: (Boolean) -> Unit = {}) {
        _isSavingNotas.value = true
        _errorNotas.value = null
        viewModelScope.launch {
            try {
                val ok = repository.updateSem6Notas(id, seContiene, susceptible, observaciones, capital)
                if (ok) {
                    _itemsRaw.value = _itemsRaw.value.map { item ->
                        if (item.id == id) item.copy(seContiene = seContiene, susceptible = susceptible, observaciones = observaciones, capital = capital) else item
                    }
                    cacheStore.save(_itemsRaw.value)
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
            _itemsRaw.value = cachedItems
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
                _itemsRaw.value = fresh
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
