package com.example.matrizapp
import android.net.Uri
import android.content.Context
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class SolicitudViewModel(
    private val repository: SheetsRepository,
    private val solicitudDao: SolicitudDao,
    private val audioHelper: AudioHelper,
    private val workManager: WorkManager,
    val driveHelper: DriveHelper
) : ViewModel() {
    val solicitudList: StateFlow<List<SolicitudEntity>> = solicitudDao.getAllSolicitud()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    fun startRecording() {
        audioHelper.startRecording()
        _isRecording.value = true
    }

    fun stopRecordingAndSave(id: String) {
        val path = audioHelper.stopRecording()
        _isRecording.value = false
        if (path != null) {
            viewModelScope.launch {
                solicitudDao.updateAudioLocal(id, Uri.fromFile(File(path)).toString())
                triggerSync()
            }
        }
    }

    /**
     * Para grabar audio en un registro NUEVO que aún no se guarda (no tiene fila en la
     * base de datos todavía). Solo detiene la grabación y regresa la ruta local; el
     * llamador debe pasarla a crearRegistro() al guardar.
     */
    fun stopRecordingLocalOnly(): String? {
        val path = audioHelper.stopRecording()
        _isRecording.value = false
        return path?.let { Uri.fromFile(File(it)).toString() }
    }

    fun cancelRecording() {
        audioHelper.cancelRecording()
        _isRecording.value = false
    }

    fun guardarEstado(id: String, nuevoEstado: String) {
        viewModelScope.launch {
            solicitudDao.updateEstadoLocal(id, nuevoEstado)
            triggerSync()
        }
    }

    fun guardarRegistroCompleto(
        id: String, nombre: String, numero: String, sucursal: String,
        ubicacion: String, nombreRef1: String, ref1: String, nombreRef2: String, ref2: String,
        observaciones: String, estado: String, fechaHoraEditada: Long? = null
    ) {
        viewModelScope.launch {
            // fechaHoraEditada: si el usuario tocó el campo y lo cambió, se respeta ese valor.
            // Si no lo tocó (null), se conserva la fecha/hora automática que ya tenía el registro,
            // o se rellena con la hora actual si nunca la tuvo (registros viejos sin este campo).
            solicitudDao.updateCompleto(id, nombre, numero, sucursal, ubicacion, nombreRef1, ref1, nombreRef2, ref2, observaciones, estado, fechaHoraEditada)
            triggerSync()
        }
    }

    fun crearRegistro(
        nombre: String, numero: String, sucursal: String, ubicacion: String,
        nombreRef1: String, ref1: String, nombreRef2: String, ref2: String,
        observaciones: String, estado: String, audioUrl: String? = null,
        imagenUrl: String? = null, imagenUrl2: String? = null,
        imagenUrl3: String? = null, imagenUrl4: String? = null,
        fechaHoraEditada: Long? = null
    ) {
        val nuevoId = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        viewModelScope.launch {
            solicitudDao.insertOne(
                SolicitudEntity(
                    id = nuevoId, nombre = nombre, numero = numero, sucursal = sucursal,
                    ubicacionRaw = ubicacion, imageUrl = imagenUrl, imageUrl2 = imagenUrl2,
                    nombreRef1 = nombreRef1, ref1 = ref1, nombreRef2 = nombreRef2, ref2 = ref2,
                    observaciones = observaciones, audioUrl = audioUrl, estado = estado,
                    imageUrl3 = imagenUrl3, imageUrl4 = imagenUrl4,
                    // Gestor asignado siempre "Flores" (fijo, no editable). Fecha/hora se captura
                    // automático con la hora del dispositivo al crear, salvo que el usuario la
                    // haya editado a mano en el formulario (fechaHoraEditada).
                    gestorAsignado = "Flores", fechaHora = fechaHoraEditada ?: System.currentTimeMillis(),
                    isDirty = true
                )
            )
            triggerSync()
        }
    }

    // --- Fotos: cámara y galería ---
    // pendingPhotoUri guarda a dónde va a escribir la cámara antes de lanzarla (necesario
    // porque TakePicture no devuelve el Uri, solo un boolean de éxito/fracaso).
    private val _pendingPhotoUri = MutableStateFlow<Uri?>(null)
    val pendingPhotoUri: StateFlow<Uri?> = _pendingPhotoUri
    private var pendingPhotoSlot = 1

    /** Prepara el archivo destino para la cámara y devuelve su Uri (para pasarlo al launcher). */
    fun preparePhotoUri(context: Context, slot: Int): Uri {
        val photoFile = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "SOL_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "com.example.matrizapp.fileprovider", photoFile)
        _pendingPhotoUri.value = uri
        pendingPhotoSlot = slot
        return uri
    }

    /** Llamar cuando la cámara (TakePicture) termina, para un registro YA guardado. */
    fun onPhotoTaken(id: String, success: Boolean) {
        val uri = _pendingPhotoUri.value ?: return
        if (success) {
            viewModelScope.launch {
                when (pendingPhotoSlot) {
                    2 -> solicitudDao.updateImagen2Local(id, uri.toString())
                    3 -> solicitudDao.updateImagen3Local(id, uri.toString())
                    4 -> solicitudDao.updateImagen4Local(id, uri.toString())
                    else -> solicitudDao.updateImagenLocal(id, uri.toString())
                }
                triggerSync()
            }
        }
        _pendingPhotoUri.value = null
    }

    /**
     * Llamar cuando la cámara (TakePicture) termina, para un registro NUEVO que aún no se
     * guarda. Solo confirma si sí se tomó la foto y regresa su Uri para pasarla a
     * crearRegistro(); si el usuario canceló, regresa null.
     */
    fun onPhotoTakenLocalOnly(success: Boolean): String? {
        val uri = _pendingPhotoUri.value
        _pendingPhotoUri.value = null
        return if (success) uri?.toString() else null
    }

    /**
     * Copia una imagen elegida de la galería (Uri de content:// del picker del sistema, que
     * puede perder el permiso de lectura tras cerrar la app) a un archivo propio de la app,
     * para un registro YA guardado.
     */
    fun onImagePickedFromGallery(context: Context, id: String, slot: Int, sourceUri: Uri) {
        viewModelScope.launch {
            val localUri = copiarImagenAAppStorage(context, sourceUri)
            if (localUri != null) {
                when (slot) {
                    2 -> solicitudDao.updateImagen2Local(id, localUri.toString())
                    3 -> solicitudDao.updateImagen3Local(id, localUri.toString())
                    4 -> solicitudDao.updateImagen4Local(id, localUri.toString())
                    else -> solicitudDao.updateImagenLocal(id, localUri.toString())
                }
                triggerSync()
            }
        }
    }

    /** Igual que arriba, pero para un registro NUEVO: regresa el Uri local por callback. */
    fun onImagePickedFromGalleryLocalOnly(context: Context, sourceUri: Uri, onResultado: (String?) -> Unit) {
        viewModelScope.launch {
            val localUri = copiarImagenAAppStorage(context, sourceUri)
            onResultado(localUri?.toString())
        }
    }

    private fun copiarImagenAAppStorage(context: Context, sourceUri: Uri): Uri? {
        return try {
            val destino = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "SOL_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destino.outputStream().use { output -> input.copyTo(output) }
            }
            FileProvider.getUriForFile(context, "com.example.matrizapp.fileprovider", destino)
        } catch (e: Exception) { null }
    }

    /**
     * Comparte por WhatsApp el registro: si las imágenes/audio ya están sincronizados a
     * Drive (URL remota), primero los descarga a caché local para poder adjuntarlos como
     * archivos reales (WhatsApp no puede adjuntar una URL http directamente).
     *
     * El texto+fotos se manda en un mensaje. Si el registro tiene audio, NO se manda junto
     * (WhatsApp descarta los adjuntos si se mezclan fotos+audio en un solo envío) — se
     * avisa por [onAudioPendiente] para que la pantalla ofrezca un botón "Enviar audio" y
     * el usuario decida cuándo mandarlo, evitando lanzar dos Intent seguidos hacia WhatsApp.
     *
     * Si el registro es de antes de que existiera el campo Fecha y hora (fechaHora null),
     * se rellena en ese momento con la hora actual para que no falte en el mensaje.
     */
    fun compartirPorWhatsApp(context: android.content.Context, item: SolicitudEntity, onAudioPendiente: (android.net.Uri, String) -> Unit) {
        viewModelScope.launch {
            val itemACompartir = if (item.fechaHora == null) {
                val ahora = System.currentTimeMillis()
                solicitudDao.backfillFechaHoraSiFalta(item.id, ahora)
                triggerSync()
                item.copy(fechaHora = ahora)
            } else item
            val audioUri = shareSolicitudPorWhatsApp(context, itemACompartir, driveHelper)
            if (audioUri != null) onAudioPendiente(audioUri, itemACompartir.nombre)
        }
    }

    /**
     * Resuelve una imagen (URL de Drive o ruta relativa del OCR) a un Uri local
     * descargable/mostrable antes de abrir el visor, ya que Coil no puede cargar
     * rutas relativas tipo "Solicitud_Images/archivo.jpg" directamente.
     */
    fun resolverImagenParaVista(context: android.content.Context, raw: String, onResultado: (String?) -> Unit) {
        viewModelScope.launch {
            val uri = resolverArchivoComoUri(context, driveHelper, raw, "vista_${System.currentTimeMillis()}.jpg")
            onResultado(uri?.toString())
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