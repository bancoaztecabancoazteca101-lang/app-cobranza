package com.example.matrizapp

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Corre un bloque de llamadas (marca, espera a que termine, pausa, siguiente) y si quedan
 * repeticiones se vuelve a encolar con el intervalo de horas configurado. Vía WorkManager para
 * sobrevivir a que la app se cierre, igual que SmsRepeatWorker. */
class CallRepeatWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val ids = (inputData.getString(KEY_IDS) ?: "").split(",").filter { it.isNotBlank() }.toSet()
        val subId = inputData.getInt(KEY_SUBID, -1).let { if (it == -1) null else it }
        val segundosEntreLlamadas = inputData.getInt(KEY_SEGUNDOS, 5)
        val horasEntreBloques = inputData.getInt(KEY_HORAS, 1)
        val repeticionesRestantes = inputData.getInt(KEY_REPETICIONES, 1)

        if (ids.isEmpty()) return Result.failure()

        val container = (applicationContext as MainApplication).container
        // Vuelve a leer Matriz en el momento de marcar (no datos guardados de cuando se
        // programó), y reconstruye la cola TT/Ref1/Ref2 en el mismo orden.
        val registros = container.database.matrizDao().getAllMatriz().first()
        val porId = registros.associateBy { it.id }

        for ((i, itemId) in ids.withIndex()) {
            val contactoId = itemId.substringBeforeLast("_")
            val tipo = itemId.substringAfterLast("_")
            val registro = porId[contactoId] ?: continue
            val telefono = when (tipo) {
                "TT" -> registro.numTT
                "REF1" -> registro.ref1
                "REF2" -> registro.ref2
                else -> null
            }
            if (telefono.isNullOrBlank()) continue
            CallHelper.realizarLlamada(applicationContext, subId, telefono)
            delay(2000)
            CallHelper.esperarFinDeLlamada(applicationContext, timeoutMs = 120_000)
            if (i < ids.size - 1) delay(segundosEntreLlamadas * 1000L)
        }

        if (repeticionesRestantes > 1) {
            val siguiente = OneTimeWorkRequestBuilder<CallRepeatWorker>()
                .setInitialDelay(horasEntreBloques.toLong(), TimeUnit.HOURS)
                .setInputData(construirInputData(ids.toList(), subId, segundosEntreLlamadas, horasEntreBloques, repeticionesRestantes - 1))
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, siguiente)
        }

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "call_repeat_bloques"
        private const val KEY_IDS = "ids"
        private const val KEY_SUBID = "subId"
        private const val KEY_SEGUNDOS = "segundosEntreLlamadas"
        private const val KEY_HORAS = "horasEntreBloques"
        private const val KEY_REPETICIONES = "repeticionesRestantes"

        private fun construirInputData(ids: List<String>, subscriptionId: Int?, segundosEntreLlamadas: Int, horasEntreBloques: Int, repeticionesRestantes: Int): Data =
            workDataOf(
                KEY_IDS to ids.joinToString(","),
                KEY_SUBID to (subscriptionId ?: -1),
                KEY_SEGUNDOS to segundosEntreLlamadas,
                KEY_HORAS to horasEntreBloques,
                KEY_REPETICIONES to repeticionesRestantes
            )

        /** Encola el primer bloque para que arranque en iniciarEnMillis (ya calculado como la
         * próxima ocurrencia de la hora elegida); las rondas siguientes se autoprograman desde doWork(). */
        fun programar(
            workManager: WorkManager, idsSeleccionados: List<String>, subscriptionId: Int?,
            segundosEntreLlamadas: Int, iniciarEnMillis: Long, horasEntreBloques: Int, repeticionesRestantes: Int
        ) {
            val delayInicialMs = (iniciarEnMillis - System.currentTimeMillis()).coerceAtLeast(0)
            val solicitud = OneTimeWorkRequestBuilder<CallRepeatWorker>()
                .setInitialDelay(delayInicialMs, TimeUnit.MILLISECONDS)
                .setInputData(construirInputData(idsSeleccionados, subscriptionId, segundosEntreLlamadas, horasEntreBloques, repeticionesRestantes))
                .build()
            workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, solicitud)
        }

        fun cancelar(workManager: WorkManager) { workManager.cancelUniqueWork(WORK_NAME) }
    }
}
