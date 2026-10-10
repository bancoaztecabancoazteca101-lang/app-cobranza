package com.example.matrizapp.wear

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SECCIONES = listOf("Matriz", "Solicitud", "Filtro Fecha", "Control", "Semana 6")

class WearMainActivity : ComponentActivity() {
    private var snapshot by mutableStateOf<JSONObject?>(null)
    private var syncError by mutableStateOf<String?>(null)
    private var selectedSection by mutableStateOf<String?>(null)
    private var selectedRecord by mutableStateOf<JSONObject?>(null)
    private var lastUpdated by mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadCache()
        setContent {
            MaterialTheme {
                Scaffold(timeText = { TimeText() }) {
                    when {
                        selectedRecord != null -> DetailScreen(selectedRecord!!) { selectedRecord = null }
                        selectedSection != null -> SectionScreen(
                            selectedSection!!, snapshot?.optJSONArray(selectedSection!!) ?: JSONArray(),
                            onBack = { selectedSection = null }, onSelect = { selectedRecord = it }
                        )
                        else -> HomeScreen(
                            updatedAt = lastUpdated, error = syncError,
                            counts = SECCIONES.associateWith { snapshot?.optJSONArray(it)?.length() ?: 0 },
                            onOpen = { selectedSection = it }, onSync = { requestSnapshot() }
                        )
                    }
                }
            }
        }
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshCacheRunnable = object : Runnable {
        override fun run() { loadCache(); refreshHandler.postDelayed(this, 1200L) }
    }

    override fun onResume() {
        super.onResume()
        loadCache()
        requestSnapshot()
        refreshHandler.postDelayed(refreshCacheRunnable, 1200L)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshCacheRunnable)
        super.onPause()
    }

    private fun loadCache() {
        val prefs = getSharedPreferences(MatrizWearDataService.PREFS, MODE_PRIVATE)
        snapshot = prefs.getString(MatrizWearDataService.KEY_SNAPSHOT, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        lastUpdated = prefs.getLong(MatrizWearDataService.KEY_UPDATED, 0L)
        syncError = prefs.getString(MatrizWearDataService.KEY_ERROR, null)
    }

    private fun requestSnapshot() {
        Thread {
            runCatching {
                val nodes = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
                if (nodes.isEmpty()) runOnUiThread { syncError = "Abre Matriz en el teléfono y conecta el reloj." }
                else {
                    nodes.forEach { node ->
                        Tasks.await(Wearable.getMessageClient(this).sendMessage(node.id, MatrizWearDataService.PATH_REQUEST, byteArrayOf()))
                    }
                    runOnUiThread { syncError = null }
                }
            }.onFailure { e -> runOnUiThread { syncError = e.message ?: "No se pudo conectar." } }
        }.start()
    }
}

@Composable
private fun HomeScreen(updatedAt: Long, error: String?, counts: Map<String, Int>, onOpen: (String) -> Unit, onSync: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("MATRIZ", style = MaterialTheme.typography.title2, fontWeight = FontWeight.Bold)
        Text("Consulta de hojas", style = MaterialTheme.typography.caption2)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(SECCIONES.size) { index ->
                val name = SECCIONES[index]
                Card(onClick = { onOpen(name) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text((counts[name] ?: 0).toString(), style = MaterialTheme.typography.caption2)
                    }
                }
            }
        }
        Text(if (updatedAt > 0) "Datos: " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(updatedAt)) else "Sin datos sincronizados",
            style = MaterialTheme.typography.caption2)
        if (!error.isNullOrBlank()) Text(error, style = MaterialTheme.typography.caption2)
        Button(onClick = onSync) { Text("Sincronizar") }
    }
}

@Composable
private fun SectionScreen(title: String, rows: JSONArray, onBack: () -> Unit, onSelect: (JSONObject) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("Atrás") }
            Text(title, style = MaterialTheme.typography.title3, fontWeight = FontWeight.Bold)
        }
        if (rows.length() == 0) Text("No hay registros guardados.", modifier = Modifier.padding(8.dp))
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(rows.length()) { index ->
                val row = rows.optJSONObject(index) ?: JSONObject()
                Card(onClick = { onSelect(row) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text(row.optString("nombre", row.optString("titulo", "Registro")), fontWeight = FontWeight.Bold, maxLines = 2)
                        val subtitle = listOf(row.optString("estado"), row.optString("numTT"), row.optString("cu"),
                            row.optString("colonia"), row.optString("hora")).filter { it.isNotBlank() && it != "null" }.joinToString(" · ")
                        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.caption2, maxLines = 2)
                        val amount = row.optString("requisito", row.optString("req", row.optString("valor")))
                        if (amount.isNotBlank() && amount != "null") Text("Req: $amount", style = MaterialTheme.typography.caption2)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailScreen(item: JSONObject, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Button(onClick = onBack) { Text("Atrás") }
        Text(item.optString("nombre", item.optString("titulo", "Detalle")), style = MaterialTheme.typography.title3, fontWeight = FontWeight.Bold)
        val keys = item.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in setOf("imagenUrl", "imagenUrl2", "ubicacionRaw", "ubicacion", "nombre", "titulo", "id", "fecha")) continue
            val value = item.optString(key)
            if (value.isNotBlank() && value != "null" && value != "0") Text("${keyLabel(key)}: $value", style = MaterialTheme.typography.body2)
        }
    }
}

private fun keyLabel(key: String): String = when (key) {
    "numTT" -> "Teléfono"; "requisito", "req" -> "Requerido"; "estado" -> "Status"
    "semana", "sem" -> "Semana"; "observaciones" -> "Observaciones"
    "ultimaFechaVisita" -> "Última visita"; "montoCobrado" -> "Ticket"; "visitas" -> "Visitas"
    "seContiene" -> "Se contiene"; "susceptible" -> "Susceptible"; "capital" -> "Capital"; "abono" -> "Abono"
    else -> key.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}
