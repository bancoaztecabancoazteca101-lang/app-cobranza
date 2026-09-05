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

/** Fila mínima que necesitamos del reporte semanal GCR fotografiado. */
data class PaseFotoFila(
    val cu: String,
    val nombre: String,
    val gcr: String,
    val contiene: String?,
    val capitales: String?
)

private val REGEX_CU_PASE = Regex("""\b\d{2}[-\s]\d{2,}[-\s]\d{5}[-\s]\d+\b""")
private val REGEX_CU_SOLO_DIGITOS = Regex("""\b\d{10,16}\b""")

private fun limpiarOcr(texto: String): String =
    Normalizer.normalize(texto, Normalizer.Form.NFC)
        .replace("\u00A0", " ")
        .replace(Regex("[|]"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

private fun normalizarCu(valor: String): String =
    valor.trim()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("-+"), "-")
        .uppercase()

private fun cuDeLinea(linea: String): String? =
    REGEX_CU_PASE.find(linea)?.value?.let(::normalizarCu)
        ?: REGEX_CU_SOLO_DIGITOS.find(linea)?.value

private fun contieneFlores(texto: String): Boolean = coincideBusqueda(texto, "Flores")

/**
 * El OCR de ML Kit no garantiza que una fila de una hoja llegue en una sola línea.
 * En fotografías de tablas suele separar CU, nombre y GCR en líneas distintas.
 * Por eso primero construimos bloques desde un CU hasta antes del siguiente CU y
 * buscamos GCR=Flores dentro de todo ese bloque.
 */
fun parsearFilasPaseFoto(textoOcr: String): List<PaseFotoFila> {
    val lineas = textoOcr.lines()
        .map(::limpiarOcr)
        .filter { it.isNotBlank() }

    val posicionesCu = lineas.mapIndexedNotNull { indice, linea ->
        cuDeLinea(linea)?.let { indice to it }
    }

    val resultado = mutableListOf<PaseFotoFila>()

    for ((posicion, cuDetectado) in posicionesCu) {
        val fin = posicionesCu.firstOrNull { it.first > posicion }?.first ?: lineas.size
        val bloque = lineas.subList(posicion, fin)
        val textoBloque = bloque.joinToString(" ")

        if (!contieneFlores(textoBloque)) continue

        val cu = cuDetectado
        val textoSinCu = textoBloque.replaceFirst(cuDetectado, " ").trim()

        // Flores puede aparecer con otras columnas delante o detrás.
        // El nombre se obtiene de la zona anterior a Flores, descartando campos numéricos.
        val indiceFlores = textoSinCu.indexOfFirstIgnoreCase("Flores")
        val antesGcr = if (indiceFlores >= 0) {
            textoSinCu.substring(0, indiceFlores).trim()
        } else {
            textoSinCu
        }

        val tokensAntes = antesGcr.split(Regex("\\s+")).filter { it.isNotBlank() }
        val indiceNumerico = tokensAntes.indexOfFirst { token ->
            token.matches(Regex("""[A-Z]?\d+(?:[.,]\d+)?""", RegexOption.IGNORE_CASE))
        }

        val nombre = when {
            indiceNumerico > 0 -> tokensAntes.subList(0, indiceNumerico).joinToString(" ")
            tokensAntes.isNotEmpty() -> tokensAntes.takeLast(minOf(8, tokensAntes.size)).joinToString(" ")
            else -> ""
        }

        val despuesGcr = if (indiceFlores >= 0) {
            textoSinCu.substring(indiceFlores + "Flores".length).trim()
        } else {
            ""
        }
        val finales = despuesGcr.split(Regex("\\s+")).filter { it.isNotBlank() }
        val contiene = finales.getOrNull(0)?.takeIf { it.length <= 40 }
        val capitales = finales.getOrNull(1)?.takeIf { it.length <= 40 }

        if (nombre.isNotBlank()) {
            resultado += PaseFotoFila(
                cu = cu,
                nombre = nombre,
                gcr = "Flores",
                contiene = contiene,
                capitales = capitales
            )
        }
    }

    return resultado.distinctBy { it.cu }
}

private fun String.indexOfFirstIgnoreCase(valor: String): Int {
    val regex = Regex(Regex.escape(valor), RegexOption.IGNORE_CASE)
    return regex.find(this)?.range?.first ?: -1
}

/**
 * Extrae el texto localmente con ML Kit. La app no sube la fotografía a un servicio externo.
 */
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
