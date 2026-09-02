package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

class ControlViewModel(controlDao: ControlDao, matrizDao: MatrizDao) : ViewModel() {
    // "Requerido por día" y "Requerido semana actual" se calculan 100% local a partir de
    // Matriz ya sincronizado en Room -- no dependen de la hoja "GraficaSuma" ni de ningún
    // Apps Script. Ambas excluyen registros con estado "PASE": un cliente que ya pasó no debe
    // sumar al saldo pendiente del día ni de la semana.
    val itemsHoy: StateFlow<List<ControlEntity>> = matrizDao.getAllMatriz()
        .map { registros -> calcularRequerido(registros) { fecha -> esHoy(fecha) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val itemsSemanaActual: StateFlow<List<ControlEntity>> = matrizDao.getAllMatriz()
        .map { registros -> calcularRequerido(registros) { fecha -> enSemanaActual(fecha) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

/** true si esta fecha (millis) cae en el mismo día calendario que hoy. */
private fun esHoy(fechaMillis: Long?): Boolean {
    if (fechaMillis == null || fechaMillis == 0L) return false
    val hoy = Calendar.getInstance()
    val fecha = Calendar.getInstance().apply { timeInMillis = fechaMillis }
    return hoy.get(Calendar.YEAR) == fecha.get(Calendar.YEAR) && hoy.get(Calendar.DAY_OF_YEAR) == fecha.get(Calendar.DAY_OF_YEAR)
}

/** true si esta fecha (millis) cae dentro de la semana ISO actual (lunes a domingo), misma
 * convención que currentSem6SheetName() en Sem6.kt (firstDayOfWeek=MONDAY, minimalDaysInFirstWeek=4). */
private fun enSemanaActual(fechaMillis: Long?): Boolean {
    if (fechaMillis == null || fechaMillis == 0L) return false
    val hoy = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY; minimalDaysInFirstWeek = 4 }
    val fecha = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY; minimalDaysInFirstWeek = 4
        timeInMillis = fechaMillis
    }
    return hoy.get(Calendar.YEAR) == fecha.get(Calendar.YEAR) &&
        hoy.get(Calendar.WEEK_OF_YEAR) == fecha.get(Calendar.WEEK_OF_YEAR)
}

/** Agrupa el "Req" (requisito) de Matriz por rango de Sem, sumando solo los registros que
 * pasan `perteneceAlPeriodo` (hoy o semana actual, según cuál se use) y que NO están en
 * status "PASE" -- un cliente que ya pasó no cuenta en el saldo pendiente. Misma estructura
 * de filas que "GraficaSuma": Sem 1-2, Sem 3, Sem 4-6, Sem 7-9, Total 3-6. */
private fun calcularRequerido(registros: List<MatrizEntity>, perteneceAlPeriodo: (Long?) -> Boolean): List<ControlEntity> {
    val delPeriodo = registros.mapNotNull { row ->
        if (row.estado.equals("PASE", ignoreCase = true)) return@mapNotNull null
        val sem = row.semana.trim().toIntOrNull() ?: return@mapNotNull null
        if (!perteneceAlPeriodo(row.fecha)) return@mapNotNull null
        val req = row.requisito.replace(",", "").replace("$", "").trim().toDoubleOrNull() ?: 0.0
        sem to req
    }
    fun sumaSem(rango: IntRange): Double = delPeriodo.filter { it.first in rango }.sumOf { it.second }

    val sem12 = sumaSem(1..2)
    val sem3 = sumaSem(3..3)
    val sem46 = sumaSem(4..6)
    val sem79 = sumaSem(7..9)
    val total36 = sem3 + sem46

    fun fmt(v: Double) = "%.2f".format(v)
    return listOf(
        ControlEntity("Sem 1-2", fmt(sem12)),
        ControlEntity("Sem 3", fmt(sem3)),
        ControlEntity("Sem 4-6", fmt(sem46)),
        ControlEntity("Sem 7-9", fmt(sem79)),
        ControlEntity("Total 3-6", fmt(total36))
    )
}
