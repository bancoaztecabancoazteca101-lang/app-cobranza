package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Reporte semanal "Penalización" (Tabla Velocidades) -- 100% local. Diego captura los datos
 * a mano con el botón de editar en PenalizacionScreen; esta clase solo guarda/lee esa fila
 * única y expone el cálculo de las fórmulas confirmadas por él (ver VelocidadEntity.calcular()
 * más abajo). No toca Sheets ni su cuenta de Google. */
class PenalizacionViewModel(private val dao: VelocidadDao) : ViewModel() {
    val item: StateFlow<VelocidadEntity> = dao.getFlow()
        .map { it ?: VelocidadEntity() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VelocidadEntity())

    fun guardar(nuevo: VelocidadEntity) {
        viewModelScope.launch {
            dao.guardar(nuevo.copy(id = 1, lastUpdate = System.currentTimeMillis()))
        }
    }
}

/** Resultado de aplicar a [VelocidadEntity] las fórmulas confirmadas por Diego con capturas de
 * pantalla de la hoja "Tabla Velocidades" (sesión 16/09/2026):
 * %3-6 = $3-6/REQUE 3-6; $ARRIBA = $3-6-(REQUE 3-6*0.05); REQUE A FAVOR = ($3-6*20)-REQUE 3-6;
 * PERDIDA = $CAPITAL*0.25%; ME FALTAN $ = PLAN100-TOTAL; META DIAR = PLAN100/6;
 * DEFICIT O EXCEDEN = TOTAL-(META DIAR*DÍAS OP). El total de la semana y el avance vs plan
 * (columna "PLAN" de Sheets) también se calculan aquí por ser sumas/razones directas. */
data class VelocidadCalculo(
    val total: Double,
    val planAvance: Double,
    val porcentaje36: Double,
    val arriba: Double,
    val requeFavor: Double,
    val perdida: Double,
    val meFaltan: Double,
    val metaDiaria: Double,
    val deficit: Double
)

fun VelocidadEntity.calcular(): VelocidadCalculo {
    val total = lunes + martes + miercoles + jueves + viernes + sabado + domingo
    val planAvance = if (plan100 != 0.0) total / plan100 else 0.0
    val porcentaje36 = if (reque36 != 0.0) monto36 / reque36 else 0.0
    val arriba = monto36 - (reque36 * 0.05)
    val requeFavor = (monto36 * 20) - reque36
    val perdida = capital * 0.0025
    val meFaltan = plan100 - total
    val metaDiaria = plan100 / 6
    val deficit = total - (metaDiaria * diasOp)
    return VelocidadCalculo(total, planAvance, porcentaje36, arriba, requeFavor, perdida, meFaltan, metaDiaria, deficit)
}
