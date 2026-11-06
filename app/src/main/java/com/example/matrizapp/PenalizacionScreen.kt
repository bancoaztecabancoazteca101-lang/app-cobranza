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

/** Reporte semanal "Penalización" (Tabla Velocidades) -- 100% local: Diego captura los datos
 * a mano con el botón de editar (lápiz) y la app calcula las fórmulas. No depende de Sheets
 * ni de su cuenta de Google. */
@Composable
fun PenalizacionScreen(viewModel: PenalizacionViewModel) {
    val fila by viewModel.item.collectAsState()
    var mostrarEditor by remember { mutableStateOf(false) }
    val calculo = fila.calcular()
    // Lectura por OCR de las capturas de "Resumen del Gestor" (una sola captura no trae todos los datos).
    var lectura1 by remember { mutableStateOf<LecturaVelocidad?>(null) }
    var lectura2 by remember { mutableStateOf<LecturaVelocidad?>(null) }
    var diag1 by remember { mutableStateOf("") }
    var diag2 by remember { mutableStateOf("") }
    var mensajeOcr by remember { mutableStateOf<String?>(null) }
    var revisarLectura by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Tabla de Velocidades", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = { mostrarEditor = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Editar datos")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        ClayCard {
            Text("Leer desde capturas (OCR)", fontWeight = FontWeight.Bold)
            Text(
                "App del banco > Resumen del Gestor. Una captura no trae todo: usa la parte de arriba en la 1 y la de abajo en la 2.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BotonLecturaFoto("Captura 1 (arriba)", modifier = Modifier.weight(1f),
                    onPalabras = { lectura1 = leerResumenGestor(it); diag1 = textoDiagnostico(it); mensajeOcr = null }, onError = { mensajeOcr = it })
                BotonLecturaFoto("Captura 2 (abajo)", modifier = Modifier.weight(1f),
                    onPalabras = { lectura2 = leerResumenGestor(it); diag2 = textoDiagnostico(it); mensajeOcr = null }, onError = { mensajeOcr = it })
            }
            lectura1?.let { Text(if (it.vacia) "Captura 1: no se leyó ningún dato. Texto que vio el OCR:\n$diag1" else "Captura 1 leyó: ${it.camposLeidos.joinToString(", ")}", style = MaterialTheme.typography.bodySmall) }
            lectura2?.let { Text(if (it.vacia) "Captura 2: no se leyó ningún dato. Texto que vio el OCR:\n$diag2" else "Captura 2 leyó: ${it.camposLeidos.joinToString(", ")}", style = MaterialTheme.typography.bodySmall) }
            mensajeOcr?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { revisarLectura = true }, enabled = lectura1?.vacia == false || lectura2?.vacia == false) { Text("Revisar y aplicar") }
        }
        Spacer(modifier = Modifier.height(8.dp))

        SeccionTitulo("Semana")
        ClayCard {
            FilaDato("Meta Plan", formatMoney(fila.plan100))
            FilaDato("Total Cobrado", formatMoney(fila.total), destacado = true)
            FilaDato("Avance vs plan", formatPercent(calculo.planAvance))
        }

        SeccionTitulo("Semanas 3-6")
        ClayCard {
            FilaDato("Requerido Pisado", formatMoney(fila.reque36))
            FilaDato("Requerido Cobrado", formatMoney(fila.monto36))
            FilaDato("% 3-6", formatPercent(calculo.porcentaje36))
            FilaDato("$ Arriba", formatMoney(calculo.arriba))
            FilaDato("Reque a favor", formatMoney(calculo.requeFavor))
        }

        SeccionTitulo("Cartera")
        ClayCard {
            if (fila.rk.isNotBlank()) FilaDato("RK", fila.rk)
            FilaDato("Cuentas de Pase", fila.cuPase.toString())
            FilaDato("$ Capital", formatMoney(fila.capital))
            FilaDato("Pérdida", formatMoney(calculo.perdida))
        }

        SeccionTitulo("Meta")
        ClayCard {
            FilaDato("Me faltan $", formatMoney(calculo.meFaltan))
            FilaDato("Meta diaria", formatMoney(calculo.metaDiaria))
            FilaDato("Días op", fila.diasOp.toString())
            FilaDato("Déficit o exceden", formatMoney(calculo.deficit), destacado = true)
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    if (revisarLectura) {
        RevisarLecturaVelocidadDialog(
            actual = fila,
            lectura = (lectura1 ?: LecturaVelocidad()).fusionar(lectura2),
            diagnostico = "CAPTURA 1\n$diag1\n\nCAPTURA 2\n$diag2",
            onDismiss = { revisarLectura = false },
            onAplicar = { nuevo -> viewModel.guardar(nuevo); revisarLectura = false; lectura1 = null; lectura2 = null }
        )
    }

    if (mostrarEditor) {
        EditorVelocidadDialog(
            inicial = fila,
            onDismiss = { mostrarEditor = false },
            onGuardar = { nuevo -> viewModel.guardar(nuevo); mostrarEditor = false }
        )
    }
}

