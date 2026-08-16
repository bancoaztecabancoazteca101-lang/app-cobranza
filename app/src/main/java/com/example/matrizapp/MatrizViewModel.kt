package com.example.matrizapp
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class MatrizViewModel(
    private val repository: SheetsRepository,
    private val matrizDao: MatrizDao,
    private val workManager: WorkManager
) : ViewModel() {
    val matrizList: StateFlow<List<MatrizEntity>> = matrizDao.getAllMatriz()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _pendingPhotoUri = MutableStateFlow<Uri?>(null)
    val pendingPhotoUri: StateFlow<Uri?> = _pendingPhotoUri
    private var pendingPhotoSlot: Int = 1

    fun preparePhotoUri(context: Context, slot: Int = 1): Uri {
        val photoFile = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "IMG_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "com.example.matrizapp.fileprovider", photoFile)
        _pendingPhotoUri.value = uri
        pendingPhotoSlot = slot
        return uri
    }

    fun onPhotoTaken(id: String, success: Boolean) {
        val uri = _pendingPhotoUri.value ?: return
        if (success) {
            viewModelScope.launch {
                if (pendingPhotoSlot == 2) matrizDao.updateImagen2Local(id, uri.toString())
                else matrizDao.updateImagenLocal(id, uri.toString())
                triggerSync()
            }
        }
        _pendingPhotoUri.value = null
    }

    fun guardarGestion(id: String, nuevoEstado: String, observaciones: String) {
        viewModelScope.launch {
            matrizDao.updateGestionLocal(id, nuevoEstado, observaciones)
            triggerSync()
        }
    }

    fun guardarRegistroCompleto(
        id: String, nombre: String, semana: String, requisito: String, numTT: String,
        ref1: String, ref2: String, observaciones: String?, estado: String, ubicacion: String?,
        fecha: Long?, hora: String?, ruta: String?, folioP: String?
    ) {
        viewModelScope.launch {
            matrizDao.updateRegistroCompleto(
                id, nombre, semana, requisito, numTT, ref1, ref2,
                observaciones, estado, ubicacion, fecha, hora, ruta, folioP
            )
            triggerSync()
        }
    }

    /** Crea un registro nuevo (no existe aun en el Sheet). Genera un ID unico tipo hex corto. */
    fun crearRegistro(
        nombre: String, semana: String, requisito: String, numTT: String,
        ref1: String, ref2: String, observaciones: String?, estado: String, ubicacion: String?,
        fecha: Long, hora: String?, ruta: String?, folioP: String?
    ) {
        val nuevoId = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        viewModelScope.launch {
            matrizDao.insertOne(
                MatrizEntity(
                    id = nuevoId, nombre = nombre, semana = semana, requisito = requisito, numTT = numTT,
                    ref1 = ref1, ref2 = ref2, observaciones = observaciones, estado = estado,
                    ubicacion = ubicacion, imagenUrl = null, imagenUrl2 = null, fecha = fecha,
                    hora = hora, ruta = ruta, folioP = folioP, isDirty = true
                )
            )
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