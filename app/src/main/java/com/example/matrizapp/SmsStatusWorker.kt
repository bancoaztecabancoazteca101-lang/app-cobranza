package com.example.matrizapp

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/** Ejecuta el SMS disparado por un Status APP. El evento llega por FCM al único dispositivo
 * seleccionado para SMS por Status y se procesa con WorkManager para que el envío no dependa
 * de que la Activity esté abierta. */
class SmsStatusWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val rowId = inputData.getString(KEY_ROW_ID).orEmpty()
        if (rowId.isBlank()) return Result.failure()

        val container = (applicationContext as MainApplication).container
        val registro = container.database.matrizDao().getById(rowId) ?: return Result.failure()
        val telefono = registro.numTT.trim()
        if (telefono.isBlank()) return Result.failure()

        val semana = registro.semana.toIntOrNull()?.coerceIn(1, 5) ?: 1
        val plantilla = container.database.plantillaSmsDao()
            .obtenerActivasPara("TT", semana)
            .firstOrNull()?.texto
            ?: PlantillasSemillaSms.textoDeFabrica("TT", semana, 1)
            ?: "Hola %nombre%, tiene un pago pendiente%monto% con Banco Azteca."

        val mensaje = SmsHelper.armarMensaje(plantilla, registro.nombre, registro.requisito)

        val prefs = applicationContext.getSharedPreferences("sms_status_config", Context.MODE_PRIVATE)
        val subId = prefs.getInt(KEY_SUB_ID, -1).let { if (it < 0) null else it }

        return if (SmsHelper.tienePermisos(applicationContext) &&
            SmsHelper.enviarSms(applicationContext, subId, telefono, mensaje)
        ) Result.success() else Result.failure()
    }

    companion object {
        private const val KEY_ROW_ID = "rowId"
        private const val KEY_SUB_ID = "subscriptionId"
        private const val WORK_PREFIX = "sms_status_"

        fun programar(context: Context, rowId: String, eventId: String) {
            val request = OneTimeWorkRequestBuilder<SmsStatusWorker>()
                .setInputData(workDataOf(KEY_ROW_ID to rowId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_PREFIX + eventId.hashCode(),
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
