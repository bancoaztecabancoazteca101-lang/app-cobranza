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
        // Persistir antes de iniciar la llamada: si Android mata el proceso, la reserva
        // debe seguir existiendo para evitar una llamada duplicada al reintentar.
        if (!ed.putLong(clave, ahora).commit()) return false
        return true
    }

    private const val PREFS_SMS = "numero_sms_guard"
    const val VENTANA_SMS_MS = 30 * 60 * 1000L

    /** Candado EN EL ENVÍO del SMS automático (distinto de `reservar`, que se toma antes de llamar):
     * un mismo número no recibe un segundo SMS automático dentro de 30 min, venga de donde venga
     * (dos corridas seguidas en la cola, bloque + catchup, cliente duplicado en Matriz...). El
     * `reservar` de 15 min no alcanzaba cuando la segunda corrida arrancaba pasada esa ventana. */
    @Synchronized
    fun reservarSms(context: Context, numero: String, ventanaMs: Long = VENTANA_SMS_MS): Boolean {
        val clave = ultimos10Digitos(numero)
        if (clave.isEmpty()) return true
        val prefs = context.getSharedPreferences(PREFS_SMS, Context.MODE_PRIVATE)
        val ahora = System.currentTimeMillis()
        val ultimo = prefs.getLong(clave, 0L)
        if (ultimo != 0L && ahora - ultimo < ventanaMs) return false
        val ed = prefs.edit()
        prefs.all.forEach { (k, v) -> if (v is Long && ahora - v > PODA_MS) ed.remove(k) }
        ed.putLong(clave, ahora).commit() // commit: queda guardado antes de enviar, aunque el proceso muera
        return true
    }

    /**
     * Reserva SMS y devuelve la marca temporal exacta de esta reserva.
     * Permite liberar solo la reserva propia si SmsManager rechaza el envío,
     * sin borrar una reserva más reciente de otro flujo.
     */
    @Synchronized
    fun reservarSmsConMarca(context: Context, numero: String, ventanaMs: Long = VENTANA_SMS_MS): Long? {
        val clave = ultimos10Digitos(numero)
        if (clave.isEmpty()) return null
        val prefs = context.getSharedPreferences(PREFS_SMS, Context.MODE_PRIVATE)
        val ahora = System.currentTimeMillis()
        val ultimo = prefs.getLong(clave, 0L)
        if (ultimo != 0L && ahora - ultimo < ventanaMs) return null
        val ed = prefs.edit()
        prefs.all.forEach { (k, v) -> if (v is Long && ahora - v > PODA_MS) ed.remove(k) }
        if (!ed.putLong(clave, ahora).commit()) return null
        return ahora
    }

    @Synchronized
    fun liberarReservaSms(context: Context, numero: String, marca: Long) {
        val clave = ultimos10Digitos(numero)
        if (clave.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_SMS, Context.MODE_PRIVATE)
        if (prefs.getLong(clave, 0L) == marca) {
            prefs.edit().remove(clave).commit()
        }
    }
}
