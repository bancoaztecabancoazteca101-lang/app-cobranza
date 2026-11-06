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

/** Reporte "Bolsa Gerencia" (4to submenú de Control) -- ver BolsaGerenciaViewModel.kt para de
 * dónde salen los datos (100% captura manual de Diego, sin extracción automática) y las
 * fórmulas. Mismo patrón de captura (lápiz -> AlertDialog) que ComisionScreen.kt. Muestra un
 * aviso cuando el Cumplimiento del plan no llega al 85%: la comisión se calcula en $0 aunque el
 * resto de los datos esté capturado. */
@Composable
fun BolsaGerenciaScreen(viewModel: BolsaGerenciaViewModel, totalCobradoVelocidades: Double = 0.0) {
    val fila by viewModel.item.collectAsState()
    var mostrarEditor by remember { mutableStateOf(false) }
    // Cobranza 1 a 9 Gestor = Total Cobrado de la Tabla de Velocidades (se toma de ahí, no se captura).
    val filaEf = if (totalCobradoVelocidades > 0.0) fila.copy(cobranza1a9Gestor = totalCobradoVelocidades) else fila
    val calculo = filaEf.calcular()
    // Lectura por OCR de la captura "Gerencia ... Plan de cobranza 1 a 9" de la app del banco.
    var lecturaBolsa by remember { mutableStateOf<LecturaVelocidad?>(null) }
    var diagBolsa by remember { mutableStateOf("") }
    var mensajeOcr by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Bolsa Gerencial", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = { mostrarEditor = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Editar datos")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        ClayCard {
            Text("Leer desde captura (OCR)", fontWeight = FontWeight.Bold)
            Text(
                "App del banco > Gerencia > Acumulado (tarjeta Plan de cobranza 1 a 9 semanas, con Cobranza y Plan).",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            BotonLecturaFoto("Leer captura", modifier = Modifier.fillMaxWidth(),
                onPalabras = {
                    val l = leerResumenGestor(it)
                    if (l.cobrado == null && l.plan == null) mensajeOcr = "No se pudo leer Cobranza y Plan (deben verse las dos cajas). Texto que vio el OCR:\n${textoDiagnostico(it)}"
                    else { lecturaBolsa = l; diagBolsa = textoDiagnostico(it); mensajeOcr = null }
                },
                onError = { mensajeOcr = it })
            mensajeOcr?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        Spacer(modifier = Modifier.height(8.dp))

        ClayCard {
            Text(
                formatMoneyBolsaGerencia(calculo.comision),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = if (calculo.comisionLiberada) ClayPrimary else Color.Gray
            )
            Text("Comisión", color = Color.Gray)
            if (!calculo.comisionLiberada) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Comisión no liberada: el Cumplimiento del plan debe ser de 85% o más",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        ClayCard {
            FilaDatoBolsaGerencia("Cobranza Gerencia", formatMoneyBolsaGerencia(fila.cobranzaGerencia))
            FilaDatoBolsaGerencia("Cumplimiento del plan", formatPercentBolsaGerencia(fila.cumplimientoPlan))
            FilaDatoBolsaGerencia("Monto Base", formatMoneyBolsaGerencia(fila.montoBase))
            FilaDatoBolsaGerencia("Número de Gestores", fila.numeroGestores.toString())
            Divider(modifier = Modifier.padding(vertical = 6.dp))
            FilaDatoBolsaGerencia("Bolsa de la Gerencia", formatMoneyBolsaGerencia(calculo.bolsaGerencia))
            FilaDatoBolsaGerencia("Bolsa a Repartir", formatMoneyBolsaGerencia(calculo.bolsaARepartir))
            Divider(modifier = Modifier.padding(vertical = 6.dp))
            FilaDatoBolsaGerencia("Cobranza 1 a 9 Gestor", formatMoneyBolsaGerencia(filaEf.cobranza1a9Gestor))
            FilaDatoBolsaGerencia("Cobranza 1 a 9 Gerencia", formatMoneyBolsaGerencia(fila.cobranza1a9Gerencia))
            FilaDatoBolsaGerencia("% Cumplimiento 1 a 9", formatPercentBolsaGerencia(calculo.porcentajeCumplimiento1a9))
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    lecturaBolsa?.let { l ->
        RevisarLecturaBolsaDialog(
            actual = fila,
            lectura = l,
            totalVelocidades = totalCobradoVelocidades,
            diagnostico = diagBolsa,
            onDismiss = { lecturaBolsa = null },
            onAplicar = { nuevo -> viewModel.guardar(nuevo); lecturaBolsa = null }
        )
    }

    if (mostrarEditor) {
        EditorBolsaGerenciaDialog(
            inicial = fila,
            totalVelocidades = totalCobradoVelocidades,
            onDismiss = { mostrarEditor = false },
            onGuardar = { nuevo -> viewModel.guardar(nuevo); mostrarEditor = false }
        )
    }
}

@Composable
private fun FilaDatoBolsaGerencia(etiqueta: String, valor: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(etiqueta, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        Text(valor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EditorBolsaGerenciaDialog(inicial: BolsaGerenciaEntity, totalVelocidades: Double, onDismiss: () -> Unit, onGuardar: (BolsaGerenciaEntity) -> Unit) {
    var cobranzaGerencia by remember { mutableStateOf(numTextoBolsaGerencia(inicial.cobranzaGerencia)) }
    var cumplimientoPlan by remember { mutableStateOf(if (inicial.cumplimientoPlan == 0.0) "" else (inicial.cumplimientoPlan * 100).let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }) }
    var montoBase by remember { mutableStateOf(numTextoBolsaGerencia(inicial.montoBase)) }
    var numeroGestores by remember { mutableStateOf(if (inicial.numeroGestores == 0) "" else inicial.numeroGestores.toString()) }
    var cobranza1a9Gestor by remember { mutableStateOf(numTextoBolsaGerencia(inicial.cobranza1a9Gestor)) }
    var cobranza1a9Gerencia by remember { mutableStateOf(numTextoBolsaGerencia(inicial.cobranza1a9Gerencia)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar Bolsa Gerencial") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CampoNumeroBolsaGerencia("Cobranza Gerencia", cobranzaGerencia) { cobranzaGerencia = it }
                CampoNumeroBolsaGerencia("Cumplimiento del plan (%)", cumplimientoPlan) { cumplimientoPlan = it }
                CampoNumeroBolsaGerencia("Monto Base", montoBase) { montoBase = it }
                CampoNumeroBolsaGerencia("Número de Gestores", numeroGestores) { numeroGestores = it }
                if (totalVelocidades > 0.0) {
                    Text("Cobranza 1 a 9 Gestor: ${formatMoneyBolsaGerencia(totalVelocidades)} (se toma de Tabla de Velocidades > Total Cobrado)", style = MaterialTheme.typography.bodySmall)
                } else {
                    CampoNumeroBolsaGerencia("Cobranza 1 a 9 Gestor", cobranza1a9Gestor) { cobranza1a9Gestor = it }
                }
                CampoNumeroBolsaGerencia("Cobranza 1 a 9 Gerencia", cobranza1a9Gerencia) { cobranza1a9Gerencia = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onGuardar(
                    BolsaGerenciaEntity(
                        id = 1,
                        cobranzaGerencia = numDoubleBolsaGerencia(cobranzaGerencia),
                        cumplimientoPlan = numDoubleBolsaGerencia(cumplimientoPlan) / 100.0,
                        montoBase = numDoubleBolsaGerencia(montoBase),
                        numeroGestores = numeroGestores.trim().toIntOrNull() ?: 0,
                        cobranza1a9Gestor = numDoubleBolsaGerencia(cobranza1a9Gestor),
                        cobranza1a9Gerencia = numDoubleBolsaGerencia(cobranza1a9Gerencia)
                    )
                )
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun CampoNumeroBolsaGerencia(etiqueta: String, valor: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = valor, onValueChange = onChange, label = { Text(etiqueta) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

// Texto vacío en vez de "0.0" para que el campo se vea limpio hasta que Diego capture algo;
// mismo criterio que numTextoComision en ComisionScreen.kt.
private fun numTextoBolsaGerencia(v: Double): String = when {
    v == 0.0 -> ""
    v == v.toLong().toDouble() -> v.toLong().toString()
    else -> v.toString()
}
private fun numDoubleBolsaGerencia(s: String): Double = s.replace(",", "").trim().toDoubleOrNull() ?: 0.0

private fun formatMoneyBolsaGerencia(v: Double): String {
    val signo = if (v < 0) "-" else ""
    return signo + "$" + "%,.0f".format(abs(v))
}
private fun formatPercentBolsaGerencia(v: Double): String = "%.2f".format(v * 100) + "%"
