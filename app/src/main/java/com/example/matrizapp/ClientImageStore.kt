package com.example.matrizapp

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

class ClientImageStore(context: Context) {
    private val root = File(context.filesDir, "matriz_clientes").apply { mkdirs() }

    fun localFile(raw: String?): File? {
        if (raw.isNullOrBlank()) return null
        return File(root, sha256(raw.trim()) + ".jpg")
    }

    fun localUri(raw: String?): Uri? {
        val file = localFile(raw) ?: return null
        return if (file.exists() && file.length() > 0L) Uri.fromFile(file) else null
    }

    suspend fun ensureLocal(raw: String?, driveHelper: DriveHelper): File? {
        if (raw.isNullOrBlank()) return null
        val source = raw.trim()
        val destino = localFile(source) ?: return null
        if (destino.exists() && destino.length() > 0L) return destino
        val temporal = File(root, destino.name + ".part")
        return try {
            temporal.delete()
            val ok = when {
                source.startsWith("http://") || source.startsWith("https://") -> driveHelper.downloadFile(source, temporal)
                source.contains("/") -> driveHelper.downloadByRelativePath(source, temporal)
                else -> false
            }
            if (!ok || !temporal.exists() || temporal.length() == 0L) { temporal.delete(); null }
            else {
                if (destino.exists()) destino.delete()
                if (!temporal.renameTo(destino)) { temporal.copyTo(destino, overwrite = true); temporal.delete() }
                destino.takeIf { it.exists() && it.length() > 0L }
            }
        } catch (_: Exception) { temporal.delete(); null }
    }

    fun saveCapturedFile(raw: String?, source: File): File? {
        if (raw.isNullOrBlank() || !source.exists() || source.length() == 0L) return null
        val destino = localFile(raw) ?: return null
        val temporal = File(root, destino.name + ".part")
        return try {
            temporal.delete()
            source.inputStream().use { input -> temporal.outputStream().use { output -> input.copyTo(output) } }
            if (destino.exists()) destino.delete()
            if (!temporal.renameTo(destino)) { temporal.copyTo(destino, overwrite = true); temporal.delete() }
            destino.takeIf { it.exists() && it.length() > 0L }
        } catch (_: Exception) { temporal.delete(); null }
    }

    fun count(): Int = root.listFiles()?.count { it.isFile && it.length() > 0L && !it.name.endsWith(".part") } ?: 0

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