@Composable
private fun EditorVelocidadDialog(inicial: VelocidadEntity, onDismiss: () -> Unit, onGuardar: (VelocidadEntity) -> Unit) {
    var rk by remember { mutableStateOf(inicial.rk) }
    var plan100 by remember { mutableStateOf(numTexto(inicial.plan100)) }
    var total by remember { mutableStateOf(numTexto(inicial.total)) }
    var reque36 by remember { mutableStateOf(numTexto(inicial.reque36)) }
    var monto36 by remember { mutableStateOf(numTexto(inicial.monto36)) }
    var cuPase by remember { mutableStateOf(inicial.cuPase.toString()) }
    var capital by remember { mutableStateOf(numTexto(inicial.capital)) }
    var diasOp by remember { mutableStateOf(inicial.diasOp.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar Tabla de Velocidades") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CampoTexto("RK", rk) { rk = it }
                CampoNumero("Meta Plan", plan100) { plan100 = it }
                CampoNumero("Total Cobrado", total) { total = it }
                CampoNumero("Requerido Pisado", reque36) { reque36 = it }
                CampoNumero("Requerido Cobrado", monto36) { monto36 = it }
                CampoNumero("Cuentas de Pase", cuPase) { cuPase = it }
                CampoNumero("$ Capital", capital) { capital = it }
                CampoNumero("Días operados", diasOp) { diasOp = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onGuardar(
                    VelocidadEntity(
                        id = 1, rk = rk,
                        plan100 = numDouble(plan100), total = numDouble(total),
                        reque36 = numDouble(reque36), monto36 = numDouble(monto36),
                        cuPase = cuPase.toIntOrNull() ?: 0, capital = numDouble(capital),
                        diasOp = diasOp.toIntOrNull()?.coerceAtLeast(1) ?: 6
                    )
                )
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun CampoTexto(etiqueta: String, valor: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = valor, onValueChange = onChange, label = { Text(etiqueta) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CampoNumero(etiqueta: String, valor: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = valor, onValueChange = onChange, label = { Text(etiqueta) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

// Texto vacío en vez de "0.0" para que el campo se vea limpio hasta que Diego capture algo;
// sin decimales cuando el valor es entero, para no obligarlo a teclear ".0" cada semana.
private fun numTexto(v: Double): String = when {
    v == 0.0 -> ""
    v == v.toLong().toDouble() -> v.toLong().toString()
    else -> v.toString()
}
private fun numDouble(s: String): Double = s.replace(",", "").trim().toDoubleOrNull() ?: 0.0

@Composable
private fun SeccionTitulo(texto: String) {
    Text(
        texto, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        color = ClayPrimary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
    )
}

@Composable
private fun FilaDato(etiqueta: String, valor: String, destacado: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(etiqueta, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        Text(
            valor,
            style = if (destacado) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = if (destacado) FontWeight.Bold else FontWeight.Normal,
            color = colorParaValor(valor)
        )
    }
}

@Composable
private fun colorParaValor(valor: String): Color {
    val negativo = valor.trim().startsWith("-")
    return when {
        negativo -> ClayRedText
        valor.contains("$") || valor.contains("%") -> ClayGreenSuccess
        else -> Color.Unspecified
    }
}

private fun formatMoney(v: Double): String {
    val signo = if (v < 0) "-" else ""
    return signo + "$" + "%,.0f".format(abs(v))
}

private fun formatPercent(v: Double): String = "%.1f".format(v * 100) + "%"
