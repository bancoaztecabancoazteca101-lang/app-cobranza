package com.example.matrizapp

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.Normalizer
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max

data class PaseFotoFila(
    val cu: String,
    val nombre: String,
    val gcr: String,
    val contiene: String?,
    val capitales: String?
)

private data class CeldaOcr(
    val texto: String,
    val bounds: Rect
) {
    val centroX: Float get() = bounds.centerX().toFloat()
    val centroY: Float get() = bounds.centerY().toFloat()
    val alto: Float get() = bounds.height().toFloat()
}

private fun limpiarOcr(texto: String): String =
    Normalizer.normalize(texto, Normalizer.Form.NFC)
        .replace("\u00A0", " ")
        .replace("|", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun normalizarTexto(valor: String): String =
    Normalizer.normalize(valor, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .uppercase()
        .replace(Regex("[^A-Z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun compactarTexto(valor: String): String =
    normalizarTexto(valor).replace(" ", "")

private fun esFlores(valor: String): Boolean {
    val texto = compactarTexto(valor)
    return texto == "FLORES" || texto == "FL0RES" || texto == "FLORES0" ||
        texto.matches(Regex("FL0?RES"))
}

private fun puntoMedio(a: Float, b: Float): Float = (a + b) / 2f

private val patronCu =
    Regex("(?<!\\d)\\d{1,2}-\\d{1,2}-\\d{3,6}-\\d{3,6}(?!\\d)")

private val patronImporte =
    Regex("\\$?\\s*[0-9OIL]{1,3}(?:[,.][0-9OIL]{3})*(?:[,.][0-9OIL]{1,2})?")

private enum class ColumnaObjetivo {
    CU, NOMBRE, PLAN, DIAS, DIA, SALDO, MORA, REQUE, GCR, CONTIENE, CAPITALES
}

private data class CabeceraDetectada(
    val columna: ColumnaObjetivo,
    val x: Float,
    val y: Float
)

private data class RangoX(
    val left: Float,
    val right: Float
)

private data class LayoutColumnas(
    val yCabecera: Float,
    val toleranciaCabecera: Float,
    val rangos: Map<ColumnaObjetivo, RangoX>
) {
    fun rango(columna: ColumnaObjetivo): RangoX? = rangos[columna]
}

/**
 * Reconoce el encabezado de una columna aunque el OCR cometa errores muy comunes.
 * La posición real de la columna se obtiene de la fotografía, nunca de coordenadas fijas.
 */
private fun detectarColumna(textoOriginal: String): ColumnaObjetivo? {
    val t = compactarTexto(textoOriginal)
    return when {
        t == "CU" -> ColumnaObjetivo.CU
        t == "NOMBRE" || t == "N0MBRE" || t == "NOMBRE1" -> ColumnaObjetivo.NOMBRE
        t == "PLAN" -> ColumnaObjetivo.PLAN
        t == "DIAS" || t == "D1AS" -> ColumnaObjetivo.DIAS
        t == "DIA" || t == "D1A" -> ColumnaObjetivo.DIA
        t == "SALDO" || t == "5ALDO" -> ColumnaObjetivo.SALDO
        t == "MORA" -> ColumnaObjetivo.MORA
        t == "REQUE" || t == "REQUERIMIENTO" || t == "REQUER" -> ColumnaObjetivo.REQUE
        t == "GCR" || t == "GCR1" -> ColumnaObjetivo.GCR
        t == "CONTIENE" || t == "CONTIEN" || t == "CONT1ENE" ||
            t == "CONT1EN" || t == "CONTENE" -> ColumnaObjetivo.CONTIENE
        t == "CAPITALES" || t == "CAPITAL" || t == "CAP1TALES" ||
            t == "CAP1TAL" -> ColumnaObjetivo.CAPITALES
        else -> null
    }
}

/**
 * Detecta el orden real de las columnas de ESTA fotografía.
 * Cada columna queda limitada por el punto medio entre sus cabeceras vecinas.
 */
private fun detectarLayoutColumnas(
    celdas: List<CeldaOcr>,
    lineas: List<Text.Line>
): LayoutColumnas? {
    val encabezadoGcr = celdas
        .filter { detectarColumna(it.texto) == ColumnaObjetivo.GCR }
        .minByOrNull { it.bounds.top }
        ?: return null

    val yCabecera = encabezadoGcr.centroY
    val toleranciaCabecera = max(24f, encabezadoGcr.alto * 1.8f)
    val cabeceras = mutableMapOf<ColumnaObjetivo, CabeceraDetectada>()

    fun registrar(texto: String, x: Float, y: Float) {
        val columna = detectarColumna(texto) ?: return
        if (abs(y - yCabecera) > toleranciaCabecera) return
        val actual = cabeceras[columna]
        if (actual == null || abs(y - yCabecera) < abs(actual.y - yCabecera)) {
            cabeceras[columna] = CabeceraDetectada(columna, x, y)
        }
    }

    // Primera fuente: la línea de OCR que contiene el encabezado GCR.
    lineas.firstOrNull { line ->
        line.elements.any { element ->
            val box = element.boundingBox
            box != null &&
                detectarColumna(element.text) == ColumnaObjetivo.GCR &&
                abs(box.centerY() - yCabecera) <= toleranciaCabecera
        }
    }?.elements?.forEach { element ->
        val box = element.boundingBox ?: return@forEach
        registrar(element.text, box.centerX().toFloat(), box.centerY().toFloat())
    }

    // Segunda fuente: cualquier celda físicamente ubicada en la banda de encabezados.
    celdas.filter { abs(it.centroY - yCabecera) <= toleranciaCabecera }
        .forEach { registrar(it.texto, it.centroX, it.centroY) }

    if (!cabeceras.containsKey(ColumnaObjetivo.GCR)) return null

    val ordenadas = cabeceras.values.sortedBy { it.x }
    val rangos = mutableMapOf<ColumnaObjetivo, RangoX>()

    ordenadas.forEachIndexed { index, actual ->
        val anterior = ordenadas.getOrNull(index - 1)
        val siguiente = ordenadas.getOrNull(index + 1)

        val distanciaIzquierda = anterior?.let { actual.x - it.x }
        val distanciaDerecha = siguiente?.let { it.x - actual.x }
        val anchoBorde = max(60f, max(distanciaIzquierda ?: 0f, distanciaDerecha ?: 0f))

        val left = anterior?.let { puntoMedio(it.x, actual.x) }
            ?: actual.x - anchoBorde / 2f
        val right = siguiente?.let { puntoMedio(actual.x, it.x) }
            ?: actual.x + anchoBorde / 2f

        rangos[actual.columna] = RangoX(left, right)
    }

    return LayoutColumnas(yCabecera, toleranciaCabecera, rangos)
}

private fun textoEnColumna(
    celdas: List<CeldaOcr>,
    rango: RangoX?,
    filaTop: Float,
    filaBottom: Float
): List<CeldaOcr> {
    if (rango == null) return emptyList()
    return celdas.filter {
        it.centroY >= filaTop &&
            it.centroY < filaBottom &&
            it.centroX >= rango.left &&
            it.centroX < rango.right
    }.sortedWith(compareBy<CeldaOcr> { it.centroY }.thenBy { it.centroX })
}

private fun textoColumna(
    celdas: List<CeldaOcr>,
    rango: RangoX?,
    filaTop: Float,
    filaBottom: Float
): String =
    textoEnColumna(celdas, rango, filaTop, filaBottom)
        .joinToString(" ") { it.texto }
        .trim()

private fun extraerCu(texto: String): String =
    patronCu.find(texto)?.value.orEmpty()

private fun limpiarImporteOcr(texto: String): String {
    val limpio = texto
        .replace("O", "0")
        .replace("I", "1")
        .replace("L", "1")
        .replace(" ", "")

    val match = patronImporte.find(limpio) ?: return texto.trim()
    val valor = match.value.replace(" ", "")
    return if (valor.startsWith("$")) valor else "$$valor"
}

private fun extraerImporte(
    celdas: List<CeldaOcr>,
    rango: RangoX?,
    filaTop: Float,
    filaBottom: Float
): String? {
    val texto = textoColumna(celdas, rango, filaTop, filaBottom)
    if (texto.isBlank()) return null

    val candidatos = patronImporte.findAll(texto).map { it.value }.toList()
    return if (candidatos.isNotEmpty()) {
        limpiarImporteOcr(candidatos.joinToString(""))
    } else {
        limpiarImporteOcr(texto).takeIf { it.isNotBlank() }
    }
}

private fun celdasDeImagen(visionText: Text): List<CeldaOcr> =
    visionText.textBlocks.flatMap { block ->
        block.lines.flatMap { line ->
            line.elements.mapNotNull { element ->
                val bounds = element.boundingBox ?: return@mapNotNull null
                val texto = limpiarOcr(element.text)
                if (texto.isBlank()) null else CeldaOcr(texto, bounds)
            }
        }
    }

/**
 * Las filas siempre se anclan al CU. El límite entre dos filas es el punto medio
 * entre sus CU, por lo que una fila nunca se traga automáticamente a la siguiente.
 */
private fun limitesPorAnclas(anclas: List<CeldaOcr>): List<Pair<Float, Float>> =
    anclas.mapIndexed { index, ancla ->
        val anterior = anclas.getOrNull(index - 1)?.centroY
        val siguiente = anclas.getOrNull(index + 1)?.centroY

        val top = if (anterior != null) {
            puntoMedio(anterior, ancla.centroY)
        } else {
            ancla.centroY - max(22f, ancla.alto * 1.8f)
        }

        val bottom = if (siguiente != null) {
            puntoMedio(ancla.centroY, siguiente)
        } else {
            ancla.centroY + max(22f, ancla.alto * 1.8f)
        }

        top to bottom
    }

private fun construirFila(
    celdas: List<CeldaOcr>,
    layout: LayoutColumnas,
    filaTop: Float,
    filaBottom: Float,
    gcr: String
): PaseFotoFila {
    val textoCu = textoColumna(
        celdas,
        layout.rango(ColumnaObjetivo.CU),
        filaTop,
        filaBottom
    )
    val cu = extraerCu(textoCu).ifBlank { textoCu }

    val nombre = textoColumna(
        celdas,
        layout.rango(ColumnaObjetivo.NOMBRE),
        filaTop,
        filaBottom
    )
        .replace(Regex("\\s+"), " ")
        .trim()

    val contiene = extraerImporte(
        celdas,
        layout.rango(ColumnaObjetivo.CONTIENE),
        filaTop,
        filaBottom
    )
    val capitales = extraerImporte(
        celdas,
        layout.rango(ColumnaObjetivo.CAPITALES),
        filaTop,
        filaBottom
    )

    return PaseFotoFila(cu, nombre, gcr, contiene, capitales)
}

/** Detecta exclusivamente las filas cuyo GCR es FLORES. */
fun parsearFilasPaseFoto(visionText: Text): List<PaseFotoFila> {
    val lineas = visionText.textBlocks.flatMap { it.lines }
    if (lineas.isEmpty()) return emptyList()

    val celdas = celdasDeImagen(visionText)
    if (celdas.isEmpty()) return emptyList()

    val layout = detectarLayoutColumnas(celdas, lineas) ?: return emptyList()
    val rangoGcr = layout.rango(ColumnaObjetivo.GCR) ?: return emptyList()

    val flores = celdas.filter { celda ->
        celda.centroY > layout.yCabecera + layout.toleranciaCabecera &&
            celda.centroX >= rangoGcr.left &&
            celda.centroX < rangoGcr.right &&
            esFlores(celda.texto)
    }.sortedBy { it.centroY }

    if (flores.isEmpty()) return emptyList()

    val limitesY = limitesPorAnclas(flores)

    return flores.mapIndexed { index, flor ->
        val (filaTop, filaBottom) = limitesY[index]
        construirFila(celdas, layout, filaTop, filaBottom, "Flores")
    }.filter { it.cu.isNotBlank() || it.nombre.isNotBlank() }
        .distinctBy {
            "${normalizarTexto(it.cu)}|${normalizarTexto(it.nombre)}|${it.gcr}"
        }
}

/**
 * Segunda lectura: reporte de Contiene/Capitales.
 *
 * No depende del orden de las columnas y no depende de que las filas Flores estén
 * juntas. Cada fila se detecta mediante su CU y cada dato económico mediante la
 * posición horizontal de su encabezado en ESA fotografía.
 */
fun parsearTodasLasFilasPorFila(visionText: Text): List<PaseFotoFila> {
    val lineas = visionText.textBlocks.flatMap { it.lines }
    if (lineas.isEmpty()) return emptyList()

    val celdas = celdasDeImagen(visionText)
    if (celdas.isEmpty()) return emptyList()

    val layout = detectarLayoutColumnas(celdas, lineas) ?: return emptyList()
    val rangoCu = layout.rango(ColumnaObjetivo.CU) ?: return emptyList()

    val celdasCu = celdas.filter { celda ->
        celda.centroY > layout.yCabecera + layout.toleranciaCabecera &&
            celda.centroX >= rangoCu.left &&
            celda.centroX < rangoCu.right &&
            patronCu.containsMatchIn(celda.texto)
    }.sortedBy { it.centroY }

    if (celdasCu.isEmpty()) return emptyList()

    val limitesY = limitesPorAnclas(celdasCu)

    return celdasCu.mapIndexed { index, celdaCu ->
        val (filaTop, filaBottom) = limitesY[index]
        construirFila(celdas, layout, filaTop, filaBottom, "")
            .let { fila ->
                fila.copy(cu = extraerCu(celdaCu.texto).ifBlank { fila.cu })
            }
    }.filter { it.cu.isNotBlank() }
        .distinctBy {
            "${normalizarTexto(it.cu)}|${normalizarTexto(it.nombre)}"
        }
}

suspend fun extraerPaseDeFoto(context: Context, uri: Uri): List<PaseFotoFila> =
    suspendCancellableCoroutine { cont ->
        var recognizer: TextRecognizer? = null
        try {
            val image = InputImage.fromFilePath(context, uri)
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { visionText: Text ->
                    if (cont.isActive) cont.resume(parsearFilasPaseFoto(visionText))
                    recognizer?.close()
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(emptyList())
                    recognizer?.close()
                }
        } catch (_: Exception) {
            recognizer?.close()
            if (cont.isActive) cont.resume(emptyList())
        }
    }

suspend fun extraerTodasLasFilasDeFoto(context: Context, uri: Uri): List<PaseFotoFila> =
    suspendCancellableCoroutine { cont ->
        var recognizer: TextRecognizer? = null
        try {
            val image = InputImage.fromFilePath(context, uri)
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { visionText: Text ->
                    if (cont.isActive) cont.resume(parsearTodasLasFilasPorFila(visionText))
                    recognizer?.close()
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(emptyList())
                    recognizer?.close()
                }
        } catch (_: Exception) {
            recognizer?.close()
            if (cont.isActive) cont.resume(emptyList())
        }
    }
