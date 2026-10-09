package com.example.matrizapp

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Lee datos reales de Room y la caché real de Semana 6. Solo consulta; no modifica Matriz. */
class MatrizWearBridgeService : WearableListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_REQUEST) return
        val sourceNode = event.sourceNodeId
        serviceScope.launch {
            runCatching {
                val app = application as MainApplication
                val db = app.container.database
                val allMatriz = db.matrizDao().getAllMatriz().first()
                val solicitudes = db.solicitudDao().getAllSolicitud().first()
                val control = db.controlDao().getAll().first()
                val cache = app.container.sem6CacheStore
                val sem6 = cache.load()?.first.orEmpty().let { cache.filtrarEliminados(it, currentSem6SheetName()) }
                val hoy = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val inicioHoy = hoy.timeInMillis
                val finHoy = (hoy.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
                val semana = (hoy.clone() as Calendar).apply {
                    firstDayOfWeek = Calendar.MONDAY; minimalDaysInFirstWeek = 4
                    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                }
                val inicioSemana = semana.timeInMillis
                val finSemana = (semana.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 7) }.timeInMillis
                val json = JSONObject().put("updatedAt", System.currentTimeMillis())
                json.put("Matriz", JSONArray().apply {
                    allMatriz.sortedByDescending { it.fecha ?: 0L }.take(MAX_ROWS).forEach { item ->
                        put(JSONObject().apply {
                            put("id", item.id); put("nombre", item.nombre); put("semana", item.semana)
                            put("requisito", item.requisito); put("numTT", item.numTT); put("estado", item.estado)
                            put("observaciones", item.observaciones.orEmpty()); put("hora", item.hora.orEmpty())
                            put("ruta", item.ruta.orEmpty()); put("fecha", item.fecha ?: 0L)
                            put("ubicacion", item.ubicacion.orEmpty())
                        })
                    }
                })
                json.put("Solicitud", JSONArray().apply {
                    solicitudes.take(MAX_ROWS).forEach { item ->
                        put(JSONObject().apply {
                            put("id", item.id); put("nombre", item.nombre); put("numero", item.numero.orEmpty())
                            put("sucursal", item.sucursal.orEmpty()); put("estado", item.estado)
                            put("observaciones", item.observaciones.orEmpty()); put("ubicacion", item.ubicacionRaw.orEmpty())
                            put("fecha", item.fechaHora ?: 0L)
                        })
                    }
                })
                val hoyItems = allMatriz.filter { (it.fecha ?: 0L) in inicioHoy until finHoy }
                json.put("Filtro Fecha", JSONArray().apply {
                    hoyItems.sortedByDescending { it.fecha ?: 0L }.take(MAX_ROWS).forEach { item ->
                        put(JSONObject().apply {
                            put("id", item.id); put("nombre", item.nombre); put("numTT", item.numTT)
                            put("estado", item.estado); put("requisito", item.requisito)
                            put("montoCobrado", item.montoCobrado ?: 0.0); put("hora", item.hora.orEmpty())
                            put("fecha", item.fecha ?: 0L)
                        })
                    }
                })
                val hoyActivos = hoyItems.filterNot { it.estado.equals("PASE", true) }
                val semanaActiva = allMatriz.filter {
                    (it.fecha ?: 0L) in inicioSemana until finSemana && !it.estado.equals("PASE", true)
                }
                fun requerido(items: List<MatrizEntity>) = items.sumOf {
                    it.requisito.replace(",", "").replace("$", "").trim().toDoubleOrNull() ?: 0.0
                }
                json.put("Control", JSONArray().apply {
                    put(JSONObject().put("titulo", "Requerido hoy").put("valor", requerido(hoyActivos)))
                    put(JSONObject().put("titulo", "Requerido semana").put("valor", requerido(semanaActiva)))
                    control.take(MAX_ROWS).forEach { put(JSONObject().put("titulo", it.semana).put("valor", it.requerido)) }
                })
                json.put("Semana 6", JSONArray().apply {
                    sem6.take(MAX_ROWS).forEach { item ->
                        put(JSONObject().apply {
                            put("id", item.id); put("nombre", item.nombre); put("cu", item.cu)
                            put("sem", item.sem); put("req", item.req); put("colonia", item.colonia)
                            put("visitas", item.visitas); put("ultimaFechaVisita", item.ultimaFechaVisita)
                            put("numTT", item.numTT); put("capital", item.capital); put("abono", item.abono)
                            put("seContiene", item.seContiene); put("susceptible", item.susceptible)
                            put("observaciones", item.observaciones)
                        })
                    }
                })
                val bytes = json.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_PAYLOAD_BYTES) { "La consulta es demasiado grande para el reloj." }
                com.google.android.gms.tasks.Tasks.await(Wearable.getMessageClient(this@MatrizWearBridgeService).sendMessage(sourceNode, PATH_SNAPSHOT, bytes))
            }.onFailure { error ->
                Log.e(TAG, "No se pudo preparar la consulta para Wear OS", error)
                runCatching {
                    com.google.android.gms.tasks.Tasks.await(
                        Wearable.getMessageClient(this@MatrizWearBridgeService)
                            .sendMessage(sourceNode, PATH_ERROR, (error.message ?: "Error de sincronización").toByteArray())
                    )
                }
            }
        }
    }

    override fun onDestroy() { serviceScope.cancel(); super.onDestroy() }

    companion object {
        const val PATH_REQUEST = "/matriz/request"
        const val PATH_SNAPSHOT = "/matriz/snapshot"
        const val PATH_ERROR = "/matriz/error"
        private const val TAG = "MatrizWearBridge"
        private const val MAX_ROWS = 35
        private const val MAX_PAYLOAD_BYTES = 90 * 1024
    }
}
