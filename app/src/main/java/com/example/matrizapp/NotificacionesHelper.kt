package com.example.matrizapp
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

private const val CANAL_RETORNOS = "retornos_filtro_fecha"
private const val PREFS_RETORNOS = "retornos_programados"

/** Programa una notificación local a la hora puesta para cada registro de Filtro Fecha que
 * esté marcado como "Retorno", y las cancela cuando dejan de aplicar (cambian de estado, se
 * eliminan, o ya pasó su hora). No requiere conexión ni servidor: todo corre en el dispositivo
 * vía AlarmManager. */
class NotificacionesHelper(private val context: Context) {
    init { crearCanal() }

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CANAL_RETORNOS) == null) {
                val canal = NotificationChannel(CANAL_RETORNOS, "Retornos programados", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Avisa a la hora puesta cuando un cliente en Filtro Fecha está marcado como Retorno"
                }
                nm.createNotificationChannel(canal)
            }
        }
    }

    /** Evalúa (sin programar nada todavía) qué pasaría con este registro específico, para
     * mostrarle al usuario un mensaje claro justo al guardar (en vez de que tenga que adivinar
     * si la notificación quedó programada). La programación real la hace
     * sincronizarAlarmasRetorno, que se dispara solo automáticamente al cambiar los datos. */
    fun evaluarProgramacion(estado: String, hora: String?): String? {
        if (!estado.equals("Retorno", ignoreCase = true)) return null
        if (hora.isNullOrBlank()) return "Marca \"Retorno\" y pon una Hora para programar el aviso"
        val trigger = calcularTriggerHoy(hora) ?: return "No se pudo interpretar la hora"
        val ahora = System.currentTimeMillis()
        return if (trigger <= ahora) {
            "Esa hora ya pasó — no se programó notificación"
        } else {
            val df = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            "Notificación programada para las ${df.format(java.util.Date(trigger))}"
        }
    }

    /** Se debe llamar cada vez que se actualiza la lista completa de Filtro Fecha (no la
     * filtrada por rango de fechas, sino todos los registros), para mantener las alarmas
     * sincronizadas con el estado actual de cada uno. */
    fun sincronizarAlarmasRetorno(items: List<FiltroFechaEntity>) {
        val prefs = context.getSharedPreferences(PREFS_RETORNOS, Context.MODE_PRIVATE)
        val idsAnteriores = prefs.getStringSet("ids", emptySet()) ?: emptySet()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val ahora = System.currentTimeMillis()

        val objetivos = items.filter { it.estado.equals("Retorno", ignoreCase = true) && !it.hora.isNullOrBlank() }
        val idsNuevos = mutableSetOf<String>()

        for (item in objetivos) {
            val trigger = calcularTriggerHoy(item.hora!!) ?: continue
            if (trigger <= ahora) continue // ya pasó esa hora hoy: no tiene caso programarla
            idsNuevos.add(item.id)
            programarAlarma(alarmManager, item, trigger)
        }

        // Cancela las alarmas de IDs que quedaron fuera del set nuevo (cambiaron de estado,
        // se eliminaron, o su hora ya pasó).
        for (idViejo in idsAnteriores) {
            if (idViejo !in idsNuevos) {
                alarmManager.cancel(crearPendingIntent(idViejo, "", null))
            }
        }

        prefs.edit().putStringSet("ids", idsNuevos).apply()
    }

    /** Igual que sincronizarAlarmasRetorno, pero para registros de Matriz. Solo considera los
     * que tienen Fecha de HOY: así el usuario puede registrar la visita directo en Matriz (sin
     * tener que ir también a editar Filtro Fecha) y le llega el aviso igual, pero sin arriesgarse
     * a que se disparen de golpe decenas de "Retorno" viejos del historial al sincronizar. */
    fun sincronizarAlarmasRetornoMatriz(items: List<MatrizEntity>) {
        val prefs = context.getSharedPreferences(PREFS_RETORNOS, Context.MODE_PRIVATE)
        val idsAnteriores = prefs.getStringSet("ids_matriz", emptySet()) ?: emptySet()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val ahora = System.currentTimeMillis()
        val hoyCal = java.util.Calendar.getInstance()

        val objetivos = items.filter { item ->
            item.estado.equals("Retorno", ignoreCase = true) &&
                !item.hora.isNullOrBlank() &&
                item.fecha != null && esHoy(item.fecha!!, hoyCal)
        }
        val idsNuevos = mutableSetOf<String>()

        for (item in objetivos) {
            val trigger = calcularTriggerHoy(item.hora!!) ?: continue
            if (trigger <= ahora) continue
            // Prefijo "matriz_" para que nunca choque con un ID de Filtro Fecha idéntico.
            val idPrefijado = "matriz_${item.id}"
            idsNuevos.add(idPrefijado)
            programarAlarmaGenerica(alarmManager, crearPendingIntent(idPrefijado, item.nombre, item.numTT), trigger)
        }

        for (idViejo in idsAnteriores) {
            if (idViejo !in idsNuevos) {
                alarmManager.cancel(crearPendingIntent(idViejo, "", null))
            }
        }

        prefs.edit().putStringSet("ids_matriz", idsNuevos).apply()
    }

    private fun esHoy(fechaMillis: Long, hoyCal: java.util.Calendar): Boolean {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = fechaMillis }
        return cal.get(java.util.Calendar.YEAR) == hoyCal.get(java.util.Calendar.YEAR) &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == hoyCal.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun programarAlarma(alarmManager: AlarmManager, item: FiltroFechaEntity, trigger: Long) {
        programarAlarmaGenerica(alarmManager, crearPendingIntent(item.id, item.nombre, item.numTT), trigger)
    }

    private fun programarAlarmaGenerica(alarmManager: AlarmManager, pendingIntent: PendingIntent, trigger: Long) {
        val puedeExacta = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true
        try {
            if (puedeExacta) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
        }
    }

    /** Interpreta "hora" en formato "HH:mm:ss" o "HH:mm" y arma el timestamp de HOY a esa hora. */
    private fun calcularTriggerHoy(hora: String): Long? {
        val partes = hora.split(":").mapNotNull { it.trim().toIntOrNull() }
        if (partes.size < 2) return null
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, partes[0])
        cal.set(java.util.Calendar.MINUTE, partes[1])
        cal.set(java.util.Calendar.SECOND, if (partes.size > 2) partes[2] else 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun crearPendingIntent(id: String, nombre: String, numTT: String?): PendingIntent {
        val intent = Intent(context, RetornoAlarmReceiver::class.java).apply {
            putExtra("id", id)
            putExtra("nombre", nombre)
            putExtra("numTT", numTT)
        }
        return PendingIntent.getBroadcast(
            context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        fun mostrarNotificacion(context: Context, nombre: String, numTT: String?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            }
            val notif = NotificationCompat.Builder(context, CANAL_RETORNOS)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Retorno: $nombre")
                .setContentText(if (!numTT.isNullOrBlank()) "TT: $numTT — es hora de contactar" else "Es hora de contactar")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(nombre.hashCode(), notif)
        }
    }
}
