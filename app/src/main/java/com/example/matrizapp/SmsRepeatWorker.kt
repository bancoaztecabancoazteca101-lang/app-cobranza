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

/** Envía una ronda de SMS a los contactos indicados y, si quedan repeticiones programadas para
 * el día, se vuelve a encolar a sí mismo con el intervalo de horas configurado. Corre vía
 * WorkManager para que la repetición sobreviva a que la app se cierre. */
class SmsRepeatWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val fuente = FuenteSms.valueOf(inputData.getString(KEY_FUENTE) ?: FuenteSms.TT.name)
        val ids = (inputData.getString(KEY_IDS) ?: "").split(",").filter { it.isNotBlank() }.toSet()
        val plantilla = inputData.getString(KEY_PLANTILLA) ?: return Result.failure()
        val agente = inputData.getString(KEY_AGENTE) ?: ""
        val contacto = inputData.getString(KEY_CONTACTO) ?: ""
        val subId = inputData.getInt(KEY_SUBID, -1).let { if (it == -1) null else it }
        val delaySegundos = inputData.getInt(KEY_DELAY, 5)
        val repeticionesRestantes = inputData.getInt(KEY_REPETICIONES, 1)
        val horasEntreRepeticion = inputData.getInt(KEY_HORAS, 3)

        if (ids.isEmpty()) return Result.failure()

        val container = (applicationContext as MainApplication).container
        // Vuelve a leer Matriz en el momento del envío (no datos guardados de cuando se
        // programó), así cada ronda usa el teléfono/monto más reciente si algo cambió.
        val registros = container.database.matrizDao().getAllMatriz().first().filter { it.id in ids }

        for ((i, r) in registros.withIndex()) {
            val telefono = when (fuente) {
                FuenteSms.TT -> r.numTT
                FuenteSms.REF1 -> r.ref1
                FuenteSms.REF2 -> r.ref2
            }
            if (telefono.isBlank()) continue
            val mensaje = SmsHelper.armarMensaje(plantilla, r.nombre, r.requisito, agente, contacto)
            SmsHelper.enviarSms(applicationContext, subId, telefono, mensaje)
            if (i < registros.lastIndex) delay(delaySegundos * 1000L)
        }

        if (repeticionesRestantes > 1) {
            val siguiente = OneTimeWorkRequestBuilder<SmsRepeatWorker>()
                .setInitialDelay(horasEntreRepeticion.toLong(), TimeUnit.HOURS)
                .setInputData(construirInputData(fuente, ids.toList(), plantilla, agente, contacto, subId, delaySegundos, repeticionesRestantes - 1, horasEntreRepeticion))
                .build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, siguiente)
        }

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "sms_repeat_diario"
        private const val KEY_FUENTE = "fuente"
        private const val KEY_IDS = "ids"
        private const val KEY_PLANTILLA = "plantilla"
        private const val KEY_AGENTE = "agente"
        private const val KEY_CONTACTO = "contacto"
        private const val KEY_SUBID = "subId"
        private const val KEY_DELAY = "delaySegundos"
        private const val KEY_REPETICIONES = "repeticionesRestantes"
        private const val KEY_HORAS = "horasEntreRepeticion"

        private fun construirInputData(
            fuente: FuenteSms, ids: List<String>, plantilla: String, agente: String, contacto: String,
            subscriptionId: Int?, delaySegundos: Int, repeticionesRestantes: Int, horasEntreRepeticion: Int
        ): Data = workDataOf(
            KEY_FUENTE to fuente.name,
            KEY_IDS to ids.joinToString(","),
            KEY_PLANTILLA to plantilla,
            KEY_AGENTE to agente,
            KEY_CONTACTO to contacto,
            KEY_SUBID to (subscriptionId ?: -1),
            KEY_DELAY to delaySegundos,
            KEY_REPETICIONES to repeticionesRestantes,
            KEY_HORAS to horasEntreRepeticion
        )

        /** Encola la primera ronda de inmediato; las siguientes se autoprograman desde doWork(). */
        fun programar(
            workManager: WorkManager, fuente: FuenteSms, idsSeleccionados: List<String>, plantilla: String,
            agente: String, contacto: String, subscriptionId: Int?, delaySegundos: Int,
            repeticionesRestantes: Int, horasEntreRepeticion: Int
        ) {
            val solicitud = OneTimeWorkRequestBuilder<SmsRepeatWorker>()
                .setInputData(construirInputData(fuente, idsSeleccionados, plantilla, agente, contacto, subscriptionId, delaySegundos, repeticionesRestantes, horasEntreRepeticion))
                .build()
            workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, solicitud)
        }

        fun cancelar(workManager: WorkManager) {
            workManager.cancelUniqueWork(WORK_NAME)
        }

        /** true mientras queden repeticiones encoladas o corriendo; permite mostrar en la UI si
         * el envío automático sigue activo y habilitar el botón de detener. */
        fun estaProgramado(workManager: WorkManager): Flow<Boolean> =
            workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
                infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            }
    }
}
