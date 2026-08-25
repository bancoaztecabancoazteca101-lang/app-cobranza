package com.example.matrizapp
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class MatrizViewModel(
    private val repository: SheetsRepository,
    private val matrizDao: MatrizDao,
    private val workManager: WorkManager,
    val driveHelper: DriveHelper,
    private val notificacionesHelper: NotificacionesHelper
) : ViewModel() {
    init {
        // Igual que en Filtro Fecha: cada vez que cambian los datos de Matriz se revisan los
        // "Retorno"/"App" de HOY con hora y se reprograman/cancelan las alarmas locales. Así no
        // hace falta ir también a Filtro Fecha a repetir la captura. Corre en Dispatchers.IO
        // (no en el hilo principal) para no causar lag al presionar botones cada vez que se
        // sincronizan datos: recorrer toda la lista y llamar repetidamente a AlarmManager es
        // trabajo que no debe bloquear la UI.
        viewModelScope.launch(Dispatchers.IO) {
            matrizDao.getAllMatriz().collect { items ->
                notificacionesHelper.sincronizarAlarmasRetornoMatriz(items)
            }
        }
    }

    private val _orden = MutableStateFlow(OrdenLista.ORIGINAL)
    val orden: StateFlow<OrdenLista> = _orden
    private val _miUbicacion = MutableStateFlow<Pair<Double, Double>?>(null)
    fun setOrden(o: OrdenLista, miUbicacion: Pair<Double, Double>? = null) {
        _orden.value = o
        if (miUbicacion != null) _miUbicacion.value = miUbicacion
    }

    private fun distanciaOrNull(raw: String?, miUbicacion: Pair<Double, Double>): Double? =
        parseLatLngOrden(raw)?.let { distanciaKm(miUbicacion, it) }

    private fun ordenar(list: List<MatrizEntity>, o: OrdenLista, miUbicacion: Pair<Double, Double>?): List<MatrizEntity> = when (o) {
        OrdenLista.FECHA_HORA_RECIENTE -> list.sortedByDescending { it.fecha ?: 0L }
        OrdenLista.FECHA_HORA_ANTIGUA -> list.sortedBy { it.fecha ?: Long.MAX_VALUE }
        OrdenLista.UBICACION_CERCA -> if (miUbicacion == null) list else list.sortedBy { distanciaOrNull(it.ubicacion, miUbicacion) ?: Double.MAX_VALUE }
        OrdenLista.UBICACION_LEJOS -> if (miUbicacion == null) list else list.sortedByDescending { distanciaOrNull(it.ubicacion, miUbicacion) ?: -1.0 }
        OrdenLista.ALFABETICO_AZ -> list.sortedBy { it.nombre.lowercase() }
        OrdenLista.ALFABETICO_ZA -> list.sortedByDescending { it.nombre.lowercase() }
        OrdenLista.ORIGINAL -> list
    }

    val matrizList: StateFlow<List<MatrizEntity>> = combine(matrizDao.getAllMatriz(), _orden, _miUbicacion) { list, o, loc ->
        ordenar(list, o, loc)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    /** Quita la foto del registro (deja el campo vacío) y sincroniza para que también se
     * borre la referencia en el Sheet. */
    fun borrarImagen(id: String, slot: Int) {
        viewModelScope.launch {
            if (slot == 2) matrizDao.updateImagen2Local(id, "") else matrizDao.updateImagenLocal(id, "")
            triggerSync()
        }
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

    /** Igual que guardarRegistroCompleto, pero primero intenta renombrar el ID del registro
     * (columna M) si el usuario lo editó a mano en el formulario. El renombrado en el Sheet
     * necesita conexión; si falla, no se guarda nada (para no dejar el registro inconsistente
     * entre Room y el Sheet) y se avisa el error por onResult. Si el registro es tan nuevo que
     * todavía no existe en el Sheet, el renombrado local simplemente no tiene nada que buscar
     * remotamente y sigue de largo: el ID nuevo se sube tal cual en el próximo push. */
    fun cambiarIdYGuardar(
        idAnterior: String, idNuevo: String, nombre: String, semana: String, requisito: String, numTT: String,
        ref1: String, ref2: String, observaciones: String?, estado: String, ubicacion: String?,
        fecha: Long?, hora: String?, ruta: String?, folioP: String?,
        onResult: (exito: Boolean, error: String?) -> Unit
    ) {
        viewModelScope.launch {
            val idFinal = idNuevo.trim().ifBlank { idAnterior }
            if (idFinal != idAnterior) {
                try {
                    repository.renameRowId(Constants.SHEET_MATRIZ, idAnterior, idFinal, Constants.MatrizCols.COL_ID)
                    matrizDao.renameId(idAnterior, idFinal)
                } catch (e: Exception) {
                    onResult(false, "No se pudo cambiar el ID en el Sheet (revisa tu conexión): ${e.message}")
                    return@launch
                }
            }
            matrizDao.updateRegistroCompleto(
                idFinal, nombre, semana, requisito, numTT, ref1, ref2,
                observaciones, estado, ubicacion, fecha, hora, ruta, folioP
            )
            triggerSync()
            onResult(true, null)
        }
    }

    /** Crea un registro nuevo (no existe aun en el Sheet). El ID lo sugiere generarIdMatriz()
     * (hex corto), pero el formulario permite editarlo antes de guardar por si choca con uno
     * que ya haya generado AppSheet. */
    fun crearRegistro(
        id: String, nombre: String, semana: String, requisito: String, numTT: String,
        ref1: String, ref2: String, observaciones: String?, estado: String, ubicacion: String?,
        fecha: Long, hora: String?, ruta: String?, folioP: String?
    ) {
        val idFinal = id.trim().ifBlank { java.util.UUID.randomUUID().toString().replace("-", "").take(8) }
        viewModelScope.launch {
            matrizDao.insertOne(
                MatrizEntity(
                    id = idFinal, nombre = nombre, semana = semana, requisito = requisito, numTT = numTT,
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

    private val _deleteInProgress = MutableStateFlow(false)
    val deleteInProgress: StateFlow<Boolean> = _deleteInProgress

    /** Elimina el registro tanto en el Google Sheet (columna M = Id) como en Room.
     * Requiere conexion porque el borrado remoto es inmediato (no pasa por la cola de sync). */
    fun eliminarRegistro(id: String, onResult: (exito: Boolean, error: String?) -> Unit) {
        viewModelScope.launch {
            _deleteInProgress.value = true
            try {
                repository.deleteRowById(Constants.SHEET_MATRIZ, id, Constants.MatrizCols.COL_ID)
                matrizDao.deleteById(id)
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message)
            } finally {
                _deleteInProgress.value = false
            }
        }
    }
}