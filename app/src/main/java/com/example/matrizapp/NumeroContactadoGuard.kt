package com.example.matrizapp

import android.content.Context

/**
 * Última barrera contra mensajes/llamadas repetidos al MISMO número, sin importar por qué camino
 * llegue la repetición (Worker reiniciado por Android, dos Workers, el mismo cliente duplicado
 * en Matriz, etc.). Guarda en SharedPreferences cuándo se contactó cada número (por sus últimos
 * 10 dígitos) y no deja volver a contactarlo dentro de la ventana. Sobrevive a reinicios del
 * proceso, a diferencia de un Set en memoria.
 */
object NumeroContactadoGuard {
    private const val PREFS = "numero_contactado_guard"
    const val VENTANA_MS = 15 * 60 * 1000L
    private const val PODA_MS = 24 * 60 * 60 * 1000L

    /** true = el número estaba libre y queda reservado ahora; false = ya se contactó hace poco. */
    @Synchronized
    fun reservar(context: Context, numero: String, ventanaMs: Long = VENTANA_MS): Boolean {
        val clave = ultimos10Digitos(numero)
        if (clave.isEmpty()) return true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ahora = System.currentTimeMillis()
        val ultimo = prefs.getLong(clave, 0L)
        if (ultimo != 0L && ahora - ultimo < ventanaMs) return false
        val ed = prefs.edit()
        prefs.all.forEach { (k, v) -> if (v is Long && ahora - v > PODA_MS) ed.remove(k) }
        ed.putLong(clave, ahora).apply()
        return true
    }
}
