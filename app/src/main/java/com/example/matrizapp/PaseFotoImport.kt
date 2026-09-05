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

private fun esCabecera(valor: String, esperado: String): Boolean =
    normalizarTexto(valor) == esperado

private fun esFlores(valor: String): Boolean {
    val texto = normalizarTexto(valor)
    return texto == "FLORES" || texto == "FL0RES" || texto == "FLORES0" ||
        texto == "FLORES." || texto.matches(Regex("FL0?RES"))
}

private fun centroSeguro(bounds: Rect?): Float? = bounds?.let { it.left + it.width() / 2f }
private fun puntoMedio(a: Float, b: Float): Float = (a + b) / 2f

private val patronCu = Regex("(?<!\\d)\\d{1,2}-\\d{1,2}-\\d{3,6}-\\d{3,6}(?!\\d)")

/** Une columnas/cabeceras detectadas para una foto del reporte GCR (rangos X por columna). */
private data class LayoutColumnas(
    val yCabecera: Float,
    val toleranciaCabecera: Float,
    val nombreLeft: Float, val nombreRight: Float,
    val cuLeft: Float, val cuRight: Float,
    val gcrLeft: Float, val gcrRight: Float,
    val contieneLeft: Float, val contieneRight: Float,
    val capitalesLeft: Float, val capitalesRight: Float
)

private fun detectarLayoutColumnas(celdas: List<CeldaOcr>, lineas: List<Text.Line>): LayoutColumnas? {
    val encabezadoGcr = celdas.filter { esCabecera(it.texto, "GCR") }.minByOrNull { it.bounds.top } ?: return null
    val lineaCabecera = lineas.firstOrNull { line ->
        line.elements.any { element -> element.boundingBox == encabezadoGcr.bounds && esCabecera(element.text, "GCR") }
    }

    val cabeceras = mutableMapOf<String, Float>()
    fun registrarCabecera(textoOriginal: String, centro: Float) {
        when (normalizarTexto(textoOriginal)) {
            "CU" -> cabeceras.putIfAbsent("CU", centro)
            "NOMBRE" -> cabeceras.putIfAbsent("NOMBRE", centro)
            "PLAN" -> cabeceras.putIfAbsent("PLAN", centro)
            "DIAS" -> cabeceras.putIfAbsent("DIAS", centro)
            "DIA" -> cabeceras.putIfAbsent("DIA", centro)
            "SALDO" -> cabeceras.putIfAbsent("SALDO", centro)
            "MORA" -> cabeceras.putIfAbsent("MORA", centro)
            "REQUE", "REQUERIMIENTO" -> cabeceras.putIfAbsent("REQUE", centro)
            "GCR" -> cabeceras.putIfAbsent("GCR", centro)
            "CONTIEN", "CONTIENE" -> cabeceras.putIfAbsent("CONTIENE", centro)
            "CAPITALES", "CAPITAL" -> cabeceras.putIfAbsent("CAPITALES", centro)
        }
    }

    lineaCabecera?.elements?.forEach { element ->
        val centro = centroSeguro(element.boundingBox) ?: return@forEach
        registrarCabecera(element.text, centro)
    }

    val yCabecera = encabezadoGcr.centroY
    val toleranciaCabecera = maxOf(24f, encabezadoGcr.alto * 1.8f)
    celdas.filter { kotlin.math.abs(it.centroY - yCabecera) <= toleranciaCabecera }.forEach { celda ->
        registrarCabecera(celda.texto, celda.centroX)
    }

    val gcrX = cabeceras["GCR"] ?: encabezadoGcr.centroX
    val nombreX = cabeceras["NOMBRE"]
    val planX = cabeceras["PLAN"]
    val cuX = cabeceras["CU"]
    val requeX = cabeceras["REQUE"]
    val contieneX = cabeceras["CONTIENE"]
    val capitalesX = cabeceras["CAPITALES"]

    val vecinoIzquierdo = listOfNotNull(requeX, cabeceras["MORA"], cabeceras["SALDO"], cabeceras["DIA"], cabeceras["DIAS"], planX)
        .filter { it < gcrX }.maxOrNull()
    val vecinoDerecho = listOfNotNull(contieneX, capitalesX).filter { it > gcrX }.minOrNull()

    val pasoIzquierdo = vecinoIzquierdo?.let { gcrX - it }
    val pasoDerecho = vecinoDerecho?.let { it - gcrX }
    val gcrAncho = maxOf(pasoIzquierdo ?: pasoDerecho ?: 100f, pasoDerecho ?: pasoIzquierdo ?: 100f)
    val gcrLeft = vecinoIzquierdo?.let { puntoMedio(it, gcrX) } ?: (gcrX - gcrAncho / 2f)
    val gcrRight = vecinoDerecho?.let { puntoMedio(gcrX, it) } ?: (gcrX + gcrAncho / 2f)

    val nombreLeft = when {
        cuX != null && nombreX != null -> puntoMedio(cuX, nombreX)
        nombreX != null -> nombreX - 250f
        else -> 0f
    }
    val nombreRight = when {
        nombreX != null && planX != null -> puntoMedio(nombreX, planX)
        nombreX != null && requeX != null -> puntoMedio(nombreX, requeX)
        nombreX != null -> gcrLeft
        else -> gcrLeft
    }

    val cuLeft = 0f
    val cuRight = if (cuX != null && nombreX != null) puntoMedio(cuX, nombreX) else nombreLeft

    val contieneLeft = if (contieneX != null) puntoMedio(gcrX, contieneX) else gcrRight
    val contieneRight = when {
        contieneX != null && capitalesX != null -> puntoMedio(contieneX, capitalesX)
        contieneX != null -> contieneX + (contieneX - gcrX) / 2f
        else -> contieneLeft
    }

    val capitalesLeft = when {
        capitalesX != null && contieneX != null -> puntoMedio(contieneX, capitalesX)
        capitalesX != null -> capitalesX - (capitalesX - gcrX) / 2f
        else -> contieneRight
    }
    val capitalesRight = when {
        capitalesX != null -> {
            val paso = when {
                contieneX != null -> capitalesX - contieneX
                vecinoIzquierdo != null -> capitalesX - gcrX
                else -> 100f
            }
            capitalesX + paso / 2f
        }
        else -> capitalesLeft
    }

    return LayoutColumnas(
        yCabecera, toleranciaCabecera,
        nombreLeft, nombreRight, cuLeft, cuRight, gcrLeft, gcrRight,
        contieneLeft, contieneRight, capitalesLeft, capitalesRight
    )
}

