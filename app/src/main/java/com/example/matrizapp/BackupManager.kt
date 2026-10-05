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

        private const val AVISO_BAJADA = "aviso_bajada"

        /** Versión del esquema de la base guardada en el teléfono (PRAGMA user_version), o null si no se pudo leer.
         *  Se abre en solo lectura (así incluye lo que aún está en el WAL). */
        private fun leerVersionBase(dbFile: File): Int? = try {
            android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { it.version }
        } catch (_: Exception) {
            try {
                java.io.RandomAccessFile(dbFile, "r").use { raf -> if (raf.length() < 100) null else { raf.seek(60); raf.readInt() } }
            } catch (_: Exception) { null }
        }

        private fun zipRapido(backup: File, dbFile: File, manifest: String) {
            ZipOutputStream(FileOutputStream(backup)).use { zip ->
                zip.setLevel(java.util.zip.Deflater.BEST_SPEED) // más rápido: esto corre antes de abrir la app
                addStaticFile(zip, dbFile, DB_NAME)
                addStaticIfExists(zip, File(dbFile.parentFile, DB_NAME + "-wal"), DB_NAME + "-wal")
                addStaticIfExists(zip, File(dbFile.parentFile, DB_NAME + "-shm"), DB_NAME + "-shm")
                zip.putNextEntry(ZipEntry("manifest.txt"))
                zip.write(manifest.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }

        /** Se ejecuta ANTES de abrir Room. Solo frena el arranque cuando de verdad hace falta:
         *  - MIGRACIÓN pendiente (la base del teléfono es más vieja que la app): copia de seguridad antes de migrar.
         *  - BAJADA de versión (la base es más NUEVA que la app, p. ej. se instaló un APK anterior): Room no puede
         *    abrirla y la app se cerraría siempre. Se guarda un respaldo y se empieza con una base vacía que se
         *    vuelve a llenar desde Sheets; el respaldo se puede restaurar desde una versión nueva de la app.
         *  - Actualización SIN cambio de esquema (la mayoría): no se hace nada, el arranque no se retrasa. */
        fun backupBeforeDatabaseOpenIfVersionChanged(context: Context): File? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val current = BuildConfig.BUILD_SHA
            val previous = prefs.getString(LAST_APP_VERSION, null)
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists() || dbFile.length() == 0L) {
                prefs.edit().putString(LAST_APP_VERSION, current).apply()
                return null
            }
            val versionDb = leerVersionBase(dbFile)
            val backupDir = File(context.filesDir, "backups").apply { mkdirs() }
            val stamp = System.currentTimeMillis()

            if (versionDb != null && versionDb > DB_VERSION) {
                val backup = File(backupDir, "matriz_" + stamp + "_antes_bajar_version_v" + versionDb + ".zip")
                val ok = runCatching {
                    zipRapido(backup, dbFile, "reason=antes_bajar_version\ndbVersion=" + versionDb + "\nappDbVersion=" + DB_VERSION + "\nappVersion=" + current)
                    backup.length() > 0L
                }.getOrDefault(false)
                if (!ok) { backup.delete(); return null } // sin respaldo no se toca nada
                dbFile.delete()
                File(dbFile.parentFile, DB_NAME + "-wal").delete()
                File(dbFile.parentFile, DB_NAME + "-shm").delete()
                prefs.edit().putString(LAST_APP_VERSION, current).putLong(LAST_LOCAL, stamp)
                    .putString(AVISO_BAJADA, "Se instaló una versión anterior de la app: la base local era más nueva (v" + versionDb + ", esta app usa v" + DB_VERSION + "). Se guardó un respaldo en Backup y los datos se descargan de nuevo desde Sheets. Para recuperar lo que solo estaba en el teléfono, restaura ese respaldo desde la versión nueva.")
                    .apply()
                return backup
            }

            val necesitaRespaldo = (versionDb != null && versionDb < DB_VERSION) || (versionDb == null && previous != current)
            if (!necesitaRespaldo) {
                prefs.edit().putString(LAST_APP_VERSION, current).apply()
                return null
            }
            val backup = File(backupDir, "matriz_" + stamp + "_antes_actualizacion.zip")
            return runCatching {
                zipRapido(backup, dbFile, "reason=antes_actualizacion_" + previous + "\nfromDbVersion=" + versionDb + "\nappVersion=" + current)
                prefs.edit().putString(LAST_APP_VERSION, current).putLong(LAST_LOCAL, stamp).apply()
                backup
            }.getOrNull()
        }

        /** Aviso pendiente de una bajada de versión (se muestra una sola vez en Notificaciones). */
        fun consumirAvisoBajada(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val aviso = prefs.getString(AVISO_BAJADA, null) ?: return null
            prefs.edit().remove(AVISO_BAJADA).apply()
            return aviso
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
            // En Drive solo se conservan los 14 respaldos más recientes (antes crecían sin límite).
            runCatching { driveHelper.pruneBackups(FOLDER_NAME, 14) }
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
            // Red de seguridad: restaurar reemplaza la base actual, así que primero se guarda una copia de ella.
            createBackup("antes_restaurar", uploadDrive = false)
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
            Unit
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
            zip.setLevel(java.util.zip.Deflater.BEST_SPEED)
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
        val todos = backupDir.listFiles { f -> f.isFile && f.extension.equals("zip", true) } ?: return
        val (bajada, normales) = todos.partition { it.name.contains("_antes_bajar_version") }
        normales.sortedByDescending { it.lastModified() }.drop(MAX_LOCAL_BACKUPS).forEach { it.delete() }
        bajada.sortedByDescending { it.lastModified() }.drop(3).forEach { it.delete() }
    }
}
