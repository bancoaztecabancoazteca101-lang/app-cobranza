package com.example.matrizapp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** "Avance" (submenú de Control) -- antes vivía mezclado directo en ControlScreen.kt; ahora es
 * su propia pantalla dentro del submenú (Penalización / Comisión / Avance), igual que las otras
 * dos. Mismo ControlViewModel y mismos datos de siempre: "Requerido por día" y "Requerido
 * semana actual", calculados 100% local desde Matriz/Room (ver ControlViewModel.kt). */
@Composable
fun AvanceScreen(viewModel: ControlViewModel) {
    val itemsHoy by viewModel.itemsHoy.collectAsState()
    val itemsSemanaActual by viewModel.itemsSemanaActual.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (itemsHoy.isEmpty() && itemsSemanaActual.isEmpty()) {
            Text("Sin datos", color = Color.Gray)
        } else {
            Text("Requerido por día", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (itemsHoy.isEmpty()) {
                Text("Sin datos", color = Color.Gray)
            } else {
                itemsHoy.forEach { row -> AvanceFilaCard(row) }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("Requerido semana actual", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (itemsSemanaActual.isEmpty()) {
                Text("Sin datos", color = Color.Gray)
            } else {
                itemsSemanaActual.forEach { row -> AvanceFilaCard(row) }
            }
        }
    }
}

@Composable
private fun AvanceFilaCard(row: ControlEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(row.semana, style = MaterialTheme.typography.bodyLarge)
            Text(
                formatCurrencyAvance(row.requerido),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (row.semana.startsWith("Total", ignoreCase = true)) MaterialTheme.colorScheme.primary else Color.Unspecified
            )
        }
    }
}

private fun formatCurrencyAvance(raw: String): String {
    val clean = raw.replace(",", "").replace("$", "").trim()
    val num = clean.toDoubleOrNull() ?: return raw
    return "$" + "%,.2f".format(num)
}
