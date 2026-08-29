package com.example.matrizapp

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** Corre un bloque de llamadas (marca, espera a que termine o la cuelga a la fuerza tras la
 * duración máxima, manda SMS si aplica, pausa, siguiente) y si quedan repeticiones se vuelve a
 * encolar con el intervalo de horas configurado. Vía WorkManager para sobrevivir a que la app
 * se cierre, igual que SmsRepeatWorker. */
class CallRepeatWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val ids = (inputData.getString(KEY_IDS) ?: "").split(",").filter { it.isNotBlank() }.toSet()
        val subId = inputData.getInt(KEY_SUBID, -1).let { if (it == -1) null else it }
        val segundosEntreLlamadas = inputData.getInt(KEY_SEGUNDOS, 5)
        val duracionMaximaSegundos = inputData.getInt(KEY_DURACION_MAX, 45)
        val horasEntreBloques = inputData.getInt(KEY_HORAS, 1)
        val repeticionesRestantes = inputData.getInt(KEY_REPETICIONES, 1)
        val enviarSmsAlColgar = inputData.getBoolean(KEY_ENVIAR_SMS, false)
        val plantillaSmsTT = inputData.getString(KEY_PLANTILLA_TT) ?: ""
        val plantillaSmsRef = inputData.getString(KEY_PLANTILLA_REF) ?: ""
        val agenteSms = inputData.getString(KEY_AGENTE) ?: ""
        val contactoSms = inputData.getString(KEY_CONTACTO) ?: ""

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
            // Espera a que termine sola; si sigue activa al llegar a la duración máxima
            // (nadie contestó ni colgó), la cuelga a la fuerza para no quedarse atorado.
            CallHelper.esperarFinOForzarColgar(applicationContext, duracionMaximaMs = duracionMaximaSegundos * 1000L)

            // Flujo tipo Tasker: en cuanto cuelga, manda el SMS a ese mismo número.
            if (enviarSmsAlColgar) {
                val plantilla = if (tipo == "TT") plantillaSmsTT else plantillaSmsRef
                if (plantilla.isNotBlank()) {
                    val mensaje = SmsHelper.armarMensaje(plantilla, registro.nombre, registro.requisito, agenteSms, contactoSms)
                    SmsHelper.enviarSms(applicationContext, subId, telefono, mensaje)
                }
            }

            if (i < ids.size - 1) delay(segundosEntreLlamadas * 1000L)
        }

        if (repeticionesRestantes > 1) {
            val siguiente = OneTimeWorkRequestBuilder<CallRepeatWorker>()
                .setInitialDelay(horasEntreBloques.toLong(), TimeUnit.HOURS)
                .setInputData(construirInputData(ids.toList(), subId, segundosEntreLlamadas, duracionMaximaSegundos, horasEntreBloques, repeticionesRestantes - 1, enviarSmsAlColgar, plantillaSmsTT, plantillaSmsRef, agenteSms, contactoSms))
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
        private const val KEY_DURACION_MAX = "duracionMaximaSegundos"
        private const val KEY_HORAS = "horasEntreBloques"
        private const val KEY_REPETICIONES = "repeticionesRestantes"
        private const val KEY_ENVIAR_SMS = "enviarSmsAlColgar"
        private const val KEY_PLANTILLA_TT = "plantillaSmsTT"
        private const val KEY_PLANTILLA_REF = "plantillaSmsRef"
        private const val KEY_AGENTE = "agenteSms"
        private const val KEY_CONTACTO = "contactoSms"

        private fun construirInputData(
            ids: List<String>, subscriptionId: Int?, segundosEntreLlamadas: Int, duracionMaximaSegundos: Int, horasEntreBloques: Int, repeticionesRestantes: Int,
            enviarSmsAlColgar: Boolean, plantillaSmsTT: String, plantillaSmsRef: String, agenteSms: String, contactoSms: String
        ): Data = workDataOf(
            KEY_IDS to ids.joinToString(","),
            KEY_SUBID to (subscriptionId ?: -1),
            KEY_SEGUNDOS to segundosEntreLlamadas,
            KEY_DURACION_MAX to duracionMaximaSegundos,
            KEY_HORAS to horasEntreBloques,
            KEY_REPETICIONES to repeticionesRestantes,
            KEY_ENVIAR_SMS to enviarSmsAlColgar,
            KEY_PLANTILLA_TT to plantillaSmsTT,
            KEY_PLANTILLA_REF to plantillaSmsRef,
            KEY_AGENTE to agenteSms,
            KEY_CONTACTO to contactoSms
        )

        /** Encola el primer bloque para que arranque en iniciarEnMillis (ya calculado como la
         * próxima ocurrencia de la hora elegida); las rondas siguientes se autoprograman desde doWork(). */
        fun programar(
            workManager: WorkManager, idsSeleccionados: List<String>, subscriptionId: Int?,
            segundosEntreLlamadas: Int, duracionMaximaSegundos: Int, iniciarEnMillis: Long, horasEntreBloques: Int, repeticionesRestantes: Int,
            enviarSmsAlColgar: Boolean = false, plantillaSmsTT: String = "", plantillaSmsRef: String = "",
            agenteSms: String = "", contactoSms: String = ""
        ) {
            val delayInicialMs = (iniciarEnMillis - System.currentTimeMillis()).coerceAtLeast(0)
            val solicitud = OneTimeWorkRequestBuilder<CallRepeatWorker>()
                .setInitialDelay(delayInicialMs, TimeUnit.MILLISECONDS)
                .setInputData(construirInputData(idsSeleccionados, subscriptionId, segundosEntreLlamadas, duracionMaximaSegundos, horasEntreBloques, repeticionesRestantes, enviarSmsAlColgar, plantillaSmsTT, plantillaSmsRef, agenteSms, contactoSms))
                .build()
            workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, solicitud)
        }

        fun cancelar(workManager: WorkManager) { workManager.cancelUniqueWork(WORK_NAME) }

        /** true mientras quede algún bloque encolado o corriendo; permite mostrar en la UI si
         * el flujo sigue activo y habilitar el botón de detener. */
        fun estaProgramado(workManager: WorkManager): Flow<Boolean> =
            workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
                infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            }
    }
}
