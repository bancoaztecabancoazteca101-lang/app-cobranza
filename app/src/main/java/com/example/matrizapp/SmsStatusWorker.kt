package com.example.matrizapp

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

object SmsStatusLocalConfig {
    const val PREFS = "sms_status_config"
    private const val KEY_ENABLED_STATUSES = "enabled_statuses"
    private const val KEY_SENDER_ENABLED = "sender_enabled"
    private const val KEY_SUBSCRIPTION_ID = "subscriptionId"
    private const val KEY_SENT_EVENTS = "sent_events"
    private const val KEY_BASELINE_PREFIX = "baseline_"

    fun getEnabledStatuses(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_ENABLED_STATUSES, emptySet())
            ?.map { it.uppercase() }
            ?.toSet()
            ?: emptySet()

    fun setStatus(context: Context, status: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val statuses = getEnabledStatuses(context).toMutableSet()
        val normalized = status.trim().uppercase()
        if (enabled) statuses.add(normalized) else statuses.remove(normalized)

        val sent = getSentEvents(context).filterNot { it.endsWith("|" + normalized) }.toSet()
        prefs.edit()
            .putStringSet(KEY_ENABLED_STATUSES, statuses)
            .putStringSet(KEY_SENT_EVENTS, sent)
            .putBoolean(KEY_BASELINE_PREFIX + normalized, false)
            .apply()
    }

    fun isSenderEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SENDER_ENABLED, false)

    fun setSenderEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!enabled) {
            prefs.edit()
                .putBoolean(KEY_SENDER_ENABLED, false)
                .putStringSet(KEY_SENT_EVENTS, emptySet())
                .apply()
            return
        }

        prefs.edit()
            .putBoolean(KEY_SENDER_ENABLED, true)
            .putStringSet(KEY_SENT_EVENTS, emptySet())
            .apply()

        getEnabledStatuses(context).forEach { status ->
            prefs.edit().putBoolean(KEY_BASELINE_PREFIX + status, false).apply()
        }
    }

    fun getSubscriptionId(context: Context): Int? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SUBSCRIPTION_ID, -1)
            .let { if (it < 0) null else it }

    private fun getSentEvents(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SENT_EVENTS, emptySet())
            ?.toSet()
            ?: emptySet()

    private fun saveSentEvents(context: Context, values: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_SENT_EVENTS, values).apply()
    }

    private fun eventKey(rowId: String, status: String): String =
        rowId + "|" + status.uppercase()

    private fun baselineStatus(
        context: Context,
        status: String,
        registros: List<MatrizEntity>,
        sent: MutableSet<String>
    ): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val marker = KEY_BASELINE_PREFIX + status
        if (prefs.getBoolean(marker, false)) return false

        registros
            .filter { it.estado.trim().equals(status, ignoreCase = true) }
            .filter { it.numTT.trim().isNotBlank() }
            .forEach { sent.add(eventKey(it.id, status)) }

        prefs.edit().putBoolean(marker, true).apply()
        return true
    }

    fun baseline(context: Context, statuses: Set<String>, registros: List<MatrizEntity>) {
        val sent = getSentEvents(context).toMutableSet()
        var changed = false
        statuses.forEach { status ->
            changed = baselineStatus(context, status, registros, sent) || changed
        }
        if (changed) saveSentEvents(context, sent)
    }

    fun markSent(context: Context, rowId: String, status: String) {
        val sent = getSentEvents(context).toMutableSet()
        sent.add(eventKey(rowId, status))
        saveSentEvents(context, sent)
    }

    fun wasSent(context: Context, rowId: String, status: String): Boolean =
        eventKey(rowId, status) in getSentEvents(context)
}

class SmsStatusWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!SmsStatusLocalConfig.isSenderEnabled(context)) return Result.success()

        val statuses = SmsStatusLocalConfig.getEnabledStatuses(context)
        if (statuses.isEmpty()) return Result.success()

        val container = (context as MainApplication).container
        val registros = container.database.matrizDao().getAllMatriz().first()

        SmsStatusLocalConfig.baseline(context, statuses, registros)

        val subId = SmsStatusLocalConfig.getSubscriptionId(context)
        if (!SmsHelper.tienePermisos(context)) return Result.failure()

        var enviados = 0
        for (registro in registros) {
            val status = registro.estado.trim().uppercase()
            if (status.isBlank() || status !in statuses) continue

            val telefono = registro.numTT.trim()
            if (telefono.isBlank()) continue
            if (SmsStatusLocalConfig.wasSent(context, registro.id, status)) continue

            val semana = registro.semana.toIntOrNull()?.coerceIn(1, 5) ?: 1
            val plantilla = container.database.plantillaSmsDao()
                .obtenerActivasPara("TT", semana)
                .firstOrNull()?.texto
                ?: PlantillasSemillaSms.textoDeFabrica("TT", semana, 1)
                ?: "Hola %nombre%, tiene un pago pendiente%monto% con Banco Azteca."

            val mensaje = SmsHelper.armarMensaje(
                plantilla = plantilla,
                nombre = registro.nombre,
                monto = registro.requisito
            )

            if (SmsHelper.enviarSms(context, subId, telefono, mensaje)) {
                SmsStatusLocalConfig.markSent(context, registro.id, status)
                enviados++
                if (enviados < 20) delay(3000)
            }
        }

        return Result.success()
    }

    companion object {
        private const val PERIODIC_NAME = "sms_status_periodic"

        fun programarPeriodicamente(context: Context) {
            val request = PeriodicWorkRequestBuilder<SmsStatusWorker>(
                15, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun programarAhora(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "sms_status_now",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SmsStatusWorker>().build()
            )
            programarPeriodicamente(context)
        }
    }
}
