package com.example.matrizapp

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class BackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        (applicationContext as MainApplication).container.backupManager.createAutomaticBackup()
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        private const val UNIQUE = "matriz_backup_periodico"
        fun programar(context: Context) {
            val request = androidx.work.PeriodicWorkRequestBuilder<BackupWorker>(12, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.NOT_REQUIRED).build())
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE, androidx.work.ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
