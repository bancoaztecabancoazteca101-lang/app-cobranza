package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Reporte "Bolsa Gerencia" (4to submenú de Control, 18/09/2026) -- 100% local y de captura
 * manual: Diego teclea Cobranza Gerencia, Cumplimiento del plan, Monto Base, Número de
 * Gestores, Cobranza 1 a 9 Gestor y Cobranza 1 a 9 Gerencia; nada se extrae de Sheets ni de
 * ningún lado automático. Fila única (id fijo = 1), mismo patrón que ComisionEntity/
 * VelocidadEntity. Ver BolsaGerenciaEntity.calcular() abajo para las fórmulas y la condición de
 * liberación (mínimo 85% de cumplimiento del plan; por debajo, la comisión es $0). */
class BolsaGerenciaViewModel(private val dao: BolsaGerenciaDao) : ViewModel() {
    val item: StateFlow<BolsaGerenciaEntity> = dao.getFlow()
        .map { it ?: BolsaGerenciaEntity() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BolsaGerenciaEntity())

    fun guardar(nuevo: BolsaGerenciaEntity) {
        viewModelScope.launch {
            dao.guardar(nuevo.copy(id = 1, lastUpdate = System.currentTimeMillis()))
        }
    }
}

/** Cumplimiento mínimo del plan para liberar la comisión (confirmado por Diego 18/09/2026):
 * por debajo de este umbral la comisión es $0, sin importar el resto de los datos. */
private const val CUMPLIMIENTO_MINIMO_LIBERACION = 0.85

data class BolsaGerenciaCalculo(
    val bolsaGerencia: Double,
    val bolsaARepartir: Double,
    val porcentajeCumplimiento1a9: Double,
    val comisionLiberada: Boolean,
    val comision: Double
)

fun BolsaGerenciaEntity.calcular(): BolsaGerenciaCalculo {
    val bolsaGerencia = montoBase * numeroGestores
    val bolsaARepartir = bolsaGerencia * cumplimientoPlan
    val porcentajeCumplimiento1a9 = if (cobranza1a9Gerencia != 0.0) (cobranza1a9Gestor / cobranza1a9Gerencia) else 0.0
    val liberada = cumplimientoPlan >= CUMPLIMIENTO_MINIMO_LIBERACION
    val comision = if (liberada) bolsaARepartir * porcentajeCumplimiento1a9 else 0.0
    return BolsaGerenciaCalculo(bolsaGerencia, bolsaARepartir, porcentajeCumplimiento1a9, liberada, comision)
}
