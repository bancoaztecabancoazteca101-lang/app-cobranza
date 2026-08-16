package com.example.matrizapp
import java.util.TimeZone
object DateUtils {
    private const val SHEETS_EPOCH_OFFSET_DAYS = 25569.0
    private const val MILLIS_PER_DAY = 86400000.0
    fun sheetsSerialToEpochMillis(serial: Double): Long {
        if (serial <= 0.0) return 0L
        val localMillis = ((serial - SHEETS_EPOCH_OFFSET_DAYS) * MILLIS_PER_DAY).toLong()
        return localMillis - TimeZone.getDefault().getOffset(localMillis)
    }
    fun toSheetsSerial(millis: Long?): Double {
        if (millis == null || millis == 0L) return 0.0
        val offset = TimeZone.getDefault().getOffset(millis)
        return ((millis + offset) / MILLIS_PER_DAY) + SHEETS_EPOCH_OFFSET_DAYS
    }

    private val textFormats = listOf(
        "d/M/yyyy H:mm:ss", "d/M/yyyy", "M/d/yyyy H:mm:ss", "M/d/yyyy", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"
    )
    /** Convierte una celda de fecha leída como texto formateado (ej. "8/8/2026 10:27:54") a millis epoch. */
    fun parseCellDateToEpochMillis(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        text.toDoubleOrNull()?.let { return sheetsSerialToEpochMillis(it) }
        for (pattern in textFormats) {
            try {
                val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale("es", "MX"))
                sdf.isLenient = false
                return sdf.parse(text)?.time
            } catch (e: Exception) { /* probar siguiente formato */ }
        }
        return null
    }
}