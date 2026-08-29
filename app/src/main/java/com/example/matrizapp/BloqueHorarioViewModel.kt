package com.example.matrizapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

/** Cada escritura reprograma las alarmas (scheduler.reprogramarTodos()) — barato y evita
 * tener que rastrear manualmente qué cambió. */
class BloqueHorarioViewModel(
    private val dao: BloqueHorarioDao,
    private val scheduler: LlamadaAutomaticaScheduler
) : ViewModel() {

    val bloques: StateFlow<List<BloqueHorarioEntity>> = dao.observarBloques()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
