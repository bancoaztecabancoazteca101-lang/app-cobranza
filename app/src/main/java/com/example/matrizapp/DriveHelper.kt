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
            val folderId = folderIdCache[folderKey] ?: findFolderIdByName(folderKey)?.also { folderIdCache[folderKey] = it }
                ?: throw IOException("No se encontró la carpeta remota: $folderName")
            val fileMetadata = File().apply {
                name = uri.lastPathSegment ?: "UPLOAD_${System.currentTimeMillis()}"
                parents = listOf(folderId)
            }
            val googleFile = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                driveService.files().create(fileMetadata, InputStreamContent(mimeType, inputStream))
                    .setFields("id, webViewLink").execute()
            } ?: throw IOException("No se pudo abrir el archivo local")
            googleFile.webViewLink ?: "https://drive.google.com/file/d/${googleFile.id}/view"
        } catch (e: Exception) {
            throw IOException("Error en DriveHelper: ${e.message}")
        }
    }

    private fun findFolderIdByName(name: String): String? {
        val query = "name = '${name.replace("'", "\\'")}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val result = driveService.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute()
        return result.files?.firstOrNull()?.id
    }

    suspend fun findImageUrl(rawPath: String?): String? = withContext(Dispatchers.IO) {
        if (rawPath.isNullOrBlank()) return@withContext null
        if (rawPath.startsWith("http://") || rawPath.startsWith("https://")) return@withContext rawPath
        if (!rawPath.contains("/")) return@withContext null
        try {
            val partes = rawPath.trim('/').split("/")
            if (partes.isEmpty()) return@withContext null
            val fileName = partes.last()
            val escapedFileName = fileName.replace("'", "\\'")
            val folderName = partes.dropLast(1).joinToString("/")
            val folderId = folderIdCache[folderName] ?: findFolderIdByName(folderName)?.also { folderIdCache[folderName] = it }
            val file = if (folderId != null) {
                val q = "name = '$escapedFileName' and '$folderId' in parents and trashed = false"
                driveService.files().list().setQ(q).setSpaces("drive").setFields("files(id,webViewLink,name)").execute().files?.firstOrNull()
            } else null
            val resolved = file ?: run {
                val q = "name = '$escapedFileName' and trashed = false"
                driveService.files().list().setQ(q).setSpaces("drive").setFields("files(id,webViewLink,name)").execute().files?.firstOrNull()
            } ?: return@withContext null
            resolved.webViewLink ?: "https://drive.google.com/file/d/${resolved.id}/view"
        } catch (_: Exception) {
            null
        }
    }

    suspend fun downloadFile(urlOrId: String, destFile: java.io.File): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileId = extractFileId(urlOrId) ?: return@withContext false
            java.io.FileOutputStream(destFile).use { out -> driveService.files().get(fileId).executeMediaAndDownloadTo(out) }
            destFile.exists() && destFile.length() > 0L
        } catch (_: Exception) { false }
    }

    suspend fun downloadByRelativePath(relativePath: String, destFile: java.io.File): Boolean = withContext(Dispatchers.IO) {
        try {
            val partes = relativePath.trim('/').split("/")
            if (partes.size < 2) return@withContext false
            val folderName = partes.dropLast(1).joinToString("/")
            val fileName = partes.last()
            val escapedFileName = fileName.replace("'", "\\'")
            val folderId = folderIdCache[folderName] ?: findFolderIdByName(folderName)?.also { folderIdCache[folderName] = it }
            val fileId = if (folderId != null) {
                val query = "name = '$escapedFileName' and '$folderId' in parents and trashed = false"
                driveService.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute().files?.firstOrNull()?.id
            } else null
            val resolvedFileId = fileId ?: run {
                val query = "name = '$escapedFileName' and trashed = false"
                driveService.files().list().setQ(query).setSpaces("drive").setFields("files(id)").execute().files?.firstOrNull()?.id
            } ?: return@withContext false
            java.io.FileOutputStream(destFile).use { out -> driveService.files().get(resolvedFileId).executeMediaAndDownloadTo(out) }
            destFile.exists() && destFile.length() > 0L
        } catch (_: Exception) { false }
    }

    private fun extractFileId(urlOrId: String): String? = Regex("[-\\w]{25,}").find(urlOrId)?.value
}