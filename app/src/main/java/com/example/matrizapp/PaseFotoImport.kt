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

private val REGEX_CU_PASE = Regex("""\b\d{1,3}(?:[-\s]\d{1,3}){2,3}\b""")
private val REGEX_CU_SOLO_DIGITOS = Regex("""\b\d{10,16}\b""")

private fun limpiarOcr(texto: String): String = Normalizer.normalize(texto, Normalizer.Form.NFC)
    .replace("\u00A0", " ").replace("|", " ").replace(Regex("\\s{2,}"), " ").trim()

fun normalizarCuPase(valor: String?): String = valor.orEmpty().trim()
    .replace(Regex("\\s+"), "-").replace(Regex("[^0-9-]"), "")
    .split('-').filter { it.isNotBlank() }
    .joinToString("-") { bloque -> bloque.toLongOrNull()?.toString() ?: bloque }

private fun cuDeLinea(linea: String): String? = REGEX_CU_PASE.find(linea)?.value?.let(::normalizarCuPase)
    ?: REGEX_CU_SOLO_DIGITOS.find(linea)?.value?.let(::normalizarCuPase)

private fun extraerNombreAntesDeFlores(bloque: String, cu: String): String {
    val indiceFlores = bloque.indexOf("Flores", ignoreCase = true)
    val antes = if (indiceFlores >= 0) bloque.substring(0, indiceFlores) else bloque
    val sinCu = antes.replace(cu, " ").trim()
    val tokens = sinCu.split(Regex("\\s+")).filter { it.isNotBlank() }
    val primerNumero = tokens.indexOfFirst { it.matches(Regex("[A-Z]?\\d+(?:[.,]\\d+)?", RegexOption.IGNORE_CASE)) }
    return if (primerNumero > 0) tokens.take(primerNumero).joinToString(" ") else tokens.takeLast(minOf(8, tokens.size)).joinToString(" ")
}

fun parsearFilasPaseFoto(textoOcr: String): List<PaseFotoFila> {
    val lineas = textoOcr.lines().map(::limpiarOcr).filter { it.isNotBlank() }
    val posicionesCu = lineas.mapIndexedNotNull { indice, linea -> cuDeLinea(linea)?.let { indice to it } }
    val resultado = mutableListOf<PaseFotoFila>()
    for ((posicion, cuDetectado) in posicionesCu) {
        val fin = posicionesCu.firstOrNull { it.first > posicion }?.first ?: lineas.size
        val bloque = lineas.subList(posicion, fin).joinToString(" ")
        if (!coincideBusqueda(bloque, "Flores")) continue
        val nombre = extraerNombreAntesDeFlores(bloque, cuDetectado)
        if (nombre.isBlank()) continue
        val indiceFlores = bloque.indexOf("Flores", ignoreCase = true)
        val despues = if (indiceFlores >= 0) bloque.substring(indiceFlores + "Flores".length).trim() else ""
        val finales = despues.split(Regex("\\s+")).filter { it.isNotBlank() }
        resultado += PaseFotoFila(cuDetectado, nombre, "Flores", finales.getOrNull(0)?.takeIf { it.length <= 40 }, finales.getOrNull(1)?.takeIf { it.length <= 40 })
    }
    return resultado.distinctBy { it.cu }
}

suspend fun extraerPaseDeFoto(context: Context, uri: Uri): List<PaseFotoFila> = suspendCancellableCoroutine { cont ->
    var recognizer: TextRecognizer? = null
    try {
        val image = InputImage.fromFilePath(context, uri)
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image).addOnSuccessListener { visionText: Text ->
            if (cont.isActive) cont.resume(parsearFilasPaseFoto(visionText.text))
            recognizer?.close()
        }.addOnFailureListener {
            if (cont.isActive) cont.resume(emptyList())
            recognizer?.close()
        }
    } catch (_: Exception) {
        recognizer?.close()
        if (cont.isActive) cont.resume(emptyList())
    }
}
