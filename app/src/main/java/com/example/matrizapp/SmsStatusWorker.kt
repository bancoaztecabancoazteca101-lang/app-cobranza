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

    const val DEFAULT_TEMPLATE =
        "Hola %nombre%, le recordamos su pago acordado para hoy a las %hora%. Banco Azteca."

    fun getMessageTemplate(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("message_template", null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TEMPLATE

    fun setMessageTemplate(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("message_template", text).apply()
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

    private fun eventKey(rowId: String, status: String, extra: String = ""): String =
        if (extra.isBlank()) rowId + "|" + status.uppercase()
        else rowId + "|" + extra + "|" + status.uppercase()

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

    fun markSent(context: Context, rowId: String, status: String, extra: String = "") {
        val sent = getSentEvents(context).toMutableSet()
        sent.add(eventKey(rowId, status, extra))
        saveSentEvents(context, sent)
    }

    fun wasSent(context: Context, rowId: String, status: String, extra: String = ""): Boolean =
        eventKey(rowId, status, extra) in getSentEvents(context)
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

        val subId = SmsStatusLocalConfig.getSubscriptionId(context)
        if (!SmsHelper.tienePermisos(context)) return Result.failure()

        val ahora = System.currentTimeMillis()
        val hoyCal = java.util.Calendar.getInstance()
        val dia = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(ahora))
        var enviados = 0
        for (registro in registros) {
            val status = registro.estado.trim().uppercase()
            if (status.isBlank() || status !in statuses) continue

            val telefono = registro.numTT.trim()
            if (telefono.isBlank()) continue

            // Solo registros de HOY con Hora fijada: el SMS sale a esa hora, una sola vez
            val hora = registro.hora?.trim().orEmpty()
            if (hora.isBlank()) continue
            val fechaReg = registro.fecha ?: continue
            if (!esMismoDia(fechaReg, hoyCal)) continue
            val trigger = triggerDeHoy(hora) ?: continue

            val extra = dia + "@" + hora
            if (SmsStatusLocalConfig.wasSent(context, registro.id, status, extra)) continue

            if (ahora < trigger) {
                programarEn(context, registro.id, trigger - ahora)
                continue
            }
            if (ahora - trigger > GRACIA_MS) continue // ya pasó demasiado: no se manda tarde

            val mensaje = SmsHelper.armarMensaje(
                plantilla = SmsStatusLocalConfig.getMessageTemplate(context),
                nombre = registro.nombre,
                monto = registro.requisito
            ).replace("%hora%", hora, ignoreCase = true)

            if (SmsHelper.enviarSms(context, subId, telefono, mensaje)) {
                SmsStatusLocalConfig.markSent(context, registro.id, status, extra)
                enviados++
                if (enviados < 20) delay(3000)
            }
        }

        return Result.success()
    }

    companion object {
        private const val PERIODIC_NAME = "sms_status_periodic"
        private const val GRACIA_MS = 60 * 60 * 1000L

        private fun esMismoDia(millis: Long, hoy: java.util.Calendar): Boolean {
            val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
            return c.get(java.util.Calendar.YEAR) == hoy.get(java.util.Calendar.YEAR) &&
                c.get(java.util.Calendar.DAY_OF_YEAR) == hoy.get(java.util.Calendar.DAY_OF_YEAR)
        }

        private fun triggerDeHoy(hora: String): Long? {
            val partes = hora.split(":").mapNotNull { it.trim().toIntOrNull() }
            if (partes.size < 2) return null
            return java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, partes[0])
                set(java.util.Calendar.MINUTE, partes[1])
                set(java.util.Calendar.SECOND, if (partes.size > 2) partes[2] else 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        private fun programarEn(context: Context, rowId: String, delayMs: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "sms_status_at_" + rowId,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SmsStatusWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .build()
            )
        }

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
