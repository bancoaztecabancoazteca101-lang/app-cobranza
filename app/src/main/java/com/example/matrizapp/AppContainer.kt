package com.example.matrizapp
import android.content.Context
import androidx.work.WorkManager
import com.google.api.services.drive.Drive
import com.google.api.services.sheets.v4.Sheets

class AppContainer(private val context: Context) {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(context) }
    val sheetsService: Sheets by lazy { GoogleSheetsServiceProvider.getService(context) }
    val driveService: Drive by lazy { GoogleSheetsServiceProvider.getDriveService(context) }

    val driveHelper: DriveHelper by lazy { DriveHelper(driveService, context) }
    val audioHelper: AudioHelper by lazy { AudioHelper(context) }

    val repository: SheetsRepository by lazy {
        SheetsRepository(
            sheetsService = sheetsService,
            matrizDao = database.matrizDao(),
            paseDao = database.paseDao(),
            solicitudDao = database.solicitudDao(),
            filtroDao = database.filtroDao(),
            filtrarDao = database.filtrarDao(),
            controlDao = database.controlDao()
        )
    }
    val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
}