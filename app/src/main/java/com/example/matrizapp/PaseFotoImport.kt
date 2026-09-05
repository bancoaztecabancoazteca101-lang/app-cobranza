package com.example.matrizapp

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.text.Normalizer

data class PaseFotoFila(val cu: String, val nombre: String, val gcr: String, val contiene: String?, val capitales: String?)

private fun limpiarOcr(texto: String): String =
    Normalizer.normalize(texto, Normalizer.Form.NFC)
        .replace("\u00A0", " ")
        .replace("|", " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

private fun normalizarTexto(valor: String): String =
    Normalizer.normalize(valor, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .uppercase()
        .replace(Regex("[^A-Z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun esFlores(valor: String): Boolean {
    val texto = normalizarTexto(valor)
    return texto == "FLORES" ||
        texto == "FL0RES" ||
        texto == "FLORES." ||
        texto == "FLORES-" ||
        texto.matches(Regex("FLO?RES"))
}

/**
 * Parser temporal enfocado exclusivamente en la columna GCR.
 * No depende de CU, CONTIENE ni CAPITALES para detectar una fila.
 *
 * La prioridad es localizar el encabezado GCR y, desde las líneas OCR
 * posteriores, detectar una celda cuyo contenido sea FLORES. De esta forma
 * un cliente cuyo nombre/apellido sea Flores no se toma como GCR Flores
 * solamente por aparecer en cualquier parte de la fotografía.
 */
fun parsearFilasPaseFoto(textoOcr: String): List<PaseFotoFila> {
    val lineas = textoOcr.lines()
        .map(::limpiarOcr)
        .filter { it.isNotBlank() }

    if (lineas.isEmpty()) return emptyList()

    val indiceGcr = lineas.indexOfFirst {
        normalizarTexto(it).split(' ').any { token -> token == "GCR" }
    }

    if (indiceGcr < 0) return emptyList()

    val resultado = mutableListOf<PaseFotoFila>()

    // Buscamos FLORES solamente después de haber localizado el encabezado GCR.
    // Cada línea OCR que contiene una celda GCR independiente se considera
    // una posible fila; se excluyen coincidencias embebidas en nombres.
    for (i in indiceGcr + 1 until lineas.size) {
        val linea = lineas[i]
        val tokens = linea.split(Regex("\\s+"))

        tokens.forEachIndexed { indice, token ->
            if (!esFlores(token)) return@forEachIndexed

            // Si OCR entregó una línea tabular, GCR suele aparecer como una
            // celda independiente. No aceptamos "FLORES" dentro de una cadena
            // que claramente parece nombre completo.
            val contexto = tokens.joinToString(" ")
            val cantidadTokens = tokens.size
            val pareceCeldaGcr = cantidadTokens <= 8 ||
                contexto.equals("FLORES", ignoreCase = true)

            if (!pareceCeldaGcr) return@forEachIndexed

            // En esta fase todavía no dependemos del CU. Dejamos identificador
            // vacío; el cruce posterior se hará contra Pase por nombre/posición
            // cuando corresponda.
            val antes = tokens.take(indice)
            val nombre = antes
                .filter { it.length >= 2 && !it.matches(Regex("\\d+")) }
                .takeLast(6)
                .joinToString(" ")
                .trim()

            resultado += PaseFotoFila(
                cu = "",
                nombre = nombre,
                gcr = "Flores",
                contiene = null,
                capitales = null
            )
        }
    }

    return resultado.distinctBy { "${it.nombre}|${it.gcr}" }
}

suspend fun extraerPaseDeFoto(context: Context, uri: Uri): List<PaseFotoFila> =
    suspendCancellableCoroutine { cont ->
        var recognizer: TextRecognizer? = null
        try {
            val image = InputImage.fromFilePath(context, uri)
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { visionText: Text ->
                    if (cont.isActive) cont.resume(parsearFilasPaseFoto(visionText.text))
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
