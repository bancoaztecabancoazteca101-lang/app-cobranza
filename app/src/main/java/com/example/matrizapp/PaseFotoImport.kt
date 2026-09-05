package com.example.matrizapp

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.text.Normalizer

/** Fila mínima que necesitamos del reporte semanal GCR fotografiado. */
data class PaseFotoFila(
    val cu: String,
    val nombre: String,
    val gcr: String,
    val contiene: String?,
    val capitales: String?
)

private val REGEX_CU_PASE = Regex("""\b\d{2}-\d{2,}-\d{5}-\d+\b""")
private val REGEX_CU_SOLO_DIGITOS = Regex("""\b\d{10,16}\b""")

private fun limpiarOcr(texto: String): String =
    Normalizer.normalize(texto, Normalizer.Form.NFC)
        .replace("\u00A0", " ")
        .replace(Regex("[|]"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

private fun cuDeLinea(linea: String): String? =
    REGEX_CU_PASE.find(linea)?.value
        ?: REGEX_CU_SOLO_DIGITOS.find(linea)?.value

/**
 * El OCR de ML Kit puede entregar el renglón completo de una hoja o fragmentos de él.
 * Para evitar inventar columnas, solo aceptamos renglones que contengan un CU y GCR=Flores.
 * El nombre se toma del texto entre CU y el primer campo claramente numérico posterior;
 * CONTIEN y CAPITALES se toman como los dos últimos tokens del renglón cuando existen.
 */
fun parsearFilasPaseFoto(textoOcr: String): List<PaseFotoFila> {
    val resultado = mutableListOf<PaseFotoFila>()
    val lineas = textoOcr.lines().map(::limpiarOcr).filter { it.isNotBlank() }

    for (lineaOriginal in lineas) {
        val cuMatch = REGEX_CU_PASE.find(lineaOriginal) ?: REGEX_CU_SOLO_DIGITOS.find(lineaOriginal) ?: continue
        val cu = cuMatch.value
        val despuesCu = lineaOriginal.substring(cuMatch.range.last + 1).trim()
        if (!coincideBusqueda(despuesCu, "Flores") && !coincideBusqueda(lineaOriginal, "Flores")) continue

        val gcrIndex = despuesCu.indexOf("Flores", ignoreCase = true)
        val antesGcr = if (gcrIndex >= 0) despuesCu.substring(0, gcrIndex).trim() else despuesCu
        val despuesGcr = if (gcrIndex >= 0) despuesCu.substring(gcrIndex + "Flores".length).trim() else ""

        val tokensAntes = antesGcr.split(Regex("\\s+")).filter { it.isNotBlank() }
        // Después de CU suelen venir NOMBRE, PLAN, DIAS, DIA, SALDO, MORA y REQUE.
        // Buscamos el primer token que parezca un campo numérico y usamos lo anterior como nombre.
        val indiceNumerico = tokensAntes.indexOfFirst { token ->
            token.matches(Regex("""[A-Z]?\d+(?:[.,]\d+)?""", RegexOption.IGNORE_CASE))
        }
        val nombre = if (indiceNumerico > 0) tokensAntes.subList(0, indiceNumerico).joinToString(" ")
        else tokensAntes.takeLast(minOf(5, tokensAntes.size)).joinToString(" ")

        val finales = despuesGcr.split(Regex("\\s+")).filter { it.isNotBlank() }
        val contiene = finales.getOrNull(0)?.takeIf { it.length <= 40 }
        val capitales = finales.getOrNull(1)?.takeIf { it.length <= 40 }

        if (nombre.isNotBlank()) {
            resultado += PaseFotoFila(cu = cu, nombre = nombre, gcr = "Flores", contiene = contiene, capitales = capitales)
        }
    }
    return resultado.distinctBy { it.cu }
}

suspend fun extraerPaseDeFoto(context: Context, uri: Uri): List<PaseFotoFila> =
    suspendCancellableCoroutine { cont ->
        try {
            val image = InputImage.fromFilePath(context, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (cont.isActive) cont.resume(parsearFilasPaseFoto(visionText.text))
                    recognizer.close()
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(emptyList())
                    recognizer.close()
                }
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(emptyList())
        }
    }
