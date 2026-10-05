package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Visitas de un día (lunes a domingo) de la semana actual. */
data class DiaSemana(val fecha: LocalDate, val visitas: List<MatrizEntity>)

/** "Filtro Semanal": solo las visitas (fecha de Matriz) de la SEMANA ACTUAL, de lunes a DOMINGO,
 * separadas por día. Lee directo de MatrizEntity igual que Filtro Fecha; no trae nada de otras
 * semanas. */
class FiltroSemanalViewModel(
    private val matrizDao: MatrizDao,
    val driveHelper: DriveHelper
) : ViewModel() {

    private val zona = ZoneId.systemDefault()

    private fun lunesActual(): LocalDate =
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun armarSemana(todos: List<MatrizEntity>): List<DiaSemana> {
        val lunes = lunesActual()
        val visibles = todos.filter { it.fecha != null && !it.estado.equals("PASE", ignoreCase = true) }
        return (0..6).map { i ->
            val dia = lunes.plusDays(i.toLong())
            val ini = dia.atStartOfDay(zona).toInstant().toEpochMilli()
            val fin = dia.plusDays(1).atStartOfDay(zona).toInstant().toEpochMilli() - 1
            DiaSemana(dia, visibles.filter { (it.fecha ?: 0L) in ini..fin })
        }
    }

    // Pestaña seleccionada (0 = lunes ... 6 = domingo). Por defecto el día de hoy.
    private val _dia = MutableStateFlow(LocalDate.now().dayOfWeek.value - 1)
    val dia: StateFlow<Int> = _dia
    fun setDia(i: Int) { _dia.value = i.coerceIn(0, 6) }

    val semana: StateFlow<List<DiaSemana>> = matrizDao.getAllMatriz()
        .map { armarSemana(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), armarSemana(emptyList()))

    /** Guarda status y hora localmente (Room) y lo marca dirty para subirlo en el próximo sync. */
    fun guardarEstadoYHora(id: String, nuevoEstado: String, nuevaHora: String, notificacionesHelper: NotificacionesHelper, onResult: (String?) -> Unit = {}) {
        viewModelScope.launch {
            matrizDao.updateEstadoYHora(id, nuevoEstado, nuevaHora)
            onResult(notificacionesHelper.evaluarProgramacion(nuevoEstado, nuevaHora))
        }
    }
}
