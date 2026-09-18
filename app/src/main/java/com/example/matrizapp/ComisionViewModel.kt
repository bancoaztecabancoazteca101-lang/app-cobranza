package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Reporte "Comisión" (submenú de Control) -- 100% local y de captura manual: Diego teclea,
 * por cada segmento de semana de atraso (1-2, 3, 4-6, 7-9), lo cobrado "al momento" (directo)
 * y lo cobrado "indirecta", más la meta del periodo (confirmado por Diego 18/09/2026: no hay
 * fórmula de negocio detrás, los 3 datos son captura manual). Fila única (id fijo = 1), mismo
 * patrón que Penalización/VelocidadEntity. Ver ComisionEntity.calcular() abajo para el Total
 * por fila (al momento + indirecta) y el % de avance contra la meta. */
class ComisionViewModel(private val dao: ComisionDao) : ViewModel() {
    val item: StateFlow<ComisionEntity> = dao.getFlow()
        .map { it ?: ComisionEntity() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ComisionEntity())

    fun guardar(nuevo: ComisionEntity) {
        viewModelScope.launch {
            dao.guardar(nuevo.copy(id = 1, lastUpdate = System.currentTimeMillis()))
        }
    }
}

data class ComisionFila(val etiqueta: String, val alMomento: Double, val indirecta: Double) {
    val total: Double get() = alMomento + indirecta
}

data class ComisionCalculo(
    val filas: List<ComisionFila>,
    val totalGeneral: Double,
    val avance: Double
)

fun ComisionEntity.calcular(): ComisionCalculo {
    val filas = listOf(
        ComisionFila("1 a 2", momento12, indirecta12),
        ComisionFila("3", momento3, indirecta3),
        ComisionFila("4 a 6", momento46, indirecta46),
        ComisionFila("7 a 9", momento79, indirecta79)
    )
    val totalGeneral = filas.sumOf { it.total }
    val avance = if (meta != 0.0) totalGeneral / meta else 0.0
    return ComisionCalculo(filas, totalGeneral, avance)
}
