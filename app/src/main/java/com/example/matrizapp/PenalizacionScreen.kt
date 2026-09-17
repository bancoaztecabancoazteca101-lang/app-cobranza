package com.example.matrizapp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Muestra solo la fila de Diego (GIC = Constants.GIC_PROPIETARIO) de "Tabla Velocidades",
 * nunca la de sus compañeros. Se sincroniza sola al entrar y con el botón de refrescar. */
@Composable
fun PenalizacionScreen(viewModel: PenalizacionViewModel) {
    val item by viewModel.item.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val error by viewModel.error.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Penalización", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = { viewModel.sincronizar() }, enabled = !isSyncing) {
                if (isSyncing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, contentDescription = "Sincronizar")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))

        error?.let { msg ->
            ClayCard(containerColor = Color(0xFFFDEAEA)) {
                Text("No se pudo sincronizar: $msg", color = Color(0xFFC62828), style = MaterialTheme.typography.bodySmall)
            }
        }

        val fila = item
        if (fila == null) {
            if (!isSyncing) {
                Box(Modifier.fillMaxWidth().padding(top = 32.dp), Alignment.Center) {
                    Text("Sin datos todavía -- toca el botón de sincronizar", color = Color.Gray)
                }
            }
        } else {
            SeccionTitulo("Resumen")
            ClayCard {
                FilaDato("RK", fila.rk)
                FilaDato("GIC", fila.gic)
                FilaDato("Total semana", fila.total, destacado = true)
                FilaDato("Avance vs plan", fila.planAvance)
            }

            SeccionTitulo("Por día")
            ClayCard {
                FilaDato("Plan 100%", fila.plan100)
                FilaDato("Lunes", fila.lunes)
                FilaDato("Martes", fila.martes)
                FilaDato("Miércoles", fila.miercoles)
                FilaDato("Jueves", fila.jueves)
                FilaDato("Viernes", fila.viernes)
                FilaDato("Sábado", fila.sabado)
                FilaDato("Domingo", fila.domingo)
            }

            SeccionTitulo("Semanas 3-6")
            ClayCard {
                FilaDato("Reque 3-6", fila.reque36)
                FilaDato("$ 3-6", fila.monto36)
                FilaDato("% 3-6", fila.porcentaje36)
                FilaDato("$ Arriba", fila.arriba)
                FilaDato("Reque a favor", fila.requeFavor)
            }

            SeccionTitulo("Cartera")
            ClayCard {
                FilaDato("CU Pase", fila.cuPase)
                FilaDato("$ Capital", fila.capital)
                FilaDato("Pérdida", fila.perdida)
            }

            SeccionTitulo("Meta")
            ClayCard {
                FilaDato("Plan", fila.planMeta)
                FilaDato("$", fila.monto)
                FilaDato("Me faltan $", fila.meFaltan)
                FilaDato("Meta diaria", fila.metaDiaria)
                FilaDato("Días op", fila.diasOp)
                FilaDato("Déficit o exceden", fila.deficit, destacado = true)
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SeccionTitulo(texto: String) {
    Text(
        texto, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        color = ClayPrimary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
    )
}

@Composable
private fun FilaDato(etiqueta: String, valor: String?, destacado: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(etiqueta, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        Text(
            valor?.takeIf { it.isNotBlank() } ?: "--",
            style = if (destacado) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = if (destacado) FontWeight.Bold else FontWeight.Normal,
            color = colorParaValor(valor)
        )
    }
}

private fun colorParaValor(valor: String?): Color {
    if (valor.isNullOrBlank()) return Color.Unspecified
    val negativo = valor.trim().startsWith("-")
    return when {
        negativo -> Color(0xFFC62828)
        valor.contains("$") || valor.contains("%") -> Color(0xFF2E7D32)
        else -> Color.Unspecified
    }
}
