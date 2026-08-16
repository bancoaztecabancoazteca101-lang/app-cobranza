package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class PaseCarteraViewModel(
    private val repository: SheetsRepository,
    private val paseDao: PaseCarteraDao,
    private val workManager: WorkManager
) : ViewModel() {
    val paseList: StateFlow<List<PaseEntity>> = paseDao.getAllPase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun guardarEstado(id: String, nuevoEstado: String) {
        viewModelScope.launch {
            paseDao.updateEstadoLocal(id, nuevoEstado)
            triggerSync()
        }
    }

    private fun triggerSync() {
        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(3, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork("sync_app_data", ExistingWorkPolicy.REPLACE, syncRequest)
    }
}