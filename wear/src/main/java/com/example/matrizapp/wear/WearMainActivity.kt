package com.example.matrizapp.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
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
    private var requesting by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadCache()
        setContent {
            MaterialTheme {
                BackHandler(enabled = selectedRecord != null || selectedSection != null) {
                    if (selectedRecord != null) selectedRecord = null
                    else selectedSection = null
                }
                Scaffold(timeText = { TimeText() }) {
                    when {
                        selectedRecord != null -> DetailScreen(selectedRecord!!)
                        selectedSection != null -> SectionScreen(
                            title = selectedSection!!,
                            rows = snapshot?.optJSONArray(selectedSection!!) ?: JSONArray(),
                            onSelect = { selectedRecord = it }
                        )
                        else -> HomeScreen(
                            updatedAt = lastUpdated,
                            error = syncError,
                            requesting = requesting,
                            counts = SECCIONES.associateWith { snapshot?.optJSONArray(it)?.length() ?: 0 },
                            onOpen = { selectedSection = it },
                            onSync = { requestSnapshot() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadCache()
        requestSnapshot()
    }

    private fun loadCache() {
        val prefs = getSharedPreferences(MatrizWearDataService.PREFS, MODE_PRIVATE)
        snapshot = prefs.getString(MatrizWearDataService.KEY_SNAPSHOT, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        lastUpdated = prefs.getLong(MatrizWearDataService.KEY_UPDATED, 0L)
        syncError = prefs.getString(MatrizWearDataService.KEY_ERROR, null)
    }

    /** Solicita al teléfono emparejado una lectura de su Room/caché real de Matriz. */
    private fun requestSnapshot() {
        if (requesting) return
        requesting = true
        syncError = "Conectando con Matriz del teléfono…"
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    requesting = false
                    syncError = "No hay teléfono conectado. Abre Matriz en el teléfono y verifica el vínculo con el reloj."
                    return@addOnSuccessListener
                }
                var pending = nodes.size
                var sent = false
                nodes.forEach { node ->
                    Wearable.getMessageClient(this)
                        .sendMessage(node.id, MatrizWearDataService.PATH_REQUEST, ByteArray(0))
                        .addOnSuccessListener {
                            sent = true
                            pending--
                            if (pending == 0) {
                                requesting = false
                                if (sent) syncError = null
                            }
                        }
                        .addOnFailureListener { error ->
                            pending--
                            if (pending == 0) {
                                requesting = false
                                if (!sent) syncError = error.localizedMessage
                                    ?: "No se pudo solicitar la consulta al teléfono."
                            }
                        }
                }
            }
            .addOnFailureListener { error ->
                requesting = false
                syncError = error.localizedMessage ?: "No se pudo localizar el teléfono emparejado."
            }
    }
}

@Composable
private fun HomeScreen(
    updatedAt: Long,
    error: String?,
    requesting: Boolean,
    counts: Map<String, Int>,
    onOpen: (String) -> Unit,
    onSync: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text("MATRIZ", style = MaterialTheme.typography.title2, fontWeight = FontWeight.Bold)
        Text("Consulta desde el teléfono", style = MaterialTheme.typography.caption2)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(SECCIONES) { name ->
                Card(onClick = { onOpen(name) }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text((counts[name] ?: 0).toString(), style = MaterialTheme.typography.caption2)
                    }
                }
            }
        }
        Text(
            if (updatedAt > 0) "Datos: " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(updatedAt))
            else "Sin consulta guardada",
            style = MaterialTheme.typography.caption2
        )
        if (!error.isNullOrBlank()) {
            Text(error, style = MaterialTheme.typography.caption2, maxLines = 2)
        }
        Button(onClick = onSync, enabled = !requesting) {
            Text(if (requesting) "Conectando…" else "Actualizar")
        }
    }
}

@Composable
private fun SectionScreen(
    title: String,
    rows: JSONArray,
    onSelect: (JSONObject) -> Unit
) {
    var query by remember(title) { mutableStateOf("") }
    val allRows = remember(rows) {
        (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    }
    val filteredRows = remember(allRows, query) {
        val q = query.trim()
        if (q.isEmpty()) allRows else allRows.filter { it.toString().contains(q, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.title3, fontWeight = FontWeight.Bold, maxLines = 1)
        Text("Buscar en registros", style = MaterialTheme.typography.caption2)
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.body2
        )
        Text("${filteredRows.size} registros", style = MaterialTheme.typography.caption2)
        if (filteredRows.isEmpty()) {
            Text(if (allRows.isEmpty()) "No hay registros disponibles." else "Sin coincidencias.", modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(filteredRows) { row ->
                    Card(onClick = { onSelect(row) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text(
                                row.optString("nombre", row.optString("titulo", "Registro")),
                                fontWeight = FontWeight.Bold,
                                maxLines = 2
                            )
                            val subtitle = listOf(
                                row.optString("estado"), row.optString("numTT"), row.optString("cu"),
                                row.optString("semana", row.optString("sem")), row.optString("colonia"),
                                row.optString("hora"), row.optString("fecha"), row.optString("sucursal")
                            ).filter { it.isNotBlank() && it != "null" }.distinct().joinToString(" · ")
                            if (subtitle.isNotBlank()) {
                                Text(subtitle, style = MaterialTheme.typography.caption2, maxLines = 3)
                            }
                            val amount = row.optString("requisito", row.optString("req", row.optString("valor")))
                            if (amount.isNotBlank() && amount != "null") {
                                Text("Req: $amount", style = MaterialTheme.typography.caption2)
                            }
                            val address = row.optString("ubicacion")
                            if (address.isNotBlank() && address != "null") {
                                Text(address, style = MaterialTheme.typography.caption2, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailScreen(item: JSONObject) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            item.optString("nombre", item.optString("titulo", "Detalle")),
            style = MaterialTheme.typography.title3,
            fontWeight = FontWeight.Bold
        )
        val keys = item.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = item.optString(key)
            if (value.isBlank() || value == "null" || value == "0" || key == "nombre" || key == "titulo") continue
            when (key) {
                "imagenUrl", "imagenUrl2" -> Text("Foto disponible", style = MaterialTheme.typography.body2)
                else -> Text("${keyLabel(key)}: $value", style = MaterialTheme.typography.body2)
            }
        }
    }
}

private fun keyLabel(key: String): String = when (key) {
    "numTT" -> "Teléfono"
    "requisito", "req" -> "Requerido"
    "estado" -> "Status"
    "semana", "sem" -> "Semana"
    "observaciones" -> "Observaciones"
    "ultimaFechaVisita" -> "Última visita"
    "montoCobrado" -> "Ticket"
    "visitas" -> "Visitas"
    "seContiene" -> "Se contiene"
    "susceptible" -> "Susceptible"
    "capital" -> "Capital"
    "abono" -> "Abono"
    else -> key.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}
