package com.example.matrizapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.ZoneId

// ============================================================
// SCHEDULER — una alarma exacta por bloque activo, se reprograma
// sola cada medianoche. Mismo patrón que RetornoAlarmReceiver/
// BootCompletedReceiver ya usan en la app.
// ============================================================
class LlamadaAutomaticaScheduler(
    private val context: Context,
    private val dao: BloqueHorarioDao
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    suspend fun reprogramarTodos() {
        val bloques = dao.obtenerBloquesActivos()
        for (idPosible in 1..MAX_ID_ESPERADO) cancelarBloque(idPosible)
        bloques.forEach { programarBloque(it) }
        programarReprogramacionMedianoche()
    }

    private fun programarBloque(bloque: BloqueHorarioEntity) {
        val ahora = LocalDateTime.now()
        var disparo = ahora.toLocalDate().atTime(bloque.hora, bloque.minuto)
        if (disparo.isBefore(ahora)) disparo = disparo.plusDays(1)

        val millis = disparo.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(context, LlamadaBloqueAlarmReceiver::class.java).apply {
            putExtra(LlamadaBloqueAlarmReceiver.EXTRA_BLOQUE_ID, bloque.id)
        }
        val pending = PendingIntent.getBroadcast(
            context, requestCodeParaBloque(bloque.id), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
    }

    private fun cancelarBloque(id: Long) {
        val intent = Intent(context, LlamadaBloqueAlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, requestCodeParaBloque(id), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pending)
    }

    private fun programarReprogramacionMedianoche() {
        val medianoche = LocalDateTime.now().toLocalDate().plusDays(1).atStartOfDay()
        val millis = medianoche.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(context, ReprogramarLlamadaBloquesReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE_MEDIANOCHE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
    }

    private fun requestCodeParaBloque(id: Long): Int = (REQUEST_CODE_BASE + id).toInt()

    companion object {
        private const val REQUEST_CODE_BASE = 6_000
        private const val REQUEST_CODE_MEDIANOCHE = 6_999
        private const val MAX_ID_ESPERADO = 500L
    }
}

// ============================================================
// RECEIVERS — solo encolan Workers, para no arriesgar el límite
// de ~10s que Android da a un BroadcastReceiver.
// ============================================================
class LlamadaBloqueAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bloqueId = intent.getLongExtra(EXTRA_BLOQUE_ID, -1)
        if (bloqueId < 0) return
        val request = OneTimeWorkRequestBuilder<LlamadaAutomaticaWorker>()
            .setInputData(workDataOf(LlamadaAutomaticaWorker.KEY_BLOQUE_ID to bloqueId))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
    companion object { const val EXTRA_BLOQUE_ID = "bloque_id" }
}

class ReprogramarLlamadaBloquesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<ReprogramarLlamadaBloquesWorker>().build())
    }
}

class ReprogramarLlamadaBloquesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MainApplication).container
        LlamadaAutomaticaScheduler(applicationContext, container.database.bloqueHorarioDao()).reprogramarTodos()
        return Result.success()
    }
}

// ============================================================
// WORKER PRINCIPAL — decide automáticamente a quién le toca este
// bloque (según ReglaRepeticion) y ejecuta llamada+SMS reusando
// CallHelper/SmsHelper, exactamente como ya hace CallRepeatWorker,
// solo que la selección es automática por semana de atraso en vez
// de una lista de IDs elegida a mano.
// ============================================================
class LlamadaAutomaticaWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bloqueId = inputData.getLong(KEY_BLOQUE_ID, -1)
        if (bloqueId < 0) return Result.failure()

        val container = (applicationContext as MainApplication).container
        val bloqueDao = container.database.bloqueHorarioDao()
        val matrizDao = container.database.matrizDao()

        val bloquesActivos = bloqueDao.obtenerBloquesActivos()
        val bloqueActualIndex = bloquesActivos.indexOfFirst { it.id == bloqueId }
        if (bloqueActualIndex == -1) return Result.success() // el bloque fue eliminado/desactivado desde entonces

        val registros = matrizDao.getAllMatriz().first()
        val subId: Int? = null // línea default; si se necesita fijar SIM, se agrega config a BloqueHorarioEntity

        for (r in registros) {
            if (r.estado.equals("Pagado", ignoreCase = true)) continue
            val sem = r.semana.trim().toIntOrNull() ?: continue
            if (sem !in 1..5) continue

            val fechaAlta = ReglaRepeticion.fechaAltaDe(r) ?: continue
            val bloqueAltaIndex = ReglaRepeticion.calcularBloqueDeAlta(fechaAlta, bloquesActivos)
            if (!ReglaRepeticion.debeContactarseEnBloque(sem, bloqueActualIndex, bloqueAltaIndex)) continue

            procesarCliente(r, sem, subId)
        }
        return Result.success()
    }

    private suspend fun procesarCliente(r: MatrizEntity, sem: Int, subId: Int?) {
        if (r.numTT.isNotBlank()) {
            CallHelper.realizarLlamada(applicationContext, subId, r.numTT)
            delay(2_000)
            CallHelper.esperarFinOForzarColgar(applicationContext, duracionMaximaMs = DURACION_MAX_LLAMADA_MS)
            SmsHelper.enviarSms(applicationContext, subId, r.numTT, MensajesCobranza.paraTT(r.nombre, r.requisito, sem))
        }
        listOfNotNull(r.ref1.takeIf { it.isNotBlank() }, r.ref2.takeIf { it.isNotBlank() }).forEach { tel ->
            SmsHelper.enviarSms(applicationContext, subId, tel, MensajesCobranza.paraReferencia(r.nombre, sem))
        }
    }

    companion object {
        const val KEY_BLOQUE_ID = "bloque_id"
        private const val DURACION_MAX_LLAMADA_MS = 45_000L // mismo default que CallViewModel
    }
}
