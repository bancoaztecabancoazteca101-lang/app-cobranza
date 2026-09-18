package com.example.matrizapp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Reporte "Comisión" (submenú de Control) -- ver ComisionViewModel.kt para de dónde salen los
 * datos (100% captura manual de Diego, sin fórmula de negocio). Reproduce el diseño que Diego
 * mostró: barra de avance ($ ganado de $ meta) arriba, tabla "Al momento / Indirecta / Total"
 * por segmento de semana de atraso abajo. Mismo patrón de captura (lápiz -> AlertDialog) que
 * PenalizacionScreen.kt. */
@Composable
fun ComisionScreen(viewModel: ComisionViewModel) {
    val fila by viewModel.item.collectAsState()
    var mostrarEditor by remember { mutableStateOf(false) }
    val calculo = fila.calcular()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Comisión", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = { mostrarEditor = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Editar datos")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        ClayCard {
            Text(
                "${formatMoneyComision(calculo.totalGeneral)} de ${formatMoneyComision(fila.meta)}",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = calculo.avance.toFloat().coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth().height(10.dp),
                color = ClayPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text("Alcance del plan ${formatPercentComision(calculo.avance)}", color = Color.Gray)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("Comisión por semana de atraso", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ClayCard {
            EncabezadoComision()
            calculo.filas.forEach { f -> FilaComision(f) }
            Divider(modifier = Modifier.padding(vertical = 6.dp))
            FilaTotalComision(calculo.totalGeneral)
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    if (mostrarEditor) {
        EditorComisionDialog(
            inicial = fila,
            onDismiss = { mostrarEditor = false },
            onGuardar = { nuevo -> viewModel.guardar(nuevo); mostrarEditor = false }
        )
    }
}

@Composable
private fun EncabezadoComision() {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("Semanas", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        Text("Al momento", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        Text("Indirecta", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        Text("Total", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Color.Gray)
    }
}

@Composable
private fun FilaComision(fila: ComisionFila) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(fila.etiqueta, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(formatMoneyComision(fila.alMomento), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(formatMoneyComision(fila.indirecta), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(formatMoneyComision(fila.comision), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = ClayPrimary)
    }
}

@Composable
private fun FilaTotalComision(total: Double) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Total", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(formatMoneyComision(total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ClayPrimary)
    }
}

@Composable
private fun EditorComisionDialog(inicial: ComisionEntity, onDismiss: () -> Unit, onGuardar: (ComisionEntity) -> Unit) {
    var meta by remember { mutableStateOf(numTextoComision(inicial.meta)) }
    var momento12 by remember { mutableStateOf(numTextoComision(inicial.momento12)) }
    var indirecta12 by remember { mutableStateOf(numTextoComision(inicial.indirecta12)) }
    var momento3 by remember { mutableStateOf(numTextoComision(inicial.momento3)) }
    var indirecta3 by remember { mutableStateOf(numTextoComision(inicial.indirecta3)) }
    var momento46 by remember { mutableStateOf(numTextoComision(inicial.momento46)) }
    var indirecta46 by remember { mutableStateOf(numTextoComision(inicial.indirecta46)) }
    var momento79 by remember { mutableStateOf(numTextoComision(inicial.momento79)) }
    var indirecta79 by remember { mutableStateOf(numTextoComision(inicial.indirecta79)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar Comisión") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CampoNumeroComision("Meta del periodo", meta) { meta = it }
                Text("Semanas 1 a 2", style = MaterialTheme.typography.labelLarge, color = ClayPrimary)
                CampoNumeroComision("Al momento", momento12) { momento12 = it }
                CampoNumeroComision("Indirecta", indirecta12) { indirecta12 = it }
                Text("Semana 3", style = MaterialTheme.typography.labelLarge, color = ClayPrimary)
                CampoNumeroComision("Al momento", momento3) { momento3 = it }
                CampoNumeroComision("Indirecta", indirecta3) { indirecta3 = it }
                Text("Semanas 4 a 6", style = MaterialTheme.typography.labelLarge, color = ClayPrimary)
                CampoNumeroComision("Al momento", momento46) { momento46 = it }
                CampoNumeroComision("Indirecta", indirecta46) { indirecta46 = it }
                Text("Semanas 7 a 9", style = MaterialTheme.typography.labelLarge, color = ClayPrimary)
                CampoNumeroComision("Al momento", momento79) { momento79 = it }
                CampoNumeroComision("Indirecta", indirecta79) { indirecta79 = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onGuardar(
                    ComisionEntity(
                        id = 1,
                        momento12 = numDoubleComision(momento12), indirecta12 = numDoubleComision(indirecta12),
                        momento3 = numDoubleComision(momento3), indirecta3 = numDoubleComision(indirecta3),
                        momento46 = numDoubleComision(momento46), indirecta46 = numDoubleComision(indirecta46),
                        momento79 = numDoubleComision(momento79), indirecta79 = numDoubleComision(indirecta79),
                        meta = numDoubleComision(meta)
                    )
                )
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun CampoNumeroComision(etiqueta: String, valor: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = valor, onValueChange = onChange, label = { Text(etiqueta) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

// Texto vacío en vez de "0.0" para que el campo se vea limpio hasta que Diego capture algo;
// sin decimales cuando el valor es entero, mismo criterio que EditorVelocidadDialog.
private fun numTextoComision(v: Double): String = when {
    v == 0.0 -> ""
    v == v.toLong().toDouble() -> v.toLong().toString()
    else -> v.toString()
}
private fun numDoubleComision(s: String): Double = s.replace(",", "").trim().toDoubleOrNull() ?: 0.0

private fun formatMoneyComision(v: Double): String {
    val signo = if (v < 0) "-" else ""
    return signo + "$" + "%,.0f".format(abs(v))
}
private fun formatPercentComision(v: Double): String = "%.0f".format(v * 100) + "%"
