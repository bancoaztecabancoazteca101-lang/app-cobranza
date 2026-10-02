package com.example.matrizapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/** Almacén PERMANENTE de fotos de clientes en el teléfono (filesDir, no cache: Android no lo borra).
 * Cada foto se guarda con el nombre = SHA-256 de su origen (URL de Drive, ruta relativa o content://),
 * reducida a [MAX_LADO_PX] px por el lado largo (JPEG [CALIDAD_JPEG]) para no llenar el teléfono:
 * una foto de 3 MB queda en ~300-500 KB y el texto sigue legible. */
class ClientImageStore(private val context: Context) {
    private val root = File(context.filesDir, "matriz_clientes").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("client_image_store", Context.MODE_PRIVATE)

    fun localFile(raw: String?): File? {
        if (raw.isNullOrBlank()) return null
        return File(root, sha256(raw.trim()) + ".jpg")
    }

    private val uriCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Como [localUri] pero recordando el resultado en memoria (para listas con muchas tarjetas). */
    fun localUriCached(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        uriCache[raw]?.let { return it }
        val uri = localUri(raw)?.toString() ?: return null
        uriCache[raw] = uri
        return uri
    }

    fun localUri(raw: String?): Uri? {
        val file = localFile(raw) ?: return null
        return if (file.exists() && file.length() > 0L) Uri.fromFile(file) else null
    }

    /** Garantiza que la foto de [raw] esté guardada localmente (la descarga/copia y la reduce si falta). */
    suspend fun ensureLocal(raw: String?, driveHelper: DriveHelper): File? {
        if (raw.isNullOrBlank()) return null
        val source = raw.trim()
        val destino = localFile(source) ?: return null
        if (destino.exists() && destino.length() > 0L) return destino
        val original = File(root, destino.name + ".orig.part")
        return try {
            original.delete()
            val ok = when {
                source.startsWith("content://") -> copyContentUri(Uri.parse(source), original)
                source.startsWith("file://") -> copyFile(File(Uri.parse(source).path ?: return null), original)
                source.startsWith("http://") || source.startsWith("https://") -> driveHelper.downloadFile(source, original)
                source.contains("/") -> driveHelper.downloadByRelativePath(source, original)
                else -> false
            }
            if (!ok || !original.exists() || original.length() == 0L) null
            else guardarReducida(original, destino)
        } catch (_: Exception) { null }
        finally { original.delete() }
    }

    /** Guarda una foto ya tomada en el teléfono bajo la clave [raw]. */
    fun saveCapturedFile(raw: String?, source: File): File? {
        if (raw.isNullOrBlank() || !source.exists() || source.length() == 0L) return null
        val destino = localFile(raw) ?: return null
        return try { guardarReducida(source, destino) } catch (_: Exception) { null }
    }

    /** Cuando una foto tomada sin red (content://) se sube a Drive y su dirección cambia, la copia
     * local se re-asocia a la dirección nueva para que siga viéndose sin internet (sin volver a bajarla). */
    suspend fun adopt(oldRaw: String?, newRaw: String?, driveHelper: DriveHelper) {
        if (oldRaw.isNullOrBlank() || newRaw.isNullOrBlank() || oldRaw.trim() == newRaw.trim()) return
        val nuevo = localFile(newRaw) ?: return
        if (nuevo.exists() && nuevo.length() > 0L) return
        val viejo = ensureLocal(oldRaw, driveHelper) ?: return
        try {
            if (!viejo.renameTo(nuevo)) { viejo.copyTo(nuevo, overwrite = true); viejo.delete() }
        } catch (_: Exception) { }
    }

    fun count(): Int = root.listFiles()?.count { it.isFile && it.length() > 0L && !it.name.endsWith(".part") } ?: 0

    /** Espacio que ocupan las fotos guardadas, en bytes. */
    fun bytes(): Long = root.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sumOf { it.length() } ?: 0L

