package com.example.matrizapp
import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class SyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    private val container = (appContext as MainApplication).container
    private val repository = container.repository
    private val driveHelper = container.driveHelper

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Aislar cada módulo: un registro pendiente con conflicto (por ejemplo, un renombre
        // ambiguo de Pase o un ID ausente en Filtro Fecha) no debe impedir que los demás módulos
        // intenten sincronizarse en este ciclo. Si algún módulo falla, WorkManager reintentará.
        var huboErrores = false
        suspend fun ejecutarModulo(nombre: String, accion: suspend () -> Unit) {
            try {
                accion()
            } catch (e: Exception) {
                huboErrores = true
                android.util.Log.e("SyncWorker", "Falló sincronización del módulo $nombre; se reintentará", e)
            }
        }

        ejecutarModulo("Matriz") { syncMatriz() }
        ejecutarModulo("Pase") { syncPase() }
        ejecutarModulo("Ruta IA") { syncRutaIA() }
        ejecutarModulo("Solicitud") { syncSolicitud() }
        ejecutarModulo("Filtro Fecha") { syncFiltroFecha() }

        // Las fotos se intentan aunque otro módulo haya fallado.
        try {
            ClientImageSyncWorker.enqueue(applicationContext)
        } catch (e: Exception) {
            huboErrores = true
            android.util.Log.e("SyncWorker", "No se pudo programar sincronización de imágenes", e)
        }

        if (huboErrores) Result.retry() else Result.success()
    }

    private suspend fun syncMatriz() {
        repository.getDirtyMatrizItems().forEach { item ->
            var remoteImg = item.imagenUrl
            if (item.imagenUrl?.startsWith("content://") == true) remoteImg = driveHelper.uploadFile(Uri.parse(item.imagenUrl!!), Constants.FOLDER_IMAGES, "image/jpeg")
            var remoteImg2 = item.imagenUrl2
            if (item.imagenUrl2?.startsWith("content://") == true) remoteImg2 = driveHelper.uploadFile(Uri.parse(item.imagenUrl2!!), Constants.FOLDER_IMAGES, "image/jpeg")
            val idx = repository.findRowIndexById(Constants.SHEET_MATRIZ, item.id, Constants.MatrizCols.COL_ID)
            if (idx != -1) {
                repository.updateSheetCell(Constants.SHEET_MATRIZ, "A", idx, item.nombre)
                repository.updateSheetCell(Constants.SHEET_MATRIZ, "B", idx, item.semana)
                repository.updateSheetCell(Constants.SHEET_MATRIZ, "C", idx, item.requisito)
                repository.updateSheetCell(Constants.SHEET_MATRIZ, "D", idx, item.numTT)
                repository.updateSheetCell(Constants.SHEET_MATRIZ, "E", idx, item.ref1)
                repository.updateSheetCell(Constants.SHEET_MATRIZ, "F", idx, item.ref2)
                repository.updateSheetCell(Constants.SHEET_MATRIZ, "G", idx, item.observaciones)
                repository.updateSheetCell(Constants.SHEET_MATRIZ, "H", idx, item.estado)
                repository.updateSheetCell(Constants.SHEET_MATRIZ, "I", idx, item.ubicacion)
                repository.updateSheetCell(Constants.SHEET_MATRIZ, "J", idx, remoteImg)
                repository.updateSheetCell(Constants.SHEET_MATRIZ, "K", idx, remoteImg2)
                repository.updateSheetCell(Constants.SHEET_MATRIZ, "L", idx, DateUtils.toSheetsSerial(item.fecha))
                repository.updateSheetCell(Constants.SHEET_MATRIZ, "N", idx, item.hora)
                repository.updateSheetCell(Constants.SHEET_MATRIZ, "O", idx, item.ruta)
                repository.updateSheetCell(Constants.SHEET_MATRIZ, "P", idx, item.folioP)
            } else {
                repository.appendRow(Constants.SHEET_MATRIZ, listOf(item.nombre, item.semana, item.requisito, item.numTT, item.ref1, item.ref2, item.observaciones, item.estado, item.ubicacion, remoteImg, remoteImg2 ?: "", DateUtils.toSheetsSerial(item.fecha), item.id, item.hora, item.ruta, item.folioP))
            }
            // La foto tomada sin red (content://) ya está guardada en el teléfono: se re-asocia a su
            // dirección nueva de Drive para que siga viéndose sin internet sin volver a descargarla.
            container.clientImageStore.adopt(item.imagenUrl, remoteImg, driveHelper)
            container.clientImageStore.adopt(item.imagenUrl2, remoteImg2, driveHelper)
            repository.markMatrizAsClean(item, remoteImg, remoteImg2)
        }
    }

    private suspend fun syncFiltroFecha() {
        repository.getDirtyFiltroFechaItems().forEach { item ->
            val idx = repository.findRowIndexById(Constants.SHEET_FILTRO, item.id, "M")
            // No marcar como sincronizado si el ID no aparece en Sheets: puede ser un
            // fallo de lectura o una fila que todavía no se ha creado. Mantener el dirty
            // permite reintentar en el siguiente ciclo en lugar de perder el cambio local.
            if (idx == -1) {
                throw IllegalStateException(
                    "No se encontró el registro ${item.id} en ${Constants.SHEET_FILTRO}; se conserva pendiente"
                )
            }

            // updateSheetCell propaga errores de la API; solo limpiamos dirty si ambas
            // escrituras terminan correctamente.
            repository.updateSheetCell(Constants.SHEET_FILTRO, "H", idx, item.estado)
            repository.updateSheetCell(Constants.SHEET_FILTRO, "N", idx, item.hora)
            repository.markFiltroFechaAsClean(item.id)
        }
    }

    private suspend fun syncPase() {
        val operacionesPase = container.database.paseSyncOperationDao()
        // Resolver primero renombres/borrados pendientes para impedir que el upsert cree duplicados.
        operacionesPase.pendientes().forEach { op ->
            when (op.type) {
                "DELETE" -> {
                    val eliminado = repository.deleteRowById(Constants.SHEET_PASE, op.oldId, Constants.PaseCols.COL_ID)
                    if (!eliminado) throw IllegalStateException("No se confirmó la eliminación de Pase para ${op.oldId}")
                    operacionesPase.completar(op.operationKey)
                }
                "RENAME" -> {
                    val nuevoId = op.newId ?: throw IllegalStateException("Renombre de Pase sin ID nuevo")
                    val filaAnterior = repository.findRowIndexById(Constants.SHEET_PASE, op.oldId, Constants.PaseCols.COL_ID)
                    val filaNueva = repository.findRowIndexById(Constants.SHEET_PASE, nuevoId, Constants.PaseCols.COL_ID)
                    if (filaAnterior != -1 && filaNueva != -1) {
                        throw IllegalStateException("No se puede renombrar Pase: ya existe ${nuevoId} en Sheets")
                    }
                    if (filaAnterior != -1) {
                        // Si el ID nuevo existe, la comprobación anterior ya detuvo el flujo.
                        // Solo renombramos cuando la fila anterior sigue presente.
                        if (!repository.renameRowId(Constants.SHEET_PASE, op.oldId, nuevoId, Constants.PaseCols.COL_ID)) {
                            throw IllegalStateException("No se confirmó el renombre de Pase ${op.oldId} a ${nuevoId}")
                        }
                    } else if (filaNueva != -1) {
                        // No se puede demostrar que la fila nueva sea la misma entidad:
                        // pudo ser un renombre ya aplicado antes de una interrupción o una colisión.
                        // Mantener la operación pendiente evita declarar éxito sobre una identidad ambigua.
                        throw IllegalStateException("Renombre de Pase ambiguo: no existe ${op.oldId}, pero ${nuevoId} ya existe en Sheets. Requiere verificación antes de reintentar.")
                    }
                    // Si ambas filas faltan, el registro es local/pending; el upsert posterior
                    // lo creará con el ID nuevo. La operación se completa tras confirmar ese estado.
                    operacionesPase.completar(op.operationKey)
                }
                else -> throw IllegalStateException("Operación de Pase desconocida: ${op.type}")
            }
        }
        repository.updateSheetCell(Constants.SHEET_PASE, Constants.PaseCols.COL_CONTIENE, 1, "CONTIENE")
        repository.updateSheetCell(Constants.SHEET_PASE, Constants.PaseCols.COL_CAPITALES, 1, "CAPITALES")
        repository.getDirtyPaseItems().forEach { item ->
            val idx = repository.findRowIndexById(Constants.SHEET_PASE, item.id, Constants.PaseCols.COL_ID)
            if (idx != -1) {
                repository.updateSheetCell(Constants.SHEET_PASE, "S", idx, item.estado)
                repository.updateSheetCell(Constants.SHEET_PASE, Constants.PaseCols.COL_CONTIENE, idx, formatoMoneda(item.contiene))
                repository.updateSheetCell(Constants.SHEET_PASE, Constants.PaseCols.COL_CAPITALES, idx, formatoMoneda(item.capitales))
                repository.markPaseAsClean(item.id)
            } else {
                repository.appendRow(Constants.SHEET_PASE, listOf(item.id, item.folioP ?: "", item.nombre, item.numTT, "", item.ref1, "", item.ref2, "", "", "", "", item.imagenUrl?.takeUnless { it.startsWith("content://") } ?: "", item.imagenUrl2?.takeUnless { it.startsWith("content://") } ?: "", "", "", item.ubicacion ?: "", "", item.estado, formatoMoneda(item.contiene), formatoMoneda(item.capitales)))
                repository.markPaseAsClean(item.id)
            }
        }
    }

    /** Ruta IA usa un reemplazo completo porque la hoja representa la ruta vigente del día.
     * Si una subida anterior falló o el usuario cambió Visitado/orden sin red, cualquier dirty
     * obliga a reescribir la ruta completa, evitando dejar filas antiguas o estados parciales. */
    private suspend fun syncRutaIA() {
        if (repository.getDirtyRutaIAItems().isEmpty()) return
        val items = repository.getAllRutaIAItems()
        repository.reemplazarRutaIAEnSheet(items)
        items.forEach { repository.markRutaIAAsClean(it.id) }
    }

    private fun formatoMoneda(valor: String?): String {
        val limpio = valor?.trim()?.removePrefix("$")?.replace(" ", "") ?: return ""
        if (limpio.isBlank()) return ""
        val normalizado = when {
            limpio.contains(".") && limpio.contains(",") && limpio.lastIndexOf(',') > limpio.lastIndexOf('.') -> limpio.replace(".", "").replace(",", ".")
            limpio.contains(",") && limpio.substringAfterLast(',').length == 2 -> limpio.replace(".", "").replace(",", ".")
            else -> limpio.replace(",", "")
        }
        val numero = normalizado.toDoubleOrNull() ?: return limpio
        return String.format(Locale.US, "$%,.2f", numero)
    }

    private suspend fun syncSolicitud() {
        repository.getDirtySolicitudItems().forEach { item ->
            var remoteAudio = item.audioUrl
            if (item.audioUrl?.startsWith("content://") == true || item.audioUrl?.startsWith("file://") == true) remoteAudio = driveHelper.uploadFile(Uri.parse(item.audioUrl!!), Constants.FOLDER_AUDIOS, "audio/mp4")
            var remoteImg = item.imageUrl
            if (item.imageUrl?.startsWith("content://") == true || item.imageUrl?.startsWith("file://") == true) remoteImg = driveHelper.uploadFile(Uri.parse(item.imageUrl!!), Constants.FOLDER_IMAGES, "image/jpeg")
            var remoteImg2 = item.imageUrl2
            if (item.imageUrl2?.startsWith("content://") == true || item.imageUrl2?.startsWith("file://") == true) remoteImg2 = driveHelper.uploadFile(Uri.parse(item.imageUrl2!!), Constants.FOLDER_IMAGES, "image/jpeg")
            var remoteImg3 = item.imageUrl3
            if (item.imageUrl3?.startsWith("content://") == true || item.imageUrl3?.startsWith("file://") == true) remoteImg3 = driveHelper.uploadFile(Uri.parse(item.imageUrl3!!), Constants.FOLDER_IMAGES, "image/jpeg")
            var remoteImg4 = item.imageUrl4
            if (item.imageUrl4?.startsWith("content://") == true || item.imageUrl4?.startsWith("file://") == true) remoteImg4 = driveHelper.uploadFile(Uri.parse(item.imageUrl4!!), Constants.FOLDER_IMAGES, "image/jpeg")
            val idx = repository.findRowIndexById(Constants.SHEET_SOLICITUD, item.id, Constants.SolicitudCols.COL_ID)
            if (idx != -1) {
                repository.updateSheetCell(Constants.SHEET_SOLICITUD, "B", idx, item.nombre)
                repository.updateSheetCell(Constants.SHEET_SOLICITUD, "C", idx, item.numero)
                repository.updateSheetCell(Constants.SHEET_SOLICITUD, "D", idx, item.sucursal)
                repository.updateSheetCell(Constants.SHEET_SOLICITUD, "E", idx, item.ubicacionRaw)
                repository.updateSheetCell(Constants.SHEET_SOLICITUD, "F", idx, remoteImg)
                repository.updateSheetCell(Constants.SHEET_SOLICITUD, "G", idx, remoteImg2)
                repository.updateSheetCell(Constants.SHEET_SOLICITUD, "H", idx, item.nombreRef1)
                repository.updateSheetCell(Constants.SHEET_SOLICITUD, "I", idx, item.ref1)
                repository.updateSheetCell(Constants.SHEET_SOLICITUD, "J", idx, item.nombreRef2)
                repository.updateSheetCell(Constants.SHEET_SOLICITUD, "K", idx, item.ref2)
                repository.updateSheetCell(Constants.SHEET_SOLICITUD, "L", idx, item.observaciones)
                repository.updateSheetCell(Constants.SHEET_SOLICITUD, "N", idx, item.estado)
                repository.updateSheetCell(Constants.SHEET_SOLICITUD, "M", idx, remoteAudio)
                repository.updateSheetCell(Constants.SHEET_SOLICITUD, "O", idx, remoteImg3)
                repository.updateSheetCell(Constants.SHEET_SOLICITUD, "P", idx, remoteImg4)
                repository.updateSheetCell(Constants.SHEET_SOLICITUD, "Q", idx, item.gestorAsignado)
                repository.updateSheetCell(Constants.SHEET_SOLICITUD, "R", idx, DateUtils.toSheetsSerial(item.fechaHora))
                repository.markSolicitudAsClean(item.id, remoteAudio, remoteImg, remoteImg2, remoteImg3, remoteImg4)
            } else {
                repository.appendRow(Constants.SHEET_SOLICITUD, listOf(item.id, item.nombre, item.numero ?: "", item.sucursal ?: "", item.ubicacionRaw ?: "", remoteImg ?: "", remoteImg2 ?: "", item.nombreRef1 ?: "", item.ref1 ?: "", item.nombreRef2 ?: "", item.ref2 ?: "", item.observaciones ?: "", remoteAudio ?: "", item.estado, remoteImg3 ?: "", remoteImg4 ?: "", item.gestorAsignado, DateUtils.toSheetsSerial(item.fechaHora)))
                repository.markSolicitudAsClean(item.id, remoteAudio, remoteImg, remoteImg2, remoteImg3, remoteImg4)
            }
        }
    }
}