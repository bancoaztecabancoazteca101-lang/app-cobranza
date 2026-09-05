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
import kotlin.math.max

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
        val filas: List<PaseFotoFila>
    )
    private var preferenciasContext: Context? = null

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

    /** Busca la fila OCR solamente en los registros existentes de Matriz. */
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
            filas.forEach { fila ->
                val matrizItem = buscarMatrizParaFila(fila, matriz, idsMatrizUsados)
                if (matrizItem == null) noEncontradosMatriz++ else {
                    idsMatrizUsados += matrizItem.id
                    coincidenciasMatriz++
                    if (pase.any { it.origenMatrizId == matrizItem.id }) yaEnPase++ else aAgregarPase++
                }
            }
            onResult(ImportResumen(filas.size, coincidenciasMatriz, yaEnPase, aAgregarPase, noEncontradosMatriz, filas), null)
        } catch (e: Exception) { onResult(null, e.message ?: "No se pudo procesar el reporte") }
    }

    fun aplicarImportacion(resumen: ImportResumen, onResult: (String) -> Unit) = viewModelScope.launch {
        try {
            val matriz = matrizDao.getAllMatriz().first()
            val pase = paseDao.getAllPase().first()
            val idsAplicados = mutableSetOf<String>()
            var agregados = 0
            var yaExistentes = 0
            var noEncontrados = 0
            resumen.filas.forEach { fila ->
                val matrizItem = buscarMatrizParaFila(fila, matriz, idsAplicados)
                if (matrizItem == null) noEncontrados++ else {
                    val existentePase = pase.firstOrNull { it.origenMatrizId == matrizItem.id }
                    if (existentePase != null) yaExistentes++ else {
                        val nuevo = PaseEntity(
                            id = matrizItem.id,
                            nombre = matrizItem.nombre,
                            semana = matrizItem.semana,
                            requisito = matrizItem.requisito,
                            numTT = matrizItem.numTT,
                            ref1 = matrizItem.ref1,
                            ref2 = matrizItem.ref2,
                            observaciones = matrizItem.observaciones,
                            estado = matrizItem.estado,
                            ubicacion = matrizItem.ubicacion,
                            imagenUrl = matrizItem.imagenUrl,
                            imagenUrl2 = matrizItem.imagenUrl2,
                            fecha = matrizItem.fecha,
                            hora = matrizItem.hora,
                            ruta = matrizItem.ruta,
                            folioP = matrizItem.folioP,
                            origenMatrizId = matrizItem.id,
                            contiene = fila.contiene,
                            capitales = fila.capitales,
                            isDirty = true
                        )
                        paseDao.insertPase(nuevo)
                        agregados++
                    }
                    idsAplicados += matrizItem.id
                }
            }
            onResult("Importación aplicada: $agregados registro(s) agregados a Pase. " + if (yaExistentes > 0) "$yaExistentes ya existían. " else "" + if (noEncontrados > 0) "$noEncontrados sin coincidencia en Matriz." else "")
        } catch (e: Exception) { onResult("No se pudo aplicar: ${e.message}") }
    }

    fun crearRegistro(registro: PaseEntity, onResult: (Boolean) -> Unit = {}) = viewModelScope.launch {
        try { paseDao.insertPase(registro); onResult(true) } catch (_: Exception) { onResult(false) }
    }

    fun actualizarRegistro(registro: PaseEntity, onResult: (Boolean) -> Unit = {}) = viewModelScope.launch {
        try { paseDao.updatePase(registro); onResult(true) } catch (_: Exception) { onResult(false) }
    }

    fun eliminarRegistro(id: String, onResult: (Boolean) -> Unit = {}) = viewModelScope.launch {
        _deleteInProgress.value = true
        try { paseDao.deletePaseById(id); onResult(true) } catch (_: Exception) { onResult(false) } finally { _deleteInProgress.value = false }
    }

    fun buscar(query: String): StateFlow<List<PaseEntity>> = paseDao.buscarPase(query).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun buscarPorFecha(fecha: Long): StateFlow<List<PaseEntity>> = paseDao.buscarPasePorFecha(fecha).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
