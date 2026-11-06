package com.example.matrizapp

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/** Lee una foto/captura con ML Kit y devuelve cada PALABRA con su caja (la interpretación está en ReporteGestorParser.kt). */
suspend fun leerPalabrasDeImagen(context: Context, uri: Uri): List<PalabraOcr> = withContext(Dispatchers.Default) {
    val image = OcrImagen.cargar(context, uri)
    val texto = OcrImagen.reconocedor.process(image).await()
    var idLinea = 0
    texto.textBlocks.flatMap { it.lines }.flatMap { linea ->
        val id = idLinea++
        linea.elements.mapNotNull { e ->
            val caja = e.boundingBox ?: return@mapNotNull null
            PalabraOcr(e.text, caja.left, caja.top, caja.right, caja.bottom, id)
        }
    }
}

/** Botón que ofrece Cámara / Galería, lee la imagen elegida con OCR y entrega las palabras detectadas. */
@Composable
fun BotonLecturaFoto(
    texto: String,
    onPalabras: (List<PalabraOcr>) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selector by remember { mutableStateOf(false) }
    var leyendo by remember { mutableStateOf(false) }
    var uriCamara by remember { mutableStateOf<Uri?>(null) }

    fun procesar(uri: Uri?) {
        if (uri == null) return
        leyendo = true
        scope.launch {
            try {
                onPalabras(leerPalabrasDeImagen(context, uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError("No se pudo leer la imagen: ${e.message ?: "error desconocido"}")
            } finally {
                leyendo = false
            }
        }
    }

    val camara = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) procesar(uriCamara) }
    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> procesar(uri) }

    OutlinedButton(onClick = { selector = true }, enabled = !leyendo, modifier = modifier) {
        if (leyendo) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) else Text(texto)
    }

    if (selector) {
        AlertDialog(
            onDismissRequest = { selector = false },
            title = { Text(texto) },
            text = { Text("Toma una foto o elige una captura de la galería. Se leerá con OCR y podrás revisar los datos antes de guardarlos.") },
            confirmButton = {
                TextButton(onClick = {
                    selector = false
                    val archivo = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "ocr_reporte_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(context, "com.example.matrizapp.fileprovider", archivo)
                    uriCamara = uri
                    camara.launch(uri)
                }) { Text("Cámara") }
            },
            dismissButton = { TextButton(onClick = { selector = false; galeria.launch("image/*") }) { Text("Galería") } }
        )
    }
}

internal fun dineroTexto(v: Double?): String = when {
    v == null -> ""
    v == v.toLong().toDouble() -> v.toLong().toString()
    else -> v.toString()
}

private fun numLeido(texto: String, actual: Double): Double = texto.replace(",", "").replace("$", "").trim().toDoubleOrNull() ?: actual

