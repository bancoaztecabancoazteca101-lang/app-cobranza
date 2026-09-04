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
private val ESTADOS_NOTIFICABLES = setOf("retorno", "app")
private fun esEstadoNotificable(estado: String) = estado.trim().lowercase() in ESTADOS_NOTIFICABLES

class NotificacionesHelper(private val context: Context) {
    init { crearCanal() }
    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CANAL_RETORNOS) == null) {
                nm.createNotificationChannel(NotificationChannel(CANAL_RETORNOS, "Retornos programados", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Avisa a la hora puesta cuando un cliente está marcado como Retorno o App"
                })
            }
        }
    }
    fun evaluarProgramacion(estado: String, hora: String?): String? {
        if (!esEstadoNotificable(estado)) return null
        if (hora.isNullOrBlank()) return "Marca \"Retorno\" o \"App\" y pon una Hora para programar el aviso"
        val trigger = calcularTriggerHoy(hora) ?: return "No se pudo interpretar la hora"
        return if (trigger <= System.currentTimeMillis()) "Esa hora ya pasó — no se programó notificación" else {
            val df = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            "Notificación programada para las ${df.format(java.util.Date(trigger))}"
        }
    }
    fun sincronizarAlarmasRetorno(items: List<FiltroFechaEntity>) {
        val prefs = context.getSharedPreferences(PREFS_RETORNOS, Context.MODE_PRIVATE)
        val idsAnteriores = prefs.getStringSet("ids", emptySet()) ?: emptySet()
        val historial = parseHistorial(prefs.getStringSet("historial", emptySet()))
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val ahora = System.currentTimeMillis()
        val objetivos = items.filter { esEstadoNotificable(it.estado) && !it.hora.isNullOrBlank() }
        if (objetivos.isEmpty() && idsAnteriores.isEmpty() && historial.isEmpty()) return
        val puedeExacta = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true
        val idsNuevos = mutableSetOf<String>()
        val historialNuevo = mutableMapOf<String, Long>()
        for (item in objetivos) {
            val clave = "${item.id}|${item.hora}"
            val triggerPrevio = historial[clave]
            if (triggerPrevio != null) {
                if (triggerPrevio > ahora) { idsNuevos.add(item.id); historialNuevo[clave] = triggerPrevio }
                continue
            }
            val trigger = calcularTriggerHoy(item.hora!!) ?: continue
            if (trigger <= ahora) continue
            idsNuevos.add(item.id); historialNuevo[clave] = trigger
            programarAlarmaGenerica(alarmManager, crearPendingIntent(item.id, item.nombre, item.numTT, item.estado, item.ubicacion, item.req), trigger, puedeExacta)
        }
        for (idViejo in idsAnteriores) if (idViejo !in idsNuevos) alarmManager.cancel(crearPendingIntent(idViejo, "", null, ""))
        prefs.edit().putStringSet("ids", idsNuevos).putStringSet("historial", historialNuevo.map { "${it.key}=${it.value}" }.toSet()).apply()
    }
    private fun parseHistorial(raw: Set<String>?): Map<String, Long> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.mapNotNull { entry ->
            val idx = entry.lastIndexOf('='); if (idx == -1) return@mapNotNull null
            val trigger = entry.substring(idx + 1).toLongOrNull() ?: return@mapNotNull null
            entry.substring(0, idx) to trigger
        }.toMap()
    }
    fun sincronizarAlarmasRetornoMatriz(items: List<MatrizEntity>) {
        val prefs = context.getSharedPreferences(PREFS_RETORNOS, Context.MODE_PRIVATE)
        val idsAnteriores = prefs.getStringSet("ids_matriz", emptySet()) ?: emptySet()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val ahora = System.currentTimeMillis(); val hoyCal = java.util.Calendar.getInstance()
        val objetivos = items.filter { it -> esEstadoNotificable(it.estado) && !it.hora.isNullOrBlank() && it.fecha != null && esHoy(it.fecha!!, hoyCal) }
        if (objetivos.isEmpty() && idsAnteriores.isEmpty()) return
        val puedeExacta = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true
        val idsNuevos = mutableSetOf<String>()
        for (item in objetivos) {
            val trigger = calcularTriggerHoy(item.hora!!) ?: continue
            if (trigger <= ahora) continue
            val idPrefijado = "matriz_${item.id}"; idsNuevos.add(idPrefijado)
            programarAlarmaGenerica(alarmManager, crearPendingIntent(idPrefijado, item.nombre, item.numTT, item.estado, item.ubicacion, item.requisito), trigger, puedeExacta)
        }
        for (idViejo in idsAnteriores) if (idViejo !in idsNuevos) alarmManager.cancel(crearPendingIntent(idViejo, "", null, ""))
        prefs.edit().putStringSet("ids_matriz", idsNuevos).apply()
    }
    private fun esHoy(fechaMillis: Long, hoyCal: java.util.Calendar): Boolean {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = fechaMillis }
        return cal.get(java.util.Calendar.YEAR) == hoyCal.get(java.util.Calendar.YEAR) && cal.get(java.util.Calendar.DAY_OF_YEAR) == hoyCal.get(java.util.Calendar.DAY_OF_YEAR)
    }
    private fun programarAlarmaGenerica(alarmManager: AlarmManager, pendingIntent: PendingIntent, trigger: Long, puedeExacta: Boolean) {
        try { if (puedeExacta) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent) else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent) }
        catch (e: SecurityException) { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent) }
    }
    private fun calcularTriggerHoy(hora: String): Long? {
        val partes = hora.split(":").mapNotNull { it.trim().toIntOrNull() }; if (partes.size < 2) return null
        val cal = java.util.Calendar.getInstance(); cal.set(java.util.Calendar.HOUR_OF_DAY, partes[0]); cal.set(java.util.Calendar.MINUTE, partes[1]); cal.set(java.util.Calendar.SECOND, if (partes.size > 2) partes[2] else 0); cal.set(java.util.Calendar.MILLISECOND, 0); return cal.timeInMillis
    }
    private fun crearPendingIntent(id: String, nombre: String, numTT: String?, estado: String, ubicacion: String? = null, requerido: String? = null): PendingIntent {
        val intent = Intent(context, RetornoAlarmReceiver::class.java).apply { putExtra("id", id); putExtra("nombre", nombre); putExtra("numTT", numTT); putExtra("estado", estado); putExtra("ubicacion", ubicacion); putExtra("requerido", requerido) }
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    companion object {
        fun mostrarNotificacion(context: Context, id: String, nombre: String, numTT: String?, estado: String?, colonia: String?, calle: String?, ubicacion: String?, requerido: String?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val etiqueta = if (estado?.trim()?.lowercase() == "app") "App" else "Retorno"
            val direccion = listOf(colonia?.trim(), calle?.trim()).filter { !it.isNullOrBlank() }.joinToString(" — ")
            val req = requerido?.trim()?.takeIf { it.isNotBlank() }
            val texto = when { !req.isNullOrBlank() && direccion.isNotBlank() -> "Requerido: $req\n$direccion"; !req.isNullOrBlank() -> "Requerido: $req"; direccion.isNotBlank() -> direccion; else -> "Es hora de contactar" }
            val builder = NotificationCompat.Builder(context, CANAL_RETORNOS)
                .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("$etiqueta: $nombre")
                .setStyle(NotificationCompat.BigTextStyle().bigText(texto)).setContentText(texto.replace("\n", " — "))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER).setDefaults(NotificationCompat.DEFAULT_ALL)
            val latLng = parseLatLngOrden(ubicacion)
            if (latLng != null) {
                val navIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=${latLng.first},${latLng.second}")).apply { setPackage("com.google.android.apps.maps"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                val navPendingIntent = PendingIntent.getActivity(context, "nav_$id".hashCode(), navIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(android.R.drawable.ic_menu_mylocation, "Iniciar ruta", navPendingIntent)
            }
            NotificationManagerCompat.from(context).notify(id.hashCode(), builder.build())
        }
    }
}
