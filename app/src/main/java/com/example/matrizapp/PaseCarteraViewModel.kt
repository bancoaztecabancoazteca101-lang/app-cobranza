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
import java.util.UUID

class PaseCarteraViewModel(private val paseDao: PaseCarteraDao, private val matrizDao: MatrizDao, val driveHelper: DriveHelper) : ViewModel() {
    val paseList: StateFlow<List<PaseEntity>> = paseDao.getAllPase().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _deleteInProgress = MutableStateFlow(false)
    val deleteInProgress: StateFlow<Boolean> = _deleteInProgress
    data class ImportResumen(val detectadosFlores: Int, val coincidenciasMatriz: Int, val nuevosPase: Int, val filas: List<PaseFotoFila>)
    private var preferenciasContext: Context? = null
    fun importarFotos(context: Context, uris: List<Uri>, onResult: (ImportResumen?, String?) -> Unit) = viewModelScope.launch {
        try {
            preferenciasContext = context.applicationContext
            val filas = uris.take(8).flatMap { extraerPaseDeFoto(context, it) }.distinctBy { normalizarCuPase(it.cu) }
            val matriz = matrizDao.getAllMatriz().first()
            val matches = filas.count { fila -> val cu = normalizarCuPase(fila.cu); matriz.any { normalizarCuPase(it.folioP) == cu && cu.isNotBlank() } }
            onResult(ImportResumen(filas.size, matches, filas.size - matches, filas), null)
        } catch (e: Exception) { onResult(null, e.message ?: "No se pudo procesar el reporte") }
    }
    fun aplicarImportacion(resumen: ImportResumen, onResult: (String) -> Unit) = viewModelScope.launch {
        try {
            val matriz = matrizDao.getAllMatriz().first(); var matches = 0; var nuevos = 0
            resumen.filas.forEach { fila ->
                val cu = normalizarCuPase(fila.cu)
                val match = matriz.firstOrNull { normalizarCuPase(it.folioP) == cu && cu.isNotBlank() }
                if (match != null) { guardarPendiente(match.id, fila.contiene, fila.capitales); matrizDao.marcarComoPase(match.id); matches++ }
                else { paseDao.insertar(PaseEntity(UUID.randomUUID().toString(), fila.nombre, "", "", "", "", "", null, "PASE", null, null, null, System.currentTimeMillis(), null, null, fila.cu, "IMPORT_FOTO:${fila.cu}:${UUID.randomUUID()}", fila.contiene, fila.capitales, true)); nuevos++ }
            }
            onResult("Importación aplicada: $matches coincidencia(s) con Matriz y $nuevos registro(s) nuevo(s) en Pase.")
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
    fun actualizarCamposGcr(id: String, contiene: String?, capitales: String?, onResult: (String?) -> Unit) = viewModelScope.launch { try { paseDao.updateCamposGcr(id, contiene, capitales); onResult(null) } catch (e: Exception) { onResult(e.message ?: "No se pudo guardar") } }
    fun cambiarIdYGuardar(idAnterior: String, idNuevo: String, nombre: String, semana: String, requisito: String, numTT: String, ref1: String, ref2: String, observaciones: String?, estado: String, ubicacion: String?, fecha: Long?, hora: String?, ruta: String?, folioP: String?, onResult: (Boolean, String?) -> Unit) = viewModelScope.launch { val existente = paseList.value.find { it.id == idAnterior }; if (existente == null) { onResult(false, "El registro ya no existe"); return@launch }; paseDao.actualizar(existente.copy(nombre = nombre, semana = semana, requisito = requisito, numTT = numTT, ref1 = ref1, ref2 = ref2, observaciones = observaciones, estado = estado, ubicacion = ubicacion, fecha = fecha, hora = hora, ruta = ruta, folioP = folioP, isDirty = true)); onResult(true, null) }
    fun crearRegistro(id: String, nombre: String, semana: String, requisito: String, numTT: String, ref1: String, ref2: String, observaciones: String?, estado: String, ubicacion: String?, fecha: Long, hora: String?, ruta: String?, folioP: String?) { val idFinal = id.trim().ifBlank { UUID.randomUUID().toString().replace("-", "").take(12) }; viewModelScope.launch { paseDao.insertar(PaseEntity(idFinal, nombre, semana, requisito, numTT, ref1, ref2, observaciones, estado, ubicacion, null, null, fecha, hora, ruta, folioP, "", isDirty = true)) } }
    fun eliminarRegistro(id: String, onResult: (Boolean, String?) -> Unit) = viewModelScope.launch { _deleteInProgress.value = true; paseDao.eliminar(id); _deleteInProgress.value = false; onResult(true, null) }
}
