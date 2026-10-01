package com.example.matrizapp
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Un renglón de la tabla fotografiada (CU, NAME, ..., CAPITAL): su texto completo y el capital leído. */
data class FilaCapitalFoto(val cuClave: String?, val texto: String, val capital: Long)

/** Un cambio propuesto: el capital que trae la foto para un registro de Semana 6. */
data class CambioCapital(val item: Sem6Item, val nuevoCapital: String, val capitalActual: String, val porCu: Boolean) {
    val cambia: Boolean get() = capitalActual.replace(Regex("[^0-9.]"), "").toDoubleOrNull()?.toLong() != nuevoCapital.toLongOrNull()
}

private val patronCuTabla = Regex("""\b\d{1,2}\s?[-_]\s?\d{1,2}\s?[-_]\s?\d{1,5}\s?[-_]\s?\d{3,8}\b""")
private val patronMonto = Regex("""\$\s?(\d{1,3}(?:,\d{3})+|\d+)""")

/** "01-01-06142-24483" y "1-1-6142-24483" (o con guiones bajos) dan la misma clave. */
fun claveCu(cu: String): String =
    cu.split(Regex("[-_\\s]+")).filter { it.isNotBlank() }.joinToString("-") { it.trimStart('0').ifEmpty { "0" } }

/** Lee con OCR una foto/captura de la tabla de cartera y regresa, por renglón, su CU y el monto de la
 * columna más a la derecha (CAPITAL). Los renglones se arman por altura (Y) de cada línea que detecta
 * ML Kit, porque el OCR no entiende tablas. */
suspend fun leerCapitalesDeFoto(context: Context, uri: Uri): List<FilaCapitalFoto> = suspendCancellableCoroutine { cont ->
    try {
        // La letra de una tabla fotografiada es chica: se escala x2 (máx. 4000 px) para que el OCR la lea mejor.
        val original = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        if (original == null) { cont.resume(emptyList()); return@suspendCancellableCoroutine }
        val escala = minOf(2f, 4000f / maxOf(original.width, original.height)).coerceAtLeast(1f)
        val bmp: Bitmap = if (escala > 1.1f) Bitmap.createScaledBitmap(original, (original.width * escala).toInt(), (original.height * escala).toInt(), true) else original
        val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { vt ->
                val lineas = vt.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
                if (lineas.isEmpty()) { cont.resume(emptyList()); return@addOnSuccessListener }
                val alturas = lineas.map { it.boundingBox!!.height() }.sorted()
                val tol = (alturas[alturas.size / 2] * 0.6f).coerceAtLeast(4f)
                // Agrupa por centro vertical.
                val ordenadas = lineas.sortedBy { it.boundingBox!!.centerY() }
                val renglones = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Line>>()
                for (l in ordenadas) {
                    val ultimo = renglones.lastOrNull()
                    if (ultimo != null && kotlin.math.abs(l.boundingBox!!.centerY() - ultimo.map { it.boundingBox!!.centerY() }.average()) <= tol) ultimo.add(l)
                    else renglones.add(mutableListOf(l))
                }
                // Borde derecho de la columna de montos: el capital debe estar pegado a él.
                val derechoMax = lineas.filter { it.text.contains('$') }.maxOfOrNull { it.boundingBox!!.right } ?: 0
                val filas = renglones.mapNotNull { r ->
                    val ord = r.sortedBy { it.boundingBox!!.left }
                    val texto = ord.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ")
                    val ultimaConMonto = ord.lastOrNull { patronMonto.containsMatchIn(it.text) } ?: return@mapNotNull null
                    if (derechoMax > 0 && ultimaConMonto.boundingBox!!.right < derechoMax * 0.85f) return@mapNotNull null // no se leyó la columna CAPITAL
                    val monto = patronMonto.findAll(ultimaConMonto.text).last().groupValues[1].replace(",", "").toLongOrNull() ?: return@mapNotNull null
                    val cu = patronCuTabla.find(texto)?.value?.let { claveCu(it.replace(" ", "")) }
                    FilaCapitalFoto(cu, texto, monto)
                }
                cont.resume(filas)
            }
            .addOnFailureListener { cont.resume(emptyList()) }
    } catch (e: Exception) {
        cont.resume(emptyList())
    }
}

/** Empata cada registro de Semana 6 con su renglón de la foto: primero por CU y, si no, por nombre
 * (todas las palabras del nombre del registro deben aparecer en el renglón). */
fun emparejarCapitales(items: List<Sem6Item>, filas: List<FilaCapitalFoto>): List<CambioCapital> =
    items.mapNotNull { item ->
        val clave = claveCu(item.cu)
        val porCu = if (clave.isNotBlank()) filas.firstOrNull { it.cuClave == clave } else null
        if (porCu != null) return@mapNotNull CambioCapital(item, porCu.capital.toString(), item.capital, true)
        val tokens = tokensNombre(item.nombre)
        if (tokens.size < 2) return@mapNotNull null
        val porNombre = filas.filter { f ->
            val tf = tokensNombre(f.texto)
            tokens.all { t -> tf.any { x -> tokenParecido(t, x) } }
        }
        if (porNombre.size == 1) CambioCapital(item, porNombre[0].capital.toString(), item.capital, false) else null
    }