/** Detecta exclusivamente las filas cuyo GCR es FLORES usando coordenadas OCR. */
fun parsearFilasPaseFoto(visionText: Text): List<PaseFotoFila> {
    val lineas = visionText.textBlocks.flatMap { it.lines }
    if (lineas.isEmpty()) return emptyList()

    val celdas = lineas.flatMap { line ->
        line.elements.mapNotNull { element ->
            val bounds = element.boundingBox ?: return@mapNotNull null
            val texto = limpiarOcr(element.text)
            if (texto.isBlank()) null else CeldaOcr(texto, bounds)
        }
    }
    if (celdas.isEmpty()) return emptyList()

    val layout = detectarLayoutColumnas(celdas, lineas) ?: return emptyList()

    val flores = celdas.filter { celda ->
        celda.centroY > layout.yCabecera + layout.toleranciaCabecera &&
            celda.centroX in layout.gcrLeft..layout.gcrRight && esFlores(celda.texto)
    }.sortedBy { it.centroY }
    if (flores.isEmpty()) return emptyList()

    // Cada GCR=FLORES define una fila. Los limites se colocan a mitad de camino
    // entre dos celdas GCR consecutivas, evitando mezclar nombres/CU de filas vecinas.
    // OJO: esto asume que las filas FLORES estan relativamente cerca unas de otras
    // (como cuando el reporte viene ordenado por GCR). Si el reporte viene ordenado
    // por otra columna (ej. Capitales) y las filas FLORES quedan dispersas, este
    // punto medio puede abarcar muchas filas ajenas -- para ese caso usar
    // parsearTodasLasFilasPorFila() en vez de esta funcion.
    val limitesY = flores.mapIndexed { index, flor ->
        val anterior = flores.getOrNull(index - 1)?.centroY
        val siguiente = flores.getOrNull(index + 1)?.centroY
        val top = if (anterior != null) puntoMedio(anterior, flor.centroY)
        else flor.centroY - maxOf(22f, flor.alto * 1.8f)
        val bottom = if (siguiente != null) puntoMedio(flor.centroY, siguiente)
        else flor.centroY + maxOf(22f, flor.alto * 1.8f)
        top to bottom
    }

    val resultado = mutableListOf<PaseFotoFila>()
    flores.forEachIndexed { index, flor ->
        val (filaTop, filaBottom) = limitesY[index]
        val mismaFila = celdas.filter {
            it.centroY >= filaTop && it.centroY < filaBottom
        }

        val nombrePorColumna = mismaFila.filter { it.centroX in layout.nombreLeft..layout.nombreRight }
            .sortedWith(compareBy<CeldaOcr> { it.centroY }.thenBy { it.centroX })
            .joinToString(" ") { it.texto }.trim()
        val cuPorColumna = mismaFila.filter { it.centroX in layout.cuLeft..layout.cuRight }
            .sortedWith(compareBy<CeldaOcr> { it.centroY }.thenBy { it.centroX })
            .joinToString(" ") { it.texto }.trim()

        val textoFila = mismaFila.sortedWith(compareBy<CeldaOcr> { it.centroY }.thenBy { it.centroX })
            .joinToString(" ") { it.texto }
        val cuDetectado = patronCu.find(textoFila)?.value ?: patronCu.find(cuPorColumna)?.value ?: ""
        val nombre = if (cuDetectado.isNotBlank()) {
            nombrePorColumna.replace(cuDetectado, " ", ignoreCase = true).replace(Regex("\\s+"), " ").trim()
        } else nombrePorColumna
        val cu = cuDetectado.ifBlank { cuPorColumna }

        val contiene = mismaFila.filter { it.centroX in layout.contieneLeft..layout.contieneRight }
            .sortedBy { it.centroX }.joinToString(" ") { it.texto }.trim().ifBlank { null }
        val capitales = mismaFila.filter { it.centroX in layout.capitalesLeft..layout.capitalesRight }
            .sortedBy { it.centroX }.joinToString(" ") { it.texto }.trim().ifBlank { null }

        resultado += PaseFotoFila(cu, nombre, "Flores", contiene, capitales)
    }

    return resultado.distinctBy { "${normalizarTexto(it.cu)}|${normalizarTexto(it.nombre)}|${it.gcr}" }
}

