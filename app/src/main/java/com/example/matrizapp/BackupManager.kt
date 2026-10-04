package com.example.matrizapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class BackupInfo(val file: File, val createdAt: Long, val reason: String, val uploadedToDrive: Boolean)

class BackupManager(private val context: Context, private val database: AppDatabase, private val driveHelper: DriveHelper) {
    companion object {
        private const val DB_NAME = "matriz_database"
        private const val PREFS = "matriz_backup"
        private const val LAST_LOCAL = "last_local"
        private const val LAST_DRIVE = "last_drive"
        private const val LAST_APP_VERSION = "last_app_version"
        private const val MAX_LOCAL_BACKUPS = 7
        const val FOLDER_NAME = "MATRIZ_BACKUPS"

        fun backupBeforeDatabaseOpenIfVersionChanged(context: Context): File? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val current = BuildConfig.BUILD_SHA
            val previous = prefs.getString(LAST_APP_VERSION, null)
            if (previous == null) {
                prefs.edit().putString(LAST_APP_VERSION, current).apply()
                return null
            }
            if (previous == current) return null
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists() || dbFile.length() == 0L) {
                prefs.edit().putString(LAST_APP_VERSION, current).apply()
                return null
            }
            val backupDir = File(context.filesDir, "backups").apply { mkdirs() }
            val stamp = System.currentTimeMillis()
            val backup = File(backupDir, "matriz_" + stamp + "_antes_actualizacion.zip")
            return runCatching {
                ZipOutputStream(FileOutputStream(backup)).use { zip ->
                    addStaticFile(zip, dbFile, DB_NAME)
                    addStaticIfExists(zip, File(dbFile.parentFile, DB_NAME + "-wal"), DB_NAME + "-wal")
                    addStaticIfExists(zip, File(dbFile.parentFile, DB_NAME + "-shm"), DB_NAME + "-shm")
                    zip.putNextEntry(ZipEntry("manifest.txt"))
                    zip.write(("reason=antes_actualizacion_" + previous + "\nappVersion=" + current).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                prefs.edit().putString(LAST_APP_VERSION, current).putLong(LAST_LOCAL, stamp).apply()
                backup
            }.getOrNull()
        }

        private fun addStaticFile(zip: ZipOutputStream, file: File, entryName: String) {
            require(file.exists())
            zip.putNextEntry(ZipEntry(entryName))
            FileInputStream(file).use { it.copyTo(zip) }
            zip.closeEntry()
        }

        private fun addStaticIfExists(zip: ZipOutputStream, file: File, entryName: String) {
            if (file.exists() && file.length() > 0L) addStaticFile(zip, file, entryName)
        }
    }

    private val backupDir = File(context.filesDir, "backups").apply { mkdirs() }

    suspend fun createBackup(reason: String, uploadDrive: Boolean = true): BackupInfo = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val safeReason = reason.replace(Regex("[^A-Za-z0-9_-]"), "_").take(30).ifBlank { "manual" }
        val backup = File(backupDir, "matriz_" + now + "_" + safeReason + ".zip")
        checkpointDatabase()
        zipDatabase(backup, reason, now)
        var uploaded = false
        if (uploadDrive) uploaded = runCatching {
            driveHelper.uploadLocalFile(backup, FOLDER_NAME, "application/zip")
            true
        }.getOrDefault(false)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(LAST_LOCAL, now).apply()
        if (uploaded) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(LAST_DRIVE, now).apply()
        pruneLocalBackups()
        BackupInfo(backup, now, reason, uploaded)
    }

    suspend fun createAutomaticBackup(reason: String = "automatico"): BackupInfo = createBackup(reason, true)
    suspend fun createManualBackup(): BackupInfo = createBackup("manual", true)

    suspend fun uploadPendingPreUpdateBackup() = withContext(Dispatchers.IO) {
        val pending = backupDir.listFiles { f -> f.isFile && f.name.contains("_antes_actualizacion.") }
            ?.maxByOrNull { it.lastModified() } ?: return@withContext
        try {
            driveHelper.uploadLocalFile(pending, FOLDER_NAME, "application/zip")
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(LAST_DRIVE, pending.lastModified()).apply()
        } catch (_: Exception) {
        }
    }

    fun listLocalBackups(): List<BackupInfo> =
        backupDir.listFiles { f -> f.isFile && f.extension.equals("zip", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { BackupInfo(it, it.lastModified(), it.name.substringAfterLast("_").removeSuffix(".zip"), it.lastModified() == lastDriveTime()) }
            .orEmpty()

    suspend fun restoreLocalBackup(file: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.exists()) { "No existe el respaldo seleccionado." }
            val tempDir = File(context.cacheDir, "backup_restore_" + System.currentTimeMillis()).apply { mkdirs() }
            ZipFile(file).use { zip ->
                zip.getEntry(DB_NAME)?.let { entry ->
                    File(tempDir, DB_NAME).outputStream().use { out -> zip.getInputStream(entry).use { it.copyTo(out) } }
                } ?: error("El respaldo no contiene la base de datos.")
                listOf(DB_NAME + "-wal", DB_NAME + "-shm").forEach { name ->
                    zip.getEntry(name)?.let { entry ->
                        File(tempDir, name).outputStream().use { out -> zip.getInputStream(entry).use { it.copyTo(out) } }
                    }
                }
            }
            database.close()
            val dbFile = context.getDatabasePath(DB_NAME)
            File(dbFile.parentFile, DB_NAME + "-wal").delete()
            File(dbFile.parentFile, DB_NAME + "-shm").delete()
            FileInputStream(File(tempDir, DB_NAME)).use { input -> FileOutputStream(dbFile).use { input.copyTo(it) } }
            listOf(DB_NAME + "-wal", DB_NAME + "-shm").forEach { name ->
                val src = File(tempDir, name)
                if (src.exists()) FileInputStream(src).use { input -> FileOutputStream(File(dbFile.parentFile, name)).use { input.copyTo(it) } }
            }
            tempDir.deleteRecursively()
        }
    }

    fun lastLocalTime(): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_LOCAL, 0L)
    fun lastDriveTime(): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_DRIVE, 0L)

    fun currentAppVersion(): String = BuildConfig.BUILD_SHA

    private fun checkpointDatabase() { runCatching { database.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)") } }

    private fun zipDatabase(target: File, reason: String, now: Long) {
        zipRawDatabase(target, context.getDatabasePath(DB_NAME), reason, now)
    }

    private fun zipRawDatabase(target: File, dbFile: File, reason: String, now: Long = System.currentTimeMillis()) {
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            addFile(zip, dbFile, DB_NAME)
            addIfExists(zip, File(dbFile.parentFile, DB_NAME + "-wal"), DB_NAME + "-wal")
            addIfExists(zip, File(dbFile.parentFile, DB_NAME + "-shm"), DB_NAME + "-shm")
            val manifest = "createdAt=" + now + "\nreason=" + reason + "\nappVersion=" + currentAppVersion() + "\ndatabase=" + DB_NAME + "\n"
            zip.putNextEntry(ZipEntry("manifest.txt"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, entryName: String) {
        require(file.exists()) { "No existe la base de datos local." }
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun addIfExists(zip: ZipOutputStream, file: File, entryName: String) {
        if (file.exists() && file.length() > 0L) addFile(zip, file, entryName)
    }

    private fun pruneLocalBackups() {
        backupDir.listFiles { f -> f.isFile && f.extension.equals("zip", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_LOCAL_BACKUPS)
            ?.forEach { it.delete() }
    }
}