    /** Limpieza semanal: borra fotos que ya no pertenecen a ningún registro ([validos] = orígenes vigentes)
     * y restos de descargas a medias. Solo borra lo que lleva más de [MIN_EDAD_MS] guardado, así no
     * toca fotos recién tomadas. Devuelve cuántas borró. */
    fun limpiarHuerfanas(validos: Collection<String?>, forzar: Boolean = false): Int {
        val ahora = System.currentTimeMillis()
        if (!forzar && ahora - prefs.getLong("ultima_limpieza", 0L) < INTERVALO_LIMPIEZA_MS) return 0
        uriCache.clear()
        val nombresValidos = validos.mapNotNull { localFile(it)?.name }.toSet()
        var borradas = 0
        root.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val edad = ahora - f.lastModified()
            val huerfana = if (f.name.endsWith(".part")) edad > 60 * 60 * 1000L else f.name !in nombresValidos && edad > MIN_EDAD_MS
            if (huerfana && f.delete()) borradas++
        }
        prefs.edit().putLong("ultima_limpieza", ahora).apply()
        return borradas
    }

    // ───────── Reducción de tamaño ─────────

    /** Lee [origen], lo reduce (lado largo ≤ MAX_LADO_PX, respetando la rotación EXIF) y lo escribe
     * como JPEG en [destino] de forma atómica. Si no se puede decodificar como imagen, conserva el original. */
    private fun guardarReducida(origen: File, destino: File): File? {
        val temporal = File(root, destino.name + ".part")
        temporal.delete()
        try {
            if (!reducirJpeg(origen, temporal)) {
                temporal.delete()
                origen.inputStream().use { i -> temporal.outputStream().use { o -> i.copyTo(o) } }
            }
            if (temporal.length() == 0L) { temporal.delete(); return null }
            if (destino.exists()) destino.delete()
            if (!temporal.renameTo(destino)) { temporal.copyTo(destino, overwrite = true); temporal.delete() }
            return destino.takeIf { it.exists() && it.length() > 0L }
        } catch (_: Exception) { temporal.delete(); return null }
    }

    private fun reducirJpeg(origen: File, destino: File): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(origen.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            var muestreo = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (muestreo * 2) >= MAX_LADO_PX) muestreo *= 2
            var bmp = BitmapFactory.decodeFile(origen.path, BitmapFactory.Options().apply { inSampleSize = muestreo }) ?: return false
            val lado = maxOf(bmp.width, bmp.height)
            val rotacion = try {
                when (ExifInterface(origen.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (_: Exception) { 0f }
            if (lado > MAX_LADO_PX || rotacion != 0f) {
                val m = Matrix()
                if (lado > MAX_LADO_PX) { val e = MAX_LADO_PX.toFloat() / lado; m.postScale(e, e) }
                if (rotacion != 0f) m.postRotate(rotacion)
                val nuevo = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (nuevo !== bmp) bmp.recycle()
                bmp = nuevo
            }
            val ok = destino.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, CALIDAD_JPEG, it) }
            bmp.recycle()
            ok && destino.length() > 0L
        } catch (_: Exception) { false } catch (_: OutOfMemoryError) { false }
    }

    private fun copyContentUri(uri: Uri, destination: File): Boolean {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return false
            input.use { source -> destination.outputStream().use { output -> source.copyTo(output) } }
            destination.exists() && destination.length() > 0L
        } catch (_: Exception) { false }
    }

    private fun copyFile(source: File, destination: File): Boolean {
        return try {
            if (!source.exists() || source.length() == 0L) return false
            source.inputStream().use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
            destination.exists() && destination.length() > 0L
        } catch (_: Exception) { false }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_LADO_PX = 1600
        const val CALIDAD_JPEG = 85
        private const val MIN_EDAD_MS = 3L * 24 * 60 * 60 * 1000
        private const val INTERVALO_LIMPIEZA_MS = 7L * 24 * 60 * 60 * 1000
    }
}
