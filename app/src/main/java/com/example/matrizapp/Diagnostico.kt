package com.example.matrizapp

/** Una diferencia encontrada entre el Google Sheet "Matriz " (fuente maestra) y la copia local
 * en Room, para un registro puntual. Es solo diagnóstico -- no se corrige sola. Los registros
 * con isDirty=true (cambio local aún no subido) se excluyen del chequeo a propósito: es normal
 * que no coincidan todavía con el Sheet. */
data class Discrepancia(val id: String, val nombre: String, val tipo: TipoDiscrepancia, val detalle: String)

enum class TipoDiscrepancia(val etiqueta: String) {
    FALTA_EN_APP("Está en Sheets pero no en la app"),
    FALTA_EN_SHEET("Está en la app pero ya no en Sheets"),
    DATOS_DIFERENTES("Los datos no coinciden")
}

/** Un dispositivo que ha reportado su versión de la app en la hoja "Dispositivos". */
data class DispositivoInfo(
    val androidId: String,
    val modelo: String,
    val buildId: String,
    val ultimoReporte: Long?
)
