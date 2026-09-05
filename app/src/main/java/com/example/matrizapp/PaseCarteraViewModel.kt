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

    data class ImportResumen(
        val detectadosFlores: Int,
        val coincidenciasMatriz: Int,
        val yaEnPase: Int,
        val aAgregarPase: Int,
        val noEncontradosMatriz: Int,
        val filas: List<PaseFotoFila>,
        val diagnostico: List<String>
    )

    private var preferenciasContext: Context? = null

    private fun normalizarCuPase(valor: String?): String =
        valor.orEmpty().trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("[^0-9-]"), "")
            .split('-')
            .filter { it.isNotBlank() }
            .joinToString("-") { it.toLongOrNull()?.toString() ?: it }

    private fun normalizarNombreParaImportacion(valor: String?): String =
        quitarAcentos(valor.orEmpty()).uppercase(Locale.ROOT).replace("Ñ", "N")
            .replace(Regex("[^A-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun tokensNombre(valor: String?): List<String> = normalizarNombreParaImportacion(valor).split(' ').filter { it.length >= 2 }

    private fun distanciaLevenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var anterior = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val actual = IntArray(b.length + 1)
            actual[0] = i + 1
            for (j in b.indices) actual[j + 1] = minOf(actual[j] + 1, anterior[j + 1] + 1, anterior[j] + if (a[i] == b[j]) 0 else 1)
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
        return coincidencias.average() * 0.70 + suficientes.toDouble() / a.size * 0.30
    }

    private fun buscarMatrizParaFila(fila: PaseFotoFila, matriz: List<MatrizEntity>, idsExcluidos: Set<String> = emptySet()): MatrizEntity? {
        val disponibles = matriz.filterNot { it.id in idsExcluidos }
        val cu = normalizarCuPase(fila.cu)
        if (cu.isNotBlank()) {
            disponibles.firstOrNull {
                normalizarCuPase(it.folioP) == cu || normalizarCuPase(it.id) == cu
            }?.let { return it }
        }
        val buscado = normalizarNombreParaImportacion(fila.nombre)
        if (buscado.isBlank()) return null
        disponibles.firstOrNull { normalizarNombreParaImportacion(it.nombre) == buscado }?.let { return it }
        val candidatos = disponibles.mapNotNull { item ->
            val score = puntajeNombreOCR(buscado, item.nombre)
            if (score >= 0.84) item to score else null
        }.sortedByDescending { it.second }
        val mejor = candidatos.firstOrNull() ?: return null
        val segundo = candidatos.getOrNull(1)?.second ?: 0.0
        return if (mejor.second - segundo >= 0.04) mejor.first else null
    }

    fun importarFotos(context: Context, uris: List<Uri>, onResult: (ImportResumen?, String?) -> Unit) = viewModelScope.launch {
        try {
            preferenciasContext = context.applicationContext
            val filas = uris.take(8).flatMap { extraerPaseDeFoto(context, it) }.distinctBy {
                val cu = normalizarCuPase(it.cu)
                if (cu.isNotBlank()) "CU:$cu" else "NOMBRE:${normalizarNombreParaImportacion(it.nombre)}|GCR:${normalizarNombreParaImportacion(it.gcr)}"
            }
            val matriz = matrizDao.getAllMatriz().first()
            val pase = paseDao.getAllPase().first()
            val idsMatrizUsados = mutableSetOf<String>()
            var coincidenciasMatriz = 0
            var yaEnPase = 0
            var aAgregarPase = 0
            var noEncontradosMatriz = 0
            val diagnostico = mutableListOf<String>()
            filas.forEachIndexed { index, fila ->
                val matrizItem = buscarMatrizParaFila(fila, matriz, idsMatrizUsados)
                if (matrizItem == null) {
                    noEncontradosMatriz++
                    diagnostico += "${index + 1}. OCR NOMBRE=[${fila.nombre.ifBlank { "(vacío)" }}] | OCR CU=[${fila.cu.ifBlank { "(vacío)" }}] | OCR CONTIENE=[${fila.contiene ?: "(vacío)"}] | MATRIZ=NO ENCONTRADO"
                } else {
                    idsMatrizUsados += matrizItem.id
                    coincidenciasMatriz++
                    if (pase.any { it.origenMatrizId == matrizItem.id }) yaEnPase++ else aAgregarPase++
                    diagnostico += "${index + 1}. OCR NOMBRE=[${fila.nombre.ifBlank { "(vacío)" }}] | OCR CU=[${fila.cu.ifBlank { "(vacío)" }}] | OCR CONTIENE=[${fila.contiene ?: "(vacío)" }] | MATRIZ=OK → [${matrizItem.nombre}] CU=[${matrizItem.folioP ?: matrizItem.id}]"
                }
            }
            onResult(ImportResumen(filas.size, coincidenciasMatriz, yaEnPase, aAgregarPase, noEncontradosMatriz, filas, diagnostico), null)
        } catch (e: Exception) { onResult(null, e.message ?: "No se pudo procesar el reporte") }
    }

    fun aplicarImportacion(resumen: ImportResumen, onResult: (String) -> Unit) = viewModelScope.launch {
        try {
            val matriz = matrizDao.getAllMatriz().first()
            val pase = paseDao.getAllPase().first()
            val idsMatrizAplicados = mutableSetOf<String>()
            var agregados = 0
            var yaExistian = 0
            var noEncontrados = 0
            resumen.filas.forEach { fila ->
                val matrizItem = buscarMatrizParaFila(fila, matriz, idsMatrizAplicados)
                if (matrizItem == null) noEncontrados++ else {
                    idsMatrizAplicados += matrizItem.id
                    val yaEnPase = pase.firstOrNull { it.origenMatrizId == matrizItem.id }
                    if (yaEnPase != null) {
                        if (!fila.contiene.isNullOrBlank()) {
                            paseDao.updateCamposGcr(yaEnPase.id, fila.contiene, yaEnPase.capitales)
                        }
                        yaExistian++
                    } else {
                        paseDao.insertar(PaseEntity(UUID.randomUUID().toString().replace("-", "").take(12), matrizItem.nombre, matrizItem.semana, matrizItem.requisito, matrizItem.numTT, matrizItem.ref1, matrizItem.ref2, matrizItem.observaciones, "PASE", matrizItem.ubicacion, matrizItem.imagenUrl, matrizItem.imagenUrl2, matrizItem.fecha, matrizItem.hora, matrizItem.ruta, matrizItem.folioP, matrizItem.id, fila.contiene, null, isDirty = true))
                        agregados++
                    }
                }
            }
            onResult("Importación aplicada: $agregados registro(s) de Matriz fueron llevados a Pase. " + if (yaExistian > 0) "$yaExistian ya existían en Pase. " else "" + if (noEncontrados > 0) "$noEncontrados fila(s) no se encontraron en Matriz y NO se agregaron." else "Todas las filas detectadas coincidieron con Matriz.")
        } catch (e: Exception) { onResult("No se pudo aplicar: ${e.message}") }
    }

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
        try { paseDao.updateCamposGcr(id, contiene, capitales); onResult(null) } catch (e: Exception) { onResult(e.message ?: "No se pudo guardar") }
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
