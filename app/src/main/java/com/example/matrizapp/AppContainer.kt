package com.example.matrizapp
import android.content.Context
import androidx.work.WorkManager
import com.google.api.services.drive.Drive
import com.google.api.services.sheets.v4.Sheets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(val context: Context) {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(context) }
    val sheetsService: Sheets by lazy { GoogleSheetsServiceProvider.getService(context) }
    val driveService: Drive by lazy { GoogleSheetsServiceProvider.getDriveService(context) }

    val driveHelper: DriveHelper by lazy { DriveHelper(driveService, context) }
    val audioHelper: AudioHelper by lazy { AudioHelper(context) }
    val sem6CacheStore: Sem6CacheStore by lazy { Sem6CacheStore(context) }
    val notificacionesHelper: NotificacionesHelper by lazy { NotificacionesHelper(context) }

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
    val llamadaAutomaticaScheduler: LlamadaAutomaticaScheduler by lazy {
        LlamadaAutomaticaScheduler(context, database.bloqueHorarioDao())
    }

    init {
        // Siembra las 60 plantillas por defecto (5 semanas x 2 tipos x 6 variantes) solo la
        // primera vez que la tabla está vacía — instalaciones nuevas y las que se actualizan
        // desde una versión sin esta tabla quedan igual cubiertas.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val dao = database.plantillaSmsDao()
            if (dao.contar() == 0) {
                dao.insertarTodas(PlantillasSemillaSms.defaults())
            }
        }
    }
}