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

data class PaseFotoFila(
    val cu: String,
    val nombre: String,
    val gcr: String,
    val contiene: String?,
    val capitales: String?
)

// Los CU del reporte pueden venir como 1-1-673-33618, 1-1-1339-46633, etc.
// En Matriz pueden conservar ceros a la izquierda: 01-01-00673-33618.
private val REGEX_CU_PASE = Regex("""\b\d{1,3}(?:[-\s]\d{1,3}){2,3}\b""")
private val REGEX_CU_SOLO_DIGITOS = Regex("""\b\d{10,16}\b""")

private fun limpiarOcr(texto: String): String =
    Normalizer.normalize(texto, Normalizer.Form.NFC)
        .replace("\u00A0", " ")
        .replace("|", " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

/** Canonicaliza cada bloque numérico para que 673 y 00673 sean el mismo CU. */
fun normalizarCuPase(valor: String?): String =
    valor.orEmpty()
        .trim()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("[^0-9-]"), "")
        .split('-')
        .filter { it.isNotBlank() }
        .joinToString("-") { bloque -> bloque.toLongOrNull()?.toString() ?: bloque }
        .uppercase()

private fun cuDeLinea(linea: String): String? =
    REGEX_CU_PASE.find(linea)?.value?.let(::normalizarCuPase)
        ?: REGEX_CU_SOLO_DIGITOS.find(linea)?.value?.let(::normalizarCuPase)

private fun contieneFlores(texto: String): Boolean = coincideBusqueda(texto, "Flores")

/**
 * ML Kit puede separar una fila de Excel en varias líneas. Construimos bloques
 * desde un CU hasta el siguiente CU y buscamos GCR=Flores en todo el bloque.
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

        val indiceFlores = textoBloque.indexOf("Flores", ignoreCase = true)
        val antesGcr = if (indiceFlores >= 0) textoBloque.substring(0, indiceFlores).trim() else textoBloque
        val tokensAntes = antesGcr.split(Regex("\\s+")).filter { it.isNotBlank() }

        // El nombre normalmente ocupa la columna entre CU y PLAN; tomamos texto
        // antes de los primeros campos claramente numéricos del resto de columnas.
        val indiceNumerico = tokensAntes.indexOfFirst { token ->
            token.matches(Regex("[A-Z]?\\d+(?:[.,]\\d+)?", RegexOption.IGNORE_CASE))
        }
        val nombre = when {
            indiceNumerico > 0 -> tokensAntes.subList(0, indiceNumerico).joinToString(" ")
            tokensAntes.isNotEmpty() -> tokensAntes.takeLast(minOf(8, tokensAntes.size)).joinToString(" ")
            else -> ""
        }

        val despuesGcr = if (indiceFlores >= 0) textoBloque.substring(indiceFlores + "Flores".length).trim() else ""
        val finales = despuesGcr.split(Regex("\\s+")).filter { it.isNotBlank() }
        val contiene = finales.getOrNull(0)?.takeIf { it.length <= 40 }
        val capitales = finales.getOrNull(1)?.takeIf { it.length <= 40 }

        if (nombre.isNotBlank()) {
            resultado += PaseFotoFila(cuDetectado, nombre, "Flores", contiene, capitales)
        }
    }

    return resultado.distinctBy { it.cu }
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
