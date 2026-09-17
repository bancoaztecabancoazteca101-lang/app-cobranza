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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Penalización", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = { mostrarEditor = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Editar datos")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        SeccionTitulo("Semana")
        ClayCard {
            FilaDato("Plan 100%", formatMoney(fila.plan100))
            FilaDato("Lunes", formatMoney(fila.lunes))
            FilaDato("Martes", formatMoney(fila.martes))
            FilaDato("Miércoles", formatMoney(fila.miercoles))
            FilaDato("Jueves", formatMoney(fila.jueves))
            FilaDato("Viernes", formatMoney(fila.viernes))
            FilaDato("Sábado", formatMoney(fila.sabado))
            FilaDato("Domingo", formatMoney(fila.domingo))
            FilaDato("Total", formatMoney(calculo.total), destacado = true)
            FilaDato("Avance vs plan", formatPercent(calculo.planAvance))
        }

        SeccionTitulo("Semanas 3-6")
        ClayCard {
            FilaDato("Reque 3-6", formatMoney(fila.reque36))
            FilaDato("$ 3-6", formatMoney(fila.monto36))
            FilaDato("% 3-6", formatPercent(calculo.porcentaje36))
            FilaDato("$ Arriba", formatMoney(calculo.arriba))
            FilaDato("Reque a favor", formatMoney(calculo.requeFavor))
        }

        SeccionTitulo("Cartera")
        ClayCard {
            if (fila.rk.isNotBlank()) FilaDato("RK", fila.rk)
            FilaDato("CU Pase", fila.cuPase.toString())
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
    var lunes by remember { mutableStateOf(numTexto(inicial.lunes)) }
    var martes by remember { mutableStateOf(numTexto(inicial.martes)) }
    var miercoles by remember { mutableStateOf(numTexto(inicial.miercoles)) }
    var jueves by remember { mutableStateOf(numTexto(inicial.jueves)) }
    var viernes by remember { mutableStateOf(numTexto(inicial.viernes)) }
    var sabado by remember { mutableStateOf(numTexto(inicial.sabado)) }
    var domingo by remember { mutableStateOf(numTexto(inicial.domingo)) }
    var reque36 by remember { mutableStateOf(numTexto(inicial.reque36)) }
    var monto36 by remember { mutableStateOf(numTexto(inicial.monto36)) }
    var cuPase by remember { mutableStateOf(inicial.cuPase.toString()) }
    var capital by remember { mutableStateOf(numTexto(inicial.capital)) }
    var diasOp by remember { mutableStateOf(inicial.diasOp.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar Penalización") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CampoTexto("RK", rk) { rk = it }
                CampoNumero("Plan 100% (meta semanal)", plan100) { plan100 = it }
                CampoNumero("Lunes", lunes) { lunes = it }
                CampoNumero("Martes", martes) { martes = it }
                CampoNumero("Miércoles", miercoles) { miercoles = it }
                CampoNumero("Jueves", jueves) { jueves = it }
                CampoNumero("Viernes", viernes) { viernes = it }
                CampoNumero("Sábado", sabado) { sabado = it }
                CampoNumero("Domingo", domingo) { domingo = it }
                CampoNumero("Reque 3-6", reque36) { reque36 = it }
                CampoNumero("$ 3-6", monto36) { monto36 = it }
                CampoNumero("CU Pase", cuPase) { cuPase = it }
                CampoNumero("$ Capital", capital) { capital = it }
                CampoNumero("Días operados", diasOp) { diasOp = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onGuardar(
                    VelocidadEntity(
                        id = 1, rk = rk,
                        plan100 = numDouble(plan100), lunes = numDouble(lunes), martes = numDouble(martes),
                        miercoles = numDouble(miercoles), jueves = numDouble(jueves), viernes = numDouble(viernes),
                        sabado = numDouble(sabado), domingo = numDouble(domingo),
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

private fun colorParaValor(valor: String): Color {
    val negativo = valor.trim().startsWith("-")
    return when {
        negativo -> Color(0xFFC62828)
        valor.contains("$") || valor.contains("%") -> Color(0xFF2E7D32)
        else -> Color.Unspecified
    }
}

private fun formatMoney(v: Double): String {
    val signo = if (v < 0) "-" else ""
    return signo + "$" + "%,.0f".format(abs(v))
}

private fun formatPercent(v: Double): String = "%.1f".format(v * 100) + "%"
