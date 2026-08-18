package com.example.matrizapp
import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    private val container = (appContext as MainApplication).container
    private val repository = container.repository
    private val driveHelper = container.driveHelper

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            syncMatriz()
            syncPase()
            syncSolicitud()
            syncFiltroFecha()
            Result.success()
        } catch (e: Exception) { Result.retry() }
    }

    private suspend fun syncMatriz() {
        repository.getDirtyMatrizItems().forEach { item ->
            var remoteImg = item.imagenUrl
            if (item.imagenUrl?.startsWith("content://") == true) {
                remoteImg = driveHelper.uploadFile(Uri.parse(item.imagenUrl!!), Constants.FOLDER_IMAGES, "image/jpeg")
            }
            var remoteImg2 = item.imagenUrl2
            if (item.imagenUrl2?.startsWith("content://") == true) {
                remoteImg2 = driveHelper.uploadFile(Uri.parse(item.imagenUrl2!!), Constants.FOLDER_IMAGES, "image/jpeg")
            }
            val idx = repository.findRowIndexById(Constants.SHEET_MATRIZ, item.id, Constants.MatrizCols.COL_ID)
            if (idx != -1) {
                // Registro existente: actualizar solo las columnas editables.
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
                // Registro nuevo creado desde la app: agregar fila al final.
                // Orden de columnas A..P: Nombre,Sem,Req,NumTT,Ref1,Ref2,Obs,Estado,Ubicacion,Imagen,Imagen2,Fecha,Id,Hora,Ruta,FolioP
                repository.appendRow(Constants.SHEET_MATRIZ, listOf(
                    item.nombre, item.semana, item.requisito, item.numTT, item.ref1, item.ref2,
                    item.observaciones, item.estado, item.ubicacion, remoteImg, remoteImg2 ?: "",
                    DateUtils.toSheetsSerial(item.fecha), item.id, item.hora, item.ruta, item.folioP
                ))
            }
            repository.markMatrizAsClean(item.id, remoteImg, remoteImg2)
        }
    }

    private suspend fun syncFiltroFecha() {
        repository.getDirtyFiltroFechaItems().forEach { item ->
            // Filtro Fecha comparte el mismo layout de columnas que Matriz (columna H = Estado,
            // columna M = Id), porque se alimenta de ahí vía Apps Script.
            val idx = repository.findRowIndexById(Constants.SHEET_FILTRO, item.id, "M")
            if (idx != -1) {
                repository.updateSheetCell(Constants.SHEET_FILTRO, "H", idx, item.estado)
            }
            repository.markFiltroFechaAsClean(item.id)
        }
    }

    private suspend fun syncPase() {
        repository.getDirtyPaseItems().forEach { item ->
            val idx = repository.findRowIndexById(Constants.SHEET_PASE, item.id, Constants.PaseCols.COL_ID)
            if (idx != -1) {
                repository.updateSheetCell(Constants.SHEET_PASE, "S", idx, item.estado)
                repository.markPaseAsClean(item.id)
            }
        }
    }

    private suspend fun syncSolicitud() {
        repository.getDirtySolicitudItems().forEach { item ->
            var remoteAudio = item.audioUrl
            if (item.audioUrl?.startsWith("content://") == true || item.audioUrl?.startsWith("file://") == true) {
                remoteAudio = driveHelper.uploadFile(Uri.parse(item.audioUrl!!), Constants.FOLDER_AUDIOS, "audio/mp4")
            }
            var remoteImg = item.imageUrl
            if (item.imageUrl?.startsWith("content://") == true || item.imageUrl?.startsWith("file://") == true) {
                remoteImg = driveHelper.uploadFile(Uri.parse(item.imageUrl!!), Constants.FOLDER_IMAGES, "image/jpeg")
            }
            var remoteImg2 = item.imageUrl2
            if (item.imageUrl2?.startsWith("content://") == true || item.imageUrl2?.startsWith("file://") == true) {
                remoteImg2 = driveHelper.uploadFile(Uri.parse(item.imageUrl2!!), Constants.FOLDER_IMAGES, "image/jpeg")
            }
            var remoteImg3 = item.imageUrl3
            if (item.imageUrl3?.startsWith("content://") == true || item.imageUrl3?.startsWith("file://") == true) {
                remoteImg3 = driveHelper.uploadFile(Uri.parse(item.imageUrl3!!), Constants.FOLDER_IMAGES, "image/jpeg")
            }
            var remoteImg4 = item.imageUrl4
            if (item.imageUrl4?.startsWith("content://") == true || item.imageUrl4?.startsWith("file://") == true) {
                remoteImg4 = driveHelper.uploadFile(Uri.parse(item.imageUrl4!!), Constants.FOLDER_IMAGES, "image/jpeg")
            }
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
                repository.markSolicitudAsClean(item.id, remoteAudio, remoteImg, remoteImg2, remoteImg3, remoteImg4)
            } else {
                // Registro nuevo creado desde la app: agregar fila al final.
                // Orden A..P: Id,Nombre,Numero,Sucursal,Ubicacion,Imagen,Imagen2,NombreRef1,Ref1,NombreRef2,Ref2,Observaciones,Audio,Estado,Imagen3,Imagen4
                repository.appendRow(Constants.SHEET_SOLICITUD, listOf(
                    item.id, item.nombre, item.numero ?: "", item.sucursal ?: "", item.ubicacionRaw ?: "",
                    remoteImg ?: "", remoteImg2 ?: "", item.nombreRef1 ?: "", item.ref1 ?: "",
                    item.nombreRef2 ?: "", item.ref2 ?: "", item.observaciones ?: "", remoteAudio ?: "", item.estado,
                    remoteImg3 ?: "", remoteImg4 ?: ""
                ))
                repository.markSolicitudAsClean(item.id, remoteAudio, remoteImg, remoteImg2, remoteImg3, remoteImg4)
            }
        }
    }
}