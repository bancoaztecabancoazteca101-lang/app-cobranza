package com.example.matrizapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Lectura OCR más rápida:
 *  1) La foto se decodifica YA reducida (lado largo ≤ [MAX_LADO] px). Antes se cargaba la foto completa
 *     (12 MP o más) en memoria solo para leer unas líneas de texto: lento y pesado en equipos modestos.
 *  2) Un solo reconocedor de texto para toda la app. Antes se creaba uno nuevo (con su modelo y su hilo)
 *     en cada lectura y nunca se cerraba. */
object OcrImagen {
    const val MAX_LADO = 1800

    val reconocedor: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Devuelve la imagen lista para ML Kit (reducida y con la rotación EXIF aplicada). Si algo falla,
     *  cae al método original para no romper la lectura. */
    fun cargar(context: Context, uri: Uri): InputImage {
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return InputImage.fromFilePath(context, uri)
            var muestreo = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (muestreo * 2) >= MAX_LADO) muestreo *= 2
            var bmp: Bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = muestreo })
            } ?: return InputImage.fromFilePath(context, uri)
            val rotacion = try {
                resolver.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (_: Exception) { 0f }
            if (rotacion != 0f) {
                val girada = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotacion) }, true)
                if (girada !== bmp) bmp.recycle()
                bmp = girada
            }
            InputImage.fromBitmap(bmp, 0)
        } catch (_: Exception) {
            InputImage.fromFilePath(context, uri)
        } catch (_: OutOfMemoryError) {
            InputImage.fromFilePath(context, uri)
        }
    }
}
