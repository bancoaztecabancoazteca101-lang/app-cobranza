package com.example.matrizapp

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Con red, baja y guarda en el teléfono las fotos de Matriz Y de Semana 6 (para verlas sin internet),
 * y una vez por semana borra las fotos que ya no pertenecen a ningún registro. */
class ClientImageSyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    private val container = (appContext as MainApplication).container

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val matriz = container.database.matrizDao().getAllMatriz().first()
            // Semana 6 ya vive en Room (tabla sem6_registro_table).
            val sem6 = container.database.sem6Dao().todasLasImagenes()

            val fuentes = (matriz.flatMap { listOf(it.imagenUrl, it.imagenUrl2) } + sem6)
                .filter { !it.isNullOrBlank() }.map { it!!.trim() }.distinct()

            for (fuente in fuentes) {
                if (isStopped) return@withContext Result.retry()
                try { container.clientImageStore.ensureLocal(fuente, container.driveHelper) } catch (_: Exception) { /* sigue con la siguiente */ }
            }
            // Solo se limpia si hay datos de ambos orígenes: con una lectura vacía/fallida se
            // confundirían fotos vigentes con huérfanas.
            if (matriz.isNotEmpty() && sem6.isNotEmpty()) container.clientImageStore.limpiarHuerfanas(fuentes)
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }

    companion object {
        private const val WORK_NAME = "sync_matriz_client_images"
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ClientImageSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
