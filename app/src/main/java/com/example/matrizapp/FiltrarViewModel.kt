package com.example.matrizapp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Un registro de Matriz encontrado cerca (<= 10 m) del titular que se está Filtrando, con sus
 * datos completos de contacto (no solo nombre/distancia) para poder llamar/mandar SMS desde
 * la vista rápida. */
data class CercanoDetalle(
    val nombre: String,
    val numTT: String,
    val ref1: String,
    val ref2: String,
    val ubicacion: String?,
    val distanciaM: Int
)

/** El titular con Status = "Filtrar", solo con lo que de verdad se ocupa de él (nombre, foto,
 * dirección) más la lista de registros encontrados cerca con sus datos completos. Guarda también
 * el MatrizEntity original completo (para el diálogo de "Actualizar Gestión", que sí necesita
 * observaciones/estado tal como están en Matriz). */
data class FiltrarItem(
    val id: String,
    val nombre: String,
    val estado: String,
    val imagen: String?,
    val ubicacion: String?,
    val cercanos: List<CercanoDetalle>,
    val original: MatrizEntity
)

/**
 * "Filtrar" ya no depende de la hoja de Google Sheet "Filtrar" ni del script de Apps Script que
 * la alimentaba: se calcula directo de los datos de Matriz ya sincronizados en el teléfono
 * (matriz_table), así que aparece al instante sin esperar a que el script corra y funciona
 * incluso sin conexión.
 *
 * Reglas (confirmadas con el usuario):
 * - Un registro de Matriz aparece aquí cuando su Status dice exactamente "Filtrar".
 * - La idea de Filtrar es traer los datos de contacto completos (Num TT, Ref1, Ref2, dirección)
 *   de los registros de Matriz encontrados a 10 metros o menos del titular, ordenados del más
 *   cercano al más lejano, máximo 7 — del titular mismo solo se necesita nombre/foto/dirección.
 */
private const val RADIO_CERCANOS_METROS = 10.0

class FiltrarViewModel(
    private val matrizDao: MatrizDao,
    private val workManager: WorkManager,
    val driveHelper: DriveHelper
) : ViewModel() {

    val items: StateFlow<List<FiltrarItem>> = matrizDao.getAllMatriz()
        .map { todos -> calcularFiltrar(todos) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun calcularFiltrar(todos: List<MatrizEntity>): List<FiltrarItem> {
        val candidatos = todos.filter { it.estado.trim().equals("Filtrar", ignoreCase = true) }
        if (candidatos.isEmpty()) return emptyList()

        // Coordenadas ya parseadas una sola vez para no repetir el parseo por cada candidato.
        val coords = todos.associateWith { parseLatLngOrden(it.ubicacion) }

        return candidatos.map { item ->
            val miCoord = coords[item]
            val cercanos = if (miCoord == null) emptyList() else {
                todos.asSequence()
                    .filter { it.id != item.id }
                    .mapNotNull { otro -> coords[otro]?.let { otro to distanciaKm(miCoord, it) * 1000.0 } }
                    .filter { (_, metros) -> metros <= RADIO_CERCANOS_METROS }
                    .sortedBy { (_, metros) -> metros }
                    .take(7)
                    .map { (otro, metros) ->
                        CercanoDetalle(
                            nombre = otro.nombre, numTT = otro.numTT, ref1 = otro.ref1, ref2 = otro.ref2,
                            ubicacion = otro.ubicacion, distanciaM = metros.toInt()
                        )
                    }
                    .toList()
            }
            FiltrarItem(
                id = item.id, nombre = item.nombre, estado = item.estado,
                imagen = item.imagenUrl, ubicacion = item.ubicacion, cercanos = cercanos,
                original = item
            )
        }
    }

    /** Guarda el cambio directo sobre el registro de Matriz (misma fila que ya existe ahí,
     * no hay una tabla "Filtrar" aparte) y dispara el push normal hacia el Sheet de Matriz. */
    fun guardarGestion(id: String, nuevoEstado: String, obs: String) {
        viewModelScope.launch {
            matrizDao.updateGestionLocal(id, nuevoEstado, obs)
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
