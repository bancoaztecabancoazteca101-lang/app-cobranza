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

/**
 * "Filtrar" ya no depende de la hoja de Google Sheet "Filtrar" ni del script de Apps Script que
 * la alimentaba: se calcula directo de los datos de Matriz ya sincronizados en el teléfono
 * (matriz_table), así que aparece al instante sin esperar a que el script corra y funciona
 * incluso sin conexión.
 *
 * Reglas (confirmadas con el usuario):
 * - Un registro de Matriz aparece aquí cuando su Status dice exactamente "Filtrar".
 * - "Cercanos por GPS" son otros registros de Matriz (cualquier status) a 30 metros o menos,
 *   ordenados del más cercano al más lejano, máximo 7.
 */
private const val RADIO_CERCANOS_METROS = 30.0

class FiltrarViewModel(
    private val matrizDao: MatrizDao,
    private val workManager: WorkManager
) : ViewModel() {

    val items: StateFlow<List<FiltrarEntity>> = matrizDao.getAllMatriz()
        .map { todos -> calcularFiltrar(todos) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun calcularFiltrar(todos: List<MatrizEntity>): List<FiltrarEntity> {
        val candidatos = todos.filter { it.estado.trim().equals("Filtrar", ignoreCase = true) }
        if (candidatos.isEmpty()) return emptyList()

        // Coordenadas ya parseadas una sola vez para no repetir el parseo por cada candidato.
        val coords = todos.associateWith { parseLatLngOrden(it.ubicacion) }

        return candidatos.map { item ->
            val miCoord = coords[item]
            val referenciasTexto = if (miCoord == null) null else {
                val cercanos = todos.asSequence()
                    .filter { it.id != item.id }
                    .mapNotNull { otro -> coords[otro]?.let { otro to distanciaKm(miCoord, it) * 1000.0 } }
                    .filter { (_, metros) -> metros <= RADIO_CERCANOS_METROS }
                    .sortedBy { (_, metros) -> metros }
                    .take(7)
                    .toList()
                if (cercanos.isEmpty()) null
                else cercanos.joinToString("\n") { (otro, metros) -> "${otro.nombre} (${metros.toInt()} m)" }
            }
            FiltrarEntity(
                id = item.id, nombre = item.nombre, semana = item.semana, requerido = item.requisito,
                numTT = item.numTT, referencias = referenciasTexto, observaciones = item.observaciones,
                estado = item.estado, ubicacion = item.ubicacion, imagen = item.imagenUrl,
                fecha = item.fecha, hora = item.hora
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