@Composable
private fun CampoLectura(etiqueta: String, valor: String, leido: Boolean, nota: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = valor, onValueChange = onChange, label = { Text(etiqueta) }, singleLine = true,
        supportingText = { Text(nota ?: if (leido) "Leído de la captura" else "No se leyó: se conserva el valor actual") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Muestra lo que leyó el OCR de "Resumen del Gestor" para confirmarlo/corregirlo ANTES de guardar. */
@Composable
fun RevisarLecturaVelocidadDialog(
    actual: VelocidadEntity,
    lectura: LecturaVelocidad,
    diagnostico: String = "",
    onDismiss: () -> Unit,
    onAplicar: (VelocidadEntity) -> Unit
) {
    var verTexto by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf(dineroTexto(lectura.plan ?: actual.plan100)) }
    var cobrado by remember { mutableStateOf(dineroTexto(lectura.cobrado ?: actual.total)) }
    var reque36 by remember { mutableStateOf(dineroTexto(lectura.reque36 ?: actual.reque36)) }
    var monto36 by remember { mutableStateOf(dineroTexto(lectura.monto36 ?: actual.monto36)) }
    var cuPase by remember { mutableStateOf((lectura.cuPase ?: actual.cuPase).toString()) }
    var capital by remember { mutableStateOf(dineroTexto(lectura.capitalPase ?: actual.capital)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Revisar lo leído") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Compara cada dato con la captura antes de guardar.", style = MaterialTheme.typography.bodySmall)
                val planN = numLeido(plan, actual.plan100)
                val cobradoN = numLeido(cobrado, actual.total)
                val alcanceCalc = if (planN > 0.0) cobradoN / planN * 100 else null
                if (lectura.alcance != null && alcanceCalc != null && kotlin.math.abs(lectura.alcance - alcanceCalc) > 1.5) {
                    Text(
                        "Ojo: el Alcance leído (${"%.1f".format(lectura.alcance)}%) no cuadra con Cobranza ÷ Plan (${"%.1f".format(alcanceCalc)}%). Revisa Meta Plan y Total Cobrado.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                    )
                }
                CampoLectura("Meta Plan", plan, lectura.plan != null) { plan = it }
                CampoLectura("Total Cobrado", cobrado, lectura.cobrado != null) { cobrado = it }
                CampoLectura("Requerido Pisado (Requerido Gestionado 3 a 6)", reque36, lectura.reque36 != null) { reque36 = it }
                CampoLectura("Requerido Cobrado (Cobranza 3 a 6)", monto36, lectura.monto36 != null) { monto36 = it }
                CampoLectura("Cuentas de Pase (Número de clientes)", cuPase, lectura.cuPase != null,
                    if (lectura.cuPaseDeducido) "Deducido: \$ Capital es 0, así que son 0 cuentas (confírmalo en la captura)" else null) { cuPase = it }
                CampoLectura("\$ Capital (Suma del saldo capital)", capital, lectura.capitalPase != null) { capital = it }
                if (diagnostico.isNotBlank()) {
                    TextButton(onClick = { verTexto = !verTexto }) { Text(if (verTexto) "Ocultar texto leído" else "Ver texto leído (diagnóstico)") }
                    if (verTexto) Text(diagnostico, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAplicar(
                    actual.copy(
                        plan100 = numLeido(plan, actual.plan100), total = numLeido(cobrado, actual.total),
                        reque36 = numLeido(reque36, actual.reque36), monto36 = numLeido(monto36, actual.monto36),
                        cuPase = numLeido(cuPase, actual.cuPase.toDouble()).toInt(), capital = numLeido(capital, actual.capital)
                    )
                )
            }) { Text("Aplicar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** Muestra lo que leyó el OCR de "Cobranza del gestor" (Al momento / Indirecta por segmento) para confirmarlo. */
@Composable
fun RevisarLecturaComisionDialog(
    actual: ComisionEntity,
    lectura: LecturaComision,
    diagnostico: String = "",
    onDismiss: () -> Unit,
    onAplicar: (ComisionEntity) -> Unit
) {
    var verTexto by remember { mutableStateOf(false) }
    val etiquetas = listOf("1 a 2", "3", "4 a 6", "7 a 9")
    val momentoActual = listOf(actual.momento12, actual.momento3, actual.momento46, actual.momento79)
    val indirectaActual = listOf(actual.indirecta12, actual.indirecta3, actual.indirecta46, actual.indirecta79)
    val momento = remember { mutableStateListOfTexto(4) { dineroTexto(lectura.momento[it] ?: momentoActual[it]) } }
    val indirecta = remember { mutableStateListOfTexto(4) { dineroTexto(lectura.indirecta[it] ?: indirectaActual[it]) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Revisar lo leído") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Compara cada dato con la captura antes de guardar.", style = MaterialTheme.typography.bodySmall)
                etiquetas.forEachIndexed { i, et ->
                    Text("Semanas $et", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            CampoLectura("Al momento", momento[i], lectura.momento[i] != null,
                                if (lectura.momentoDeducido[i]) "Deducido: Total − Indirecta" else null) { momento[i] = it }
                        }
                        Column(Modifier.weight(1f)) {
                            CampoLectura("Indirecta", indirecta[i], lectura.indirecta[i] != null,
                                if (lectura.indirectaDeducida[i]) "Deducido: Total − Al momento" else null) { indirecta[i] = it }
                        }
                    }
                    // Total de la captura = Al momento + Indirecta: si no cuadra, hay un dato mal leído en esa fila.
                    val totalCaptura = lectura.total[i]
                    if (totalCaptura != null && kotlin.math.abs(numLeido(momento[i], 0.0) + numLeido(indirecta[i], 0.0) - totalCaptura) > 1.0) {
                        Text("Ojo: Al momento + Indirecta no suma el Total de la captura (${dineroTexto(totalCaptura)}). Revisa esta fila.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (diagnostico.isNotBlank()) {
                    TextButton(onClick = { verTexto = !verTexto }) { Text(if (verTexto) "Ocultar texto leído" else "Ver texto leído (diagnóstico)") }
                    if (verTexto) Text(diagnostico, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                fun m(i: Int) = numLeido(momento[i], momentoActual[i])
                fun d(i: Int) = numLeido(indirecta[i], indirectaActual[i])
                onAplicar(
                    actual.copy(
                        momento12 = m(0), indirecta12 = d(0), momento3 = m(1), indirecta3 = d(1),
                        momento46 = m(2), indirecta46 = d(2), momento79 = m(3), indirecta79 = d(3)
                    )
                )
            }) { Text("Aplicar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

private fun mutableStateListOfTexto(n: Int, inicial: (Int) -> String) =
    androidx.compose.runtime.mutableStateListOf<String>().apply { repeat(n) { add(inicial(it)) } }