/**
 * Segunda lectura, para la foto de "Contiene/Capitales" (que normalmente viene ordenada
 * por Capitales, no por GCR, asi que las filas Flores quedan dispersas entre muchas
 * otras). A diferencia de parsearFilasPaseFoto, aqui CADA fila se detecta de forma
 * INDEPENDIENTE: su rango vertical sale de la altura de su propia celda de CU, nunca
 * de la distancia a otra fila. Esto evita que filas ajenas se mezclen cuando el orden
 * del reporte dispersa las filas que nos interesan. Devuelve TODAS las filas del
 * reporte (no solo Flores) -- el filtrado por cliente ya conocido se hace despues,
 * en el ViewModel, comparando contra los clientes que ya confirmamos en la foto 1.
 */
fun parsearTodasLasFilasPorFila(visionText: Text): List<PaseFotoFila> {
    val lineas = visionText.textBlocks.flatMap { it.lines }
    if (lineas.isEmpty()) return emptyList()

    val celdas = lineas.flatMap { line ->
        line.elements.mapNotNull { element ->
            val bounds = element.boundingBox ?: return@mapNotNull null
            val texto = limpiarOcr(element.text)
            if (texto.isBlank()) null else CeldaOcr(texto, bounds)
        }
    }
    if (celdas.isEmpty()) return emptyList()

    val layout = detectarLayoutColumnas(celdas, lineas) ?: return emptyList()

    val celdasCu = celdas.filter { celda ->
        celda.centroY > layout.yCabecera + layout.toleranciaCabecera &&
            celda.centroX in layout.cuLeft..layout.cuRight &&
            patronCu.containsMatchIn(celda.texto)
    }.sortedBy { it.centroY }
    if (celdasCu.isEmpty()) return emptyList()

    // Limite vertical de cada fila = punto medio hasta la celda de CU anterior/
    // siguiente (igual que con Flores), no un multiplo de la altura de la propia
    // celda -- esa estimacion fallaba cuando las filas estan mas pegadas de lo
    // normal y terminaba mezclando Contiene/Capitales de la fila de al lado.
    val limitesY = celdasCu.mapIndexed { index, cu ->
        val anterior = celdasCu.getOrNull(index - 1)?.centroY
        val siguiente = celdasCu.getOrNull(index + 1)?.centroY
        val top = if (anterior != null) puntoMedio(anterior, cu.centroY)
        else cu.centroY - maxOf(22f, cu.alto * 1.8f)
        val bottom = if (siguiente != null) puntoMedio(cu.centroY, siguiente)
        else cu.centroY + maxOf(22f, cu.alto * 1.8f)
        top to bottom
    }

    val resultado = mutableListOf<PaseFotoFila>()
    celdasCu.forEachIndexed { index, celdaCu ->
        val (filaTop, filaBottom) = limitesY[index]
        val mismaFila = celdas.filter { it.centroY >= filaTop && it.centroY < filaBottom }

        val nombre = mismaFila.filter { it.centroX in layout.nombreLeft..layout.nombreRight }
            .sortedWith(compareBy<CeldaOcr> { it.centroY }.thenBy { it.centroX })
            .joinToString(" ") { it.texto }.trim()
        val cu = patronCu.find(celdaCu.texto)?.value ?: celdaCu.texto
        val contiene = mismaFila.filter { it.centroX in layout.contieneLeft..layout.contieneRight }
            .sortedBy { it.centroX }.joinToString(" ") { it.texto }.trim().ifBlank { null }
        val capitales = mismaFila.filter { it.centroX in layout.capitalesLeft..layout.capitalesRight }
            .sortedBy { it.centroX }.joinToString(" ") { it.texto }.trim().ifBlank { null }

        resultado += PaseFotoFila(cu, nombre, "", contiene, capitales)
    }

    return resultado.distinctBy { "${normalizarTexto(it.cu)}|${normalizarTexto(it.nombre)}" }
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

/** Igual que extraerPaseDeFoto, pero para la foto de Contiene/Capitales -- lee TODAS
 * las filas de forma independiente (ver parsearTodasLasFilasPorFila). */
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
