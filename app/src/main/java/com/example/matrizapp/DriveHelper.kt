package com.example.matrizapp
import android.content.Context
import android.net.Uri
import com.google.api.client.http.InputStreamContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class DriveHelper(private val driveService: Drive, private val context: Context) {
    private val folderIdCache = mutableMapOf<String, String>()

    suspend fun uploadFile(uri: Uri, folderName: String, mimeType: String): String = withContext(Dispatchers.IO) {
        try {
            val folderKey = folderName.trimEnd('/')
            val folderId = folderIdCache[folderKey] ?: findFolderIdByName(folderKey)?.also {
                folderIdCache[folderKey] = it
            } ?: throw IOException("No se encontró la carpeta remota: $folderName")

            val fileMetadata = File().apply {
                name = uri.lastPathSegment ?: "UPLOAD_${System.currentTimeMillis()}"
                parents = listOf(folderId)
            }

            val googleFile = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val mediaContent = InputStreamContent(mimeType, inputStream)
                driveService.files().create(fileMetadata, mediaContent)
                    .setFields("id, webViewLink").execute()
            } ?: throw IOException("No se pudo abrir el archivo local")

            googleFile.webViewLink ?: "https://drive.google.com/open?id=${googleFile.id}"
        } catch (e: Exception) {
            throw IOException("Error en DriveHelper: ${e.message}")
        }
    }

    private fun findFolderIdByName(name: String): String? {
        val query = "name = '$name' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val result = driveService.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute()
        return result.files?.firstOrNull()?.id
    }

    /**
     * Descarga un archivo de Drive a un File local a partir de su URL (webViewLink u
     * "open?id=") o de un fileId directo. Devuelve true si tuvo éxito.
     */
    suspend fun downloadFile(urlOrId: String, destFile: java.io.File): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileId = extractFileId(urlOrId) ?: return@withContext false
            java.io.FileOutputStream(destFile).use { out ->
                driveService.files().get(fileId).executeMediaAndDownloadTo(out)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Descarga un archivo de Drive a partir de una ruta relativa tipo "Carpeta/archivo.jpg"
     * (como las que guarda el pipeline de OCR en la columna IMAGEN de Solicitud: solo el
     * nombre de la carpeta y el archivo, sin URL). Busca la carpeta por nombre y dentro de
     * ella el archivo por nombre exacto.
     */
    suspend fun downloadByRelativePath(relativePath: String, destFile: java.io.File): Boolean = withContext(Dispatchers.IO) {
        try {
            val partes = relativePath.trim('/').split("/")
            if (partes.size < 2) return@withContext false
            val folderName = partes.dropLast(1).joinToString("/")
            val fileName = partes.last()
            val folderId = folderIdCache[folderName] ?: findFolderIdByName(folderName)?.also { folderIdCache[folderName] = it }
                ?: return@withContext false
            val query = "name = '$fileName' and '$folderId' in parents and trashed = false"
            val result = driveService.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute()
            val fileId = result.files?.firstOrNull()?.id ?: return@withContext false
            java.io.FileOutputStream(destFile).use { out ->
                driveService.files().get(fileId).executeMediaAndDownloadTo(out)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun extractFileId(urlOrId: String): String? {
        Regex("[-\\w]{25,}").find(urlOrId)?.let { return it.value }
        return null
    }
}