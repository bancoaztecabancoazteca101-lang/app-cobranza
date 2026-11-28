package com.example.matrizapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters

// ============================================================
// SINCRONIZACIÓN AUTOMÁTICA EN SEGUNDO PLANO (solo este teléfono)
// Hasta ahora la app solo refrescaba con la app abierta (cada 3 min en primer plano). En el
// teléfono de las llamadas automáticas la app casi nunca está abierta, así que sus vistas del
// día quedaban viejas. Este interruptor es LOCAL de cada dispositivo (SharedPreferences, no
// Room ni Sheets): apagado por defecto, y solo el teléfono donde se prenda gasta batería.
// Mecánica: una alarma encadenada cada N min -> Receiver -> Worker expedido que hace refreshAll().
// ============================================================
object SyncAutoPrefs {
    private const val PREFS_NAME = "sync_auto_prefs"
    private const val KEY_ACTIVA = "activa"
    private const val KEY_MINUTOS = "minutos"
    val OPCIONES_MINUTOS = listOf(5, 10, 15)

    fun activa(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVA, false)

    fun setActiva(context: Context, valor: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACTIVA, valor).apply()
    }

    fun minutos(context: Context): Int {
        val guardado = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_MINUTOS, 5)
        return if (guardado in OPCIONES_MINUTOS) guardado else 5
    }

    fun setMinutos(context: Context, valor: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_MINUTOS, valor).apply()
    }
}

object SyncAutoScheduler {
    private const val REQUEST_CODE = 8301
    const val WORK_NAME = "sync_auto_worker"

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE, Intent(context, SyncAutoReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Programa UNA alarma dentro de N minutos; el Receiver la vuelve a programar cada vez (cadena). */
    fun programarSiguiente(context: Context) {
        if (!SyncAutoPrefs.activa(context)) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val disparo = System.currentTimeMillis() + SyncAutoPrefs.minutos(context) * 60_000L
        val pi = pendingIntent(context)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, disparo, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, disparo, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, disparo, pi)
        }
    }

    /** Al prender el interruptor o cambiar los minutos: sincroniza ya y reinicia la cadena. */
    fun iniciar(context: Context) {
        cancelarAlarma(context)
        if (!SyncAutoPrefs.activa(context)) return
        SyncAutoWorker.encolar(context)
        programarSiguiente(context)
    }

    private fun cancelarAlarma(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent(context))
    }

    fun cancelar(context: Context) {
        cancelarAlarma(context)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** AlarmManager pierde todo al reiniciar o actualizar la app: se repone si el interruptor estaba prendido. */
    fun programarTrasReinicio(context: Context) {
        if (!SyncAutoPrefs.activa(context)) return
        SyncAutoWorker.encolar(context)
        programarSiguiente(context)
    }
}

class SyncAutoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (!SyncAutoPrefs.activa(app)) return
        // Primero encadena la siguiente alarma (así un fallo de red no rompe la cadena) y luego trabaja.
        SyncAutoScheduler.programarSiguiente(app)
        SyncAutoWorker.encolar(app)
    }
}

class SyncAutoWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!SyncAutoPrefs.activa(applicationContext)) return Result.success()
        try {
            val container = (applicationContext as MainApplication).container
            container.repository.refreshAll()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sin red u otro error: no se reintenta aquí; el siguiente ciclo (en N min) lo vuelve a intentar.
        }
        return Result.success()
    }

    /** Requerido por los trabajos expeditos en Android < 12 (corren como servicio en primer plano). */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val ctx = applicationContext
        val canal = "sync_auto"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (nm.getNotificationChannel(canal) == null) {
                nm.createNotificationChannel(android.app.NotificationChannel(canal, "Sincronización automática", android.app.NotificationManager.IMPORTANCE_LOW))
            }
        }
        val notif = androidx.core.app.NotificationCompat.Builder(ctx, canal)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Sincronizando Matriz")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(7302, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(7302, notif)
    }

    companion object {
        fun encolar(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncAutoWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            // KEEP: si todavía hay una sincronización corriendo no se apila otra.
            WorkManager.getInstance(context).enqueueUniqueWork(SyncAutoScheduler.WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
