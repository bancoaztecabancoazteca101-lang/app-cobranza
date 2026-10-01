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

class ClientImageSyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    private val container = (appContext as MainApplication).container

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val items = container.database.matrizDao().getAllMatriz().first()
            for (item in items) {
                listOf(item.imagenUrl, item.imagenUrl2).filter { !it.isNullOrBlank() }.distinct().forEach { fuente ->
                    try { container.clientImageStore.ensureLocal(fuente, container.driveHelper) } catch (_: Exception) { /* continúa con la siguiente foto */ }
                }
            }
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
