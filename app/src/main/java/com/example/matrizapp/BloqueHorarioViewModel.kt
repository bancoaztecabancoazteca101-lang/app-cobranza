package com.example.matrizapp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

/** Cada escritura reprograma las alarmas (scheduler.reprogramarTodos()) — barato y evita
 * tener que rastrear manualmente qué cambió. */
class BloqueHorarioViewModel(
    private val dao: BloqueHorarioDao,
    private val scheduler: LlamadaAutomaticaScheduler,
    private val context: Context,
    private val configDao: ConfiguracionAutomatizacionDao
) : ViewModel() {

    val bloques: StateFlow<List<BloqueHorarioEntity>> = dao.observarBloques()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Config exclusiva del flujo automático — independiente de la pantalla manual de
     * Llamadas. Se siembra sola la primera vez que se observa (obtenerOSembrar). */
    val configAutomatizacion: StateFlow<ConfiguracionAutomatizacionEntity> = flow {
        emit(configDao.obtenerOSembrar())
        emitAll(configDao.observar())
    }.filterNotNull().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConfiguracionAutomatizacionEntity())

    fun setSimAutomatizacion(subscriptionId: Int?) = viewModelScope.launch {
        configDao.actualizar(configAutomatizacion.value.copy(simSeleccionada = subscriptionId))
    }

    fun setOcultarNumeroAutomatizacion(v: Boolean) = viewModelScope.launch {
        configDao.actualizar(configAutomatizacion.value.copy(ocultarNumero = v))
    }

    fun setSegundosPausaAutomatizacion(v: Int) = viewModelScope.launch {
        configDao.actualizar(configAutomatizacion.value.copy(segundosPausaEntreLlamadas = v.coerceIn(1, 300)))
    }

    fun setDuracionMaximaAutomatizacion(v: Int) = viewModelScope.launch {
        configDao.actualizar(configAutomatizacion.value.copy(duracionMaximaLlamada = v.coerceIn(5, 600)))
    }

    /** Interruptor general de toda la automatización (llamadas + SMS por bloque). Se guarda en
     * SharedPreferences (no Room) porque BootCompletedReceiver y los Workers lo leen sin
     * depender de que este ViewModel esté vivo. */
    private val _automatizacionActiva = MutableStateFlow(AutomatizacionPrefs.activa(context))
    val automatizacionActiva: StateFlow<Boolean> = _automatizacionActiva

    fun setAutomatizacionActiva(activa: Boolean) = viewModelScope.launch {
        AutomatizacionPrefs.setActiva(context, activa)
        _automatizacionActiva.value = activa
        scheduler.reprogramarTodos()
    }

    fun agregarBloque(hora: LocalTime) = viewModelScope.launch {
        dao.insertar(BloqueHorarioEntity.fromLocalTime(hora))
        scheduler.reprogramarTodos()
    }

    fun editarHora(bloque: BloqueHorarioEntity, nuevaHora: LocalTime) = viewModelScope.launch {
        dao.actualizar(bloque.copy(hora = nuevaHora.hour, minuto = nuevaHora.minute))
        scheduler.reprogramarTodos()
    }

    fun eliminarBloque(bloque: BloqueHorarioEntity) = viewModelScope.launch {
        dao.eliminar(bloque)
        scheduler.reprogramarTodos()
    }

    fun toggleActivo(bloque: BloqueHorarioEntity) = viewModelScope.launch {
        dao.setActivo(bloque.id, !bloque.activo)
        scheduler.reprogramarTodos()
    }
}
