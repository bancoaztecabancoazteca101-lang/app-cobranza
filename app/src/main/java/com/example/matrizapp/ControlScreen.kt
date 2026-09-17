package com.example.matrizapp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ControlScreen(viewModel: ControlViewModel, onNavigateToPenalizacion: () -> Unit = {}) {
    val itemsHoy by viewModel.itemsHoy.collectAsState()
    val itemsSemanaActual by viewModel.itemsSemanaActual.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Submenú de Control: reporte personal de "Tabla Velocidades" (solo la fila de Diego),
        // vive en su propia pantalla para no mezclar la vista de cartera diaria/semanal de aquí
        // arriba con ese reporte de cumplimiento.
        Card(modifier = Modifier.fillMaxWidth(), onClick = onNavigateToPenalizacion) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Penalización", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        }

        if (itemsHoy.isEmpty() && itemsSemanaActual.isEmpty()) {
            Text("Sin datos", color = Color.Gray)
            return@Column
        }

        Text("Requerido por día", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (itemsHoy.isEmpty()) {
            Text("Sin datos", color = Color.Gray)
        } else {
            itemsHoy.forEach { row -> ControlFilaCard(row) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Misma estructura que la tabla de arriba, pero sumando todos los registros de la
        // semana actual (lunes-domingo) en vez de solo hoy. Ambas se calculan local desde
        // Matriz (Room) y excluyen status "PASE" -- ya no dependen de ninguna hoja de Sheets.
        Text("Requerido semana actual", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (itemsSemanaActual.isEmpty()) {
            Text("Sin datos", color = Color.Gray)
        } else {
            itemsSemanaActual.forEach { row -> ControlFilaCard(row) }
        }
    }
}

@Composable
private fun ControlFilaCard(row: ControlEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(row.semana, style = MaterialTheme.typography.bodyLarge)
            Text(
                formatCurrency(row.requerido),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (row.semana.startsWith("Total", ignoreCase = true)) MaterialTheme.colorScheme.primary else Color.Unspecified
            )
        }
    }
}

private fun formatCurrency(raw: String): String {
    val clean = raw.replace(",", "").replace("$", "").trim()
    val num = clean.toDoubleOrNull() ?: return raw
    return "$" + "%,.2f".format(num)
}
