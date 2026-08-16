package com.example.matrizapp
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ControlScreen(viewModel: ControlViewModel) {
    val items by viewModel.items.collectAsState()
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Sin datos", color = Color.Gray) }
        return
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Requerido por semana", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        items.forEach { row ->
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
    }
}

private fun formatCurrency(raw: String): String {
    val clean = raw.replace(",", "").replace("$", "").trim()
    val num = clean.toDoubleOrNull() ?: return raw
    return "$" + "%,.2f".format(num)
}
