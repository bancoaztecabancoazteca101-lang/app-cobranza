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
    return texto == "FLORES" ||
        texto == "FL0RES" ||
        texto == "FLORES0" ||
        texto == "FLORES." ||
        texto.matches(Regex("FL0?RES"))
}

private fun centroSeguro(bounds: Rect?): Float? = bounds?.let { it.left + it.width() / 2f }

private fun puntoMedio(a: Float, b: Float): Float = (a + b) / 2f

/** CU de cuatro bloques que el OCR suele leer como 01-01-00673-33618. */
private val patronCu = Regex("(?<!\\d)\\d{1,2}-\\d{1,2}-\\d{3,6}-\\d{3,6}(?!\\d)")

/**
 * Detecta exclusivamente las filas cuyo GCR es FLORES.
 *
 * La detección NO depende del apellido del cliente ni de CONTIENE/CAPITALES.
 * Usa las coordenadas de los elementos OCR para distinguir la columna GCR de la
 * columna NOMBRE; por eso "FLORES" dentro de un nombre no dispara la detección.
 */
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

    val encabezadoGcr = celdas
        .filter { esCabecera(it.texto, "GCR") }
        .minByOrNull { it.bounds.top }
        ?: return emptyList()

    val lineaCabecera = lineas.firstOrNull { line ->
        line.elements.any { element ->
            element.boundingBox == encabezadoGcr.bounds && esCabecera(element.text, "GCR")
        }
    }

    val cabeceras = mutableMapOf<String, Float>()
    lineaCabecera?.elements?.forEach { element ->
        val centro = centroSeguro(element.boundingBox) ?: return@forEach
        val texto = normalizarTexto(element.text)
        when (texto) {
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

    val yCabecera = encabezadoGcr.centroY
    val toleranciaCabecera = maxOf(24f, encabezadoGcr.alto * 1.8f)
    celdas.filter { kotlin.math.abs(it.centroY - yCabecera) <= toleranciaCabecera }
        .forEach { celda ->
            val centro = celda.centroX
            when (normalizarTexto(celda.texto)) {
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

    val gcrX = cabeceras["GCR"] ?: encabezadoGcr.centroX
    val nombreX = cabeceras["NOMBRE"]
    val planX = cabeceras["PLAN"]
    val cuX = cabeceras["CU"]
    val requeX = cabeceras["REQUE"]
    val contieneX = cabeceras["CONTIENE"]
    val capitalesX = cabeceras["CAPITALES"]

    val vecinoIzquierdo = listOfNotNull(requeX, cabeceras["MORA"], cabeceras["SALDO"], cabeceras["DIA"], cabeceras["DIAS"], planX)
        .filter { it < gcrX }
        .maxOrNull()
    val vecinoDerecho = listOfNotNull(contieneX, capitalesX)
        .filter { it > gcrX }
        .minOrNull()

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

    // Límites de la columna CONTIENE. Se calculan a partir de las cabeceras
    // vecinas para evitar confundir el valor con GCR o CAPITALES.
    val contieneLeft = when {
        contieneX != null -> puntoMedio(gcrX, contieneX)
        else -> gcrRight
    }
    val contieneRight = when {
        contieneX != null && capitalesX != null -> puntoMedio(contieneX, capitalesX)
        contieneX != null -> contieneX + (contieneX - gcrX) / 2f
        else -> contieneLeft
    }

    val flores = celdas.filter { celda ->
        celda.centroY > yCabecera + toleranciaCabecera &&
            celda.centroX in gcrLeft..gcrRight &&
            esFlores(celda.texto)
    }
    if (flores.isEmpty()) return emptyList()

    val resultado = mutableListOf<PaseFotoFila>()

    for (flor in flores) {
        val toleranciaFila = maxOf(18f, flor.alto * 1.35f)
        val mismaFila = celdas.filter {
            kotlin.math.abs(it.centroY - flor.centroY) <= toleranciaFila
        }

        val nombrePorColumna = mismaFila
            .filter { it.centroX in nombreLeft..nombreRight }
            .sortedBy { it.centroX }
            .joinToString(" ") { it.texto }
            .trim()

        val cuPorColumna = mismaFila
            .filter { it.centroX in cuLeft..cuRight }
            .sortedBy { it.centroX }
            .joinToString(" ") { it.texto }
            .trim()

        val textoFila = mismaFila.sortedBy { it.centroX }.joinToString(" ") { it.texto }
        val cuDetectado = patronCu.find(textoFila)?.value
            ?: patronCu.find(cuPorColumna)?.value
            ?: ""

        val nombre = if (cuDetectado.isNotBlank()) {
            nombrePorColumna
                .replace(cuDetectado, " ", ignoreCase = true)
                .replace(Regex("\\s+"), " ")
                .trim()
        } else {
            nombrePorColumna
        }

        val cu = cuDetectado.ifBlank { cuPorColumna }

        val contiene = mismaFila
            .filter { it.centroX in contieneLeft..contieneRight }
            .sortedBy { it.centroX }
            .joinToString(" ") { it.texto }
            .trim()
            .ifBlank { null }

        resultado += PaseFotoFila(
            cu = cu,
            nombre = nombre,
            gcr = "Flores",
            contiene = contiene,
            capitales = null
        )
    }

    return resultado.distinctBy {
        "${normalizarTexto(it.cu)}|${normalizarTexto(it.nombre)}|${it.gcr}"
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
