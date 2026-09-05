package com.example.matrizapp

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** Pase es una copia independiente de Matriz. */
class PaseCarteraViewModel(
    private val paseDao: PaseCarteraDao,
    private val matrizDao: MatrizDao,
    val driveHelper: DriveHelper
) : ViewModel() {

    val paseList: StateFlow<List<PaseEntity>> = paseDao.getAllPase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _deleteInProgress = MutableStateFlow(false)
    val deleteInProgress: StateFlow<Boolean> = _deleteInProgress

    data class ImportResumen(
        val detectadosFlores: Int,
        val coincidenciasMatriz: Int,
        val nuevosPase: Int,
        val filas: List<PaseFotoFila>
    )

    private data class Pendiente(val matrizId: String, val contiene: String?, val capitales: String?)

    /** Importación persistente: los datos GCR de coincidencias se guardan antes de marcar PASE.
     * Así, aunque Room copie la fila a Pase en otro ciclo o la app se cierre, no se pierden
     * CONTIEN/CAPITALES. No se crea una fila Pase directamente en el caso con coincidencia. */
    private val preferencias by lazy {
        // Se inicializa al primer uso mediante el Context recibido en importarFotos.
        mutableMapOf<String, Pendiente>()
    }
    private var preferenciasContext: Context? = null

    fun importarFotos(context: Context, uris: List<Uri>, onResult: (ImportResumen?, String?) -> Unit) {
        viewModelScope.launch {
            try {
                preferenciasContext = context.applicationContext
                val filas = uris.take(8).flatMap { extraerPaseDeFoto(context, it) }.distinctBy { it.cu }
                val matriz = matrizDao.getAllMatriz().stateIn(this).value
                val normalizarCu = { valor: String? -> valor?.trim()?.replace(" ", "")?.uppercase() ?: "" }
                val matches = filas.count { fila ->
                    val cu = normalizarCu(fila.cu)
                    matriz.any { normalizarCu(it.folioP) == cu && cu.isNotBlank() }
                }
                val resumen = ImportResumen(filas.size, matches, filas.size - matches, filas)
                onResult(resumen, null)
            } catch (e: Exception) {
                onResult(null, e.message ?: "No se pudo procesar la foto")
            }
        }
    }

    /** Aplica el resumen confirmado por el usuario. GCR distinto de Flores nunca entra. */
    fun aplicarImportacion(resumen: ImportResumen, onResult: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val matriz = matrizDao.getAllMatriz().stateIn(this).value
                var matches = 0
                var nuevos = 0
                resumen.filas.forEach { fila ->
                    val cu = fila.cu.trim().replace(" ", "").uppercase()
                    val match = matriz.firstOrNull { it.folioP?.trim()?.replace(" ", "")?.uppercase() == cu && cu.isNotBlank() }
                    if (match != null) {
                        preferencias[match.id] = Pendiente(match.id, fila.contiene, fila.capitales)
                        guardarPendiente(match.id, fila.contiene, fila.capitales)
                        matrizDao.marcarComoPase(match.id)
                        matches++
                    } else {
                        paseDao.insertar(
                            PaseEntity(
                                id = UUID.randomUUID().toString(),
                                nombre = fila.nombre,
                                semana = "",
                                requisito = "",
                                numTT = "",
                                ref1 = "",
                                ref2 = "",
                                observaciones = null,
                                estado = "PASE",
                                ubicacion = null,
                                imagenUrl = null,
                                imagenUrl2 = null,
                                fecha = System.currentTimeMillis(),
                                hora = null,
                                ruta = null,
                                folioP = fila.cu,
                                origenMatrizId = "IMPORT_FOTO:${fila.cu}:${UUID.randomUUID()}",
                                contiene = fila.contiene,
                                capitales = fila.capitales,
                                isDirty = true
                            )
                        )
                        nuevos++
                    }
                }
                onResult("Importación aplicada: $matches coincidencia(s) con Matriz y $nuevos registro(s) nuevo(s) en Pase.")
            } catch (e: Exception) {
                onResult("No se pudo aplicar la importación: ${e.message}")
            }
        }
    }

    /** Se llama al abrir Pase y cada vez que la lista cambia. Si la copia Matriz→Pase ya existe,
     * adjunta los campos OCR y elimina el pendiente persistente. */
    fun procesarPendientes() {
        viewModelScope.launch {
            val prefs = preferenciasContext ?: return@launch
            val all = leerPendientes(prefs)
            if (all.isEmpty()) return@launch
            all.forEach { pendiente ->
                val pase = paseDao.getByOrigenMatrizId(pendiente.matrizId) ?: return@forEach
                paseDao.updateCamposGcr(pase.id, pendiente.contiene, pendiente.capitales)
                eliminarPendiente(prefs, pendiente.matrizId)
                preferencias.remove(pendiente.matrizId)
            }
        }
    }

    private fun guardarPendiente(matrizId: String, contiene: String?, capitales: String?) {
        val ctx = preferenciasContext ?: return
        val p = ctx.getSharedPreferences("pase_import_gcr", Context.MODE_PRIVATE)
        p.edit().putString(matrizId, "${contiene ?: ""}\u001F${capitales ?: ""}").apply()
    }

    private fun leerPendientes(ctx: Context): List<Pendiente> {
        val p = ctx.getSharedPreferences("pase_import_gcr", Context.MODE_PRIVATE)
        return p.all.mapNotNull { (id, raw) ->
            val partes = raw?.toString()?.split("\u001F", limit = 2) ?: return@mapNotNull null
            Pendiente(id, partes.getOrNull(0)?.ifBlank { null }, partes.getOrNull(1)?.ifBlank { null })
        }
    }

    private fun eliminarPendiente(ctx: Context, matrizId: String) {
        ctx.getSharedPreferences("pase_import_gcr", Context.MODE_PRIVATE).edit().remove(matrizId).apply()
    }

    fun cambiarIdYGuardar(
        idAnterior: String, idNuevo: String, nombre: String, semana: String, requisito: String, numTT: String,
        ref1: String, ref2: String, observaciones: String?, estado: String, ubicacion: String?,
        fecha: Long?, hora: String?, ruta: String?, folioP: String?,
        onResult: (exito: Boolean, error: String?) -> Unit
    ) {
        viewModelScope.launch {
            val existente = paseList.value.find { it.id == idAnterior }
            if (existente == null) { onResult(false, "El registro ya no existe"); return@launch }
            paseDao.actualizar(existente.copy(
                nombre = nombre, semana = semana, requisito = requisito, numTT = numTT,
                ref1 = ref1, ref2 = ref2, observaciones = observaciones, estado = estado,
                ubicacion = ubicacion, fecha = fecha, hora = hora, ruta = ruta, folioP = folioP,
                isDirty = true
            ))
            onResult(true, null)
        }
    }

    fun crearRegistro(
        id: String, nombre: String, semana: String, requisito: String, numTT: String,
        ref1: String, ref2: String, observaciones: String?, estado: String, ubicacion: String?,
        fecha: Long, hora: String?, ruta: String?, folioP: String?
    ) {
        val idFinal = id.trim().ifBlank { UUID.randomUUID().toString().replace("-", "").take(12) }
        viewModelScope.launch {
            paseDao.insertar(PaseEntity(
                id = idFinal, nombre = nombre, semana = semana, requisito = requisito, numTT = numTT,
                ref1 = ref1, ref2 = ref2, observaciones = observaciones, estado = estado,
                ubicacion = ubicacion, imagenUrl = null, imagenUrl2 = null, fecha = fecha,
                hora = hora, ruta = ruta, folioP = folioP, origenMatrizId = "", isDirty = true
            ))
        }
    }

    fun eliminarRegistro(id: String, onResult: (exito: Boolean, error: String?) -> Unit) {
        viewModelScope.launch {
            _deleteInProgress.value = true
            paseDao.eliminar(id)
            _deleteInProgress.value = false
            onResult(true, null)
        }
    }
}
