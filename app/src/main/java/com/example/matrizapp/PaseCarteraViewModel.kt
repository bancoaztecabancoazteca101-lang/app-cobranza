package com.example.matrizapp

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class PaseCarteraViewModel(private val paseDao: PaseCarteraDao, private val matrizDao: MatrizDao, val driveHelper: DriveHelper) : ViewModel() {
    val paseList: StateFlow<List<PaseEntity>> = paseDao.getAllPase().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _deleteInProgress = MutableStateFlow(false)
    val deleteInProgress: StateFlow<Boolean> = _deleteInProgress
    data class ImportResumen(val detectadosFlores: Int, val coincidenciasMatriz: Int, val nuevosPase: Int, val filas: List<PaseFotoFila>)
    private var preferenciasContext: Context? = null

    private fun normalizarNombreParaImportacion(valor: String?): String =
        quitarAcentos(valor.orEmpty()).uppercase(Locale.ROOT).replace("Ñ", "N")
            .replace(Regex("[^A-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun tokensNombre(valor: String?): List<String> =
        normalizarNombreParaImportacion(valor).split(' ').filter { it.length >= 2 }

    private fun distanciaLevenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var anterior = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val actual = IntArray(b.length + 1)
            actual[0] = i + 1
            for (j in b.indices) {
                actual[j + 1] = minOf(actual[j] + 1, anterior[j + 1] + 1, anterior[j] + if (a[i] == b[j]) 0 else 1)
            }
            anterior = actual
        }
        return anterior[b.length]
    }

    private fun similitudTexto(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 0.0
        return 1.0 - distanciaLevenshtein(a, b).toDouble() / maxLen
    }

    /** Tolera errores OCR leves, pero exige varias palabras fuertes para evitar falsos positivos. */
    private fun puntajeNombreOCR(ocr: String, matriz: String): Double {
        val a = tokensNombre(ocr)
        val b = tokensNombre(matriz)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val coincidencias = a.map { tokenA -> b.maxOf { tokenB ->
            if (tokenA == tokenB) 1.0
            else if (tokenA.length <= 3 || tokenB.length <= 3) 0.0
            else similitudTexto(tokenA, tokenB)
        }}
        val suficientes = coincidencias.count { it >= 0.82 }
        if (suficientes < maxOf(2, (a.size * 0.60).toInt())) return 0.0
        val promedio = coincidencias.average()
        val cobertura = suficientes.toDouble() / a.size
        return promedio * 0.70 + cobertura * 0.30
    }

    /** Nombre primero; CU solamente si el nombre no encuentra un candidato seguro. */
    private fun buscarMatrizPorNombre(nombre: String, matriz: List<MatrizEntity>): MatrizEntity? {
        val buscado = normalizarNombreParaImportacion(nombre)
        if (buscado.isBlank()) return null
        matriz.firstOrNull { normalizarNombreParaImportacion(it.nombre) == buscado }?.let { return it }
        val candidatos = matriz.mapNotNull { item ->
            val score = puntajeNombreOCR(buscado, item.nombre)
            if (score >= 0.84) item to score else null
        }.sortedByDescending { it.second }
        val mejor = candidatos.firstOrNull() ?: return null
        val segundo = candidatos.getOrNull(1)?.second ?: 0.0
        return if (mejor.second - segundo >= 0.04) mejor.first else null
    }

    private fun buscarMatrizPorNombreOCu(fila: PaseFotoFila, matriz: List<MatrizEntity>): MatrizEntity? {
        buscarMatrizPorNombre(fila.nombre, matriz)?.let { return it }
        val cu = normalizarCuPase(fila.cu)
        if (cu.isBlank()) return null
        return matriz.firstOrNull { normalizarCuPase(it.folioP) == cu }
    }

    fun importarFotos(context: Context, uris: List<Uri>, onResult: (ImportResumen?, String?) -> Unit) = viewModelScope.launch {
        try {
            preferenciasContext = context.applicationContext
            val filas = uris.take(8).flatMap { extraerPaseDeFoto(context, it) }.distinctBy { normalizarCuPase(it.cu) }
            val matriz = matrizDao.getAllMatriz().first()
            val matches = filas.count { buscarMatrizPorNombreOCu(it, matriz) != null }
            onResult(ImportResumen(filas.size, matches, filas.size - matches, filas), null)
        } catch (e: Exception) { onResult(null, e.message ?: "No se pudo procesar el reporte") }
    }

    /** Match con Matriz => crea/actualiza Pase inmediatamente; contiene/capitales viven solo en Pase. */
    fun aplicarImportacion(resumen: ImportResumen, onResult: (String) -> Unit) = viewModelScope.launch {
        try {
            val matriz = matrizDao.getAllMatriz().first()
            var matches = 0
            var nuevos = 0
            resumen.filas.forEach { fila ->
                val match = buscarMatrizPorNombreOCu(fila, matriz)
                if (match != null) {
                    val existente = paseDao.getByOrigenMatrizId(match.id)
                    if (existente == null) {
                        paseDao.insertar(PaseEntity(
                            id = UUID.randomUUID().toString().replace("-", "").take(12),
                            nombre = match.nombre, semana = match.semana, requisito = match.requisito,
                            numTT = match.numTT, ref1 = match.ref1, ref2 = match.ref2,
                            observaciones = match.observaciones, estado = "PASE", ubicacion = match.ubicacion,
                            imagenUrl = match.imagenUrl, imagenUrl2 = match.imagenUrl2, fecha = match.fecha,
                            hora = match.hora, ruta = match.ruta, folioP = match.folioP,
                            origenMatrizId = match.id, contiene = fila.contiene, capitales = fila.capitales,
                            isDirty = true
                        ))
                        nuevos++
                    } else {
                        paseDao.updateCamposGcr(existente.id, fila.contiene, fila.capitales)
                    }
                    matrizDao.marcarComoPase(match.id)
                    matches++
                } else {
                    paseDao.insertar(PaseEntity(
                        UUID.randomUUID().toString(), fila.nombre, "", "", "", "", "", null,
                        "PASE", null, null, null, System.currentTimeMillis(), null, null, fila.cu,
                        "IMPORT_FOTO:${fila.cu}:${UUID.randomUUID()}", fila.contiene, fila.capitales, true
                    ))
                    nuevos++
                }
            }
            onResult("Importación aplicada: $matches coincidencia(s) con Matriz y $nuevos registro(s) nuevo(s) en Pase.")
        } catch (e: Exception) { onResult("No se pudo aplicar: ${e.message}") }
    }

    /** Completa importaciones antiguas que quedaron en preferencias locales. */
    fun procesarPendientes() = viewModelScope.launch {
        val ctx = preferenciasContext ?: return@launch
        val prefs = ctx.getSharedPreferences("pase_import_gcr", Context.MODE_PRIVATE)
        prefs.all.forEach { (matrizId, raw) ->
            val partes = raw?.toString()?.split("\u001F", limit = 2) ?: return@forEach
            val pase = paseDao.getByOrigenMatrizId(matrizId) ?: return@forEach
            paseDao.updateCamposGcr(pase.id, partes.getOrNull(0)?.ifBlank { null }, partes.getOrNull(1)?.ifBlank { null })
            prefs.edit().remove(matrizId).apply()
        }
    }

    private fun guardarPendiente(matrizId: String, contiene: String?, capitales: String?) {
        preferenciasContext?.getSharedPreferences("pase_import_gcr", Context.MODE_PRIVATE)?.edit()?.putString(matrizId, "${contiene ?: ""}\u001F${capitales ?: ""}")?.apply()
    }

    fun actualizarCamposGcr(id: String, contiene: String?, capitales: String?, onResult: (String?) -> Unit) = viewModelScope.launch {
        try { paseDao.updateCamposGcr(id, contiene, capitales); onResult(null) }
        catch (e: Exception) { onResult(e.message ?: "No se pudo guardar") }
    }

    fun cambiarIdYGuardar(idAnterior: String, idNuevo: String, nombre: String, semana: String, requisito: String, numTT: String, ref1: String, ref2: String, observaciones: String?, estado: String, ubicacion: String?, fecha: Long?, hora: String?, ruta: String?, folioP: String?, onResult: (Boolean, String?) -> Unit) = viewModelScope.launch {
        val existente = paseList.value.find { it.id == idAnterior }
        if (existente == null) { onResult(false, "El registro ya no existe"); return@launch }
        paseDao.actualizar(existente.copy(nombre = nombre, semana = semana, requisito = requisito, numTT = numTT, ref1 = ref1, ref2 = ref2, observaciones = observaciones, estado = estado, ubicacion = ubicacion, fecha = fecha, hora = hora, ruta = ruta, folioP = folioP, isDirty = true))
        onResult(true, null)
    }

    fun crearRegistro(id: String, nombre: String, semana: String, requisito: String, numTT: String, ref1: String, ref2: String, observaciones: String?, estado: String, ubicacion: String?, fecha: Long, hora: String?, ruta: String?, folioP: String?) {
        val idFinal = id.trim().ifBlank { UUID.randomUUID().toString().replace("-", "").take(12) }
        viewModelScope.launch { paseDao.insertar(PaseEntity(idFinal, nombre, semana, requisito, numTT, ref1, ref2, observaciones, estado, ubicacion, null, null, fecha, hora, ruta, folioP, "", isDirty = true)) }
    }

    fun eliminarRegistro(id: String, onResult: (Boolean, String?) -> Unit) = viewModelScope.launch {
        _deleteInProgress.value = true
        paseDao.eliminar(id)
        _deleteInProgress.value = false
        onResult(true, null)
    }
}
