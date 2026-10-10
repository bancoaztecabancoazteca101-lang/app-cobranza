package com.example.matrizapp.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
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
    private var apiToken by mutableStateOf("")
    private var tokenDraft by mutableStateOf("")
    private var showTokenConfig by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(MatrizWearDataService.PREFS, MODE_PRIVATE)
        apiToken = prefs.getString(KEY_API_TOKEN, "").orEmpty()
        showTokenConfig = apiToken.isBlank()
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
                            updatedAt = lastUpdated,
                            error = syncError,
                            counts = SECCIONES.associateWith { snapshot?.optJSONArray(it)?.length() ?: 0 },
                            hasToken = apiToken.isNotBlank(),
                            tokenDraft = tokenDraft,
                            showTokenConfig = showTokenConfig,
                            onTokenDraftChange = { tokenDraft = it },
                            onToggleTokenConfig = { showTokenConfig = !showTokenConfig },
                            onSaveToken = { saveToken() },
                            onOpen = { selectedSection = it },
                            onSync = {
                                if (apiToken.isBlank()) {
                                    showTokenConfig = true
                                    syncError = "Configura el token del servicio para consultar por Wi-Fi."
                                } else requestSnapshot()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadCache()
        if (apiToken.isNotBlank()) requestSnapshot()
    }

    private fun loadCache() {
        val prefs = getSharedPreferences(MatrizWearDataService.PREFS, MODE_PRIVATE)
        snapshot = prefs.getString(MatrizWearDataService.KEY_SNAPSHOT, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        lastUpdated = prefs.getLong(MatrizWearDataService.KEY_UPDATED, 0L)
        syncError = prefs.getString(MatrizWearDataService.KEY_ERROR, null)
    }

    private fun saveToken() {
        val clean = tokenDraft.trim()
        if (clean.length < 24) {
            syncError = "El token debe tener al menos 24 caracteres."
            return
        }
        apiToken = clean
        getSharedPreferences(MatrizWearDataService.PREFS, MODE_PRIVATE)
            .edit().putString(KEY_API_TOKEN, clean).apply()
        tokenDraft = ""
        showTokenConfig = false
        syncError = null
        requestSnapshot()
    }

    /** Consulta directa HTTPS. No envía mensajes al teléfono ni depende de su versión de Matriz. */
    private fun requestSnapshot() {
        val token = apiToken
        if (token.isBlank()) {
            showTokenConfig = true
            syncError = "Falta configurar el token del servicio."
            return
        }
        syncError = "Consultando Matriz por Wi-Fi…"
        Thread {
            runCatching { WearCloudRepository.fetchSnapshot(token) }
                .onSuccess { data ->
                    val jsonText = data.toString()
                    getSharedPreferences(MatrizWearDataService.PREFS, MODE_PRIVATE).edit()
                        .putString(MatrizWearDataService.KEY_SNAPSHOT, jsonText)
                        .putLong(MatrizWearDataService.KEY_UPDATED, System.currentTimeMillis())
                        .remove(MatrizWearDataService.KEY_ERROR)
                        .apply()
                    runOnUiThread {
                        snapshot = data
                        lastUpdated = System.currentTimeMillis()
                        syncError = null
                    }
                }
                .onFailure { e ->
                    val message = e.message ?: "No se pudo consultar el servicio."
                    getSharedPreferences(MatrizWearDataService.PREFS, MODE_PRIVATE)
                        .edit().putString(MatrizWearDataService.KEY_ERROR, message).apply()
                    runOnUiThread { syncError = message }
                }
        }.start()
    }

    companion object {
        private const val KEY_API_TOKEN = "wear_cloud_api_token"
    }
}

@Composable
private fun HomeScreen(
    updatedAt: Long,
    error: String?,
    counts: Map<String, Int>,
    hasToken: Boolean,
    tokenDraft: String,
    showTokenConfig: Boolean,
    onTokenDraftChange: (String) -> Unit,
    onToggleTokenConfig: () -> Unit,
    onSaveToken: () -> Unit,
    onOpen: (String) -> Unit,
    onSync: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text("MATRIZ", style = MaterialTheme.typography.title2, fontWeight = FontWeight.Bold)
        Text("Consulta directa por internet", style = MaterialTheme.typography.caption2)
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
            else "Sin datos descargados",
            style = MaterialTheme.typography.caption2
        )
        if (!error.isNullOrBlank()) Text(error, style = MaterialTheme.typography.caption2, maxLines = 2)
        Button(onClick = onSync) { Text("Sincronizar") }
        Button(onClick = onToggleTokenConfig) { Text(if (showTokenConfig) "Ocultar acceso" else if (hasToken) "Cambiar acceso" else "Configurar acceso") }
        if (showTokenConfig) {
            Text("Token privado del servicio", style = MaterialTheme.typography.caption2)
            BasicTextField(
                value = tokenDraft,
                onValueChange = onTokenDraftChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.body2,
                visualTransformation = PasswordVisualTransformation()
            )
            Button(onClick = onSaveToken) { Text("Guardar token") }
        }
    }
}

@Composable
private fun SectionScreen(title: String, rows: JSONArray, onBack: () -> Unit, onSelect: (JSONObject) -> Unit) {
    var query by remember(title) { mutableStateOf("") }
    val allRows = remember(rows) {
        (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    }
    val filteredRows = remember(allRows, query) {
        val q = query.trim()
        if (q.isEmpty()) allRows
        else allRows.filter { it.toString().contains(q, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("Atrás") }
            Text(title, style = MaterialTheme.typography.title3, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Buscar en todos los registros", style = MaterialTheme.typography.caption2)
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.body2
            )
        }
        Text("${filteredRows.size} registros", style = MaterialTheme.typography.caption2)
        if (filteredRows.isEmpty()) {
            Text(if (allRows.isEmpty()) "No hay registros disponibles." else "No hay coincidencias.", modifier = Modifier.padding(8.dp))
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filteredRows) { row ->
                Card(onClick = { onSelect(row) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text(row.optString("nombre", row.optString("titulo", "Registro")), fontWeight = FontWeight.Bold, maxLines = 2)
                        val subtitle = listOf(
                            row.optString("estado"), row.optString("numTT"), row.optString("cu"),
                            row.optString("semana", row.optString("sem")), row.optString("colonia"),
                            row.optString("hora"), row.optString("fecha"), row.optString("sucursal")
                        ).filter { it.isNotBlank() && it != "null" }.distinct().joinToString(" · ")
                        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.caption2, maxLines = 3)
                        val amount = row.optString("requisito", row.optString("req", row.optString("valor")))
                        if (amount.isNotBlank() && amount != "null") Text("Req: $amount", style = MaterialTheme.typography.caption2)
                        val address = row.optString("ubicacion")
                        if (address.isNotBlank() && address != "null") Text(address, style = MaterialTheme.typography.caption2, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailScreen(item: JSONObject, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Button(onClick = onBack) { Text("Atrás") }
        Text(item.optString("nombre", item.optString("titulo", "Detalle")), style = MaterialTheme.typography.title3, fontWeight = FontWeight.Bold)
        val keys = item.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = item.optString(key)
            if (value.isBlank() || value == "null" || value == "0" || key == "nombre" || key == "titulo") continue
            when (key) {
                "imagenUrl", "imagenUrl2" -> Text(if (value.isNotBlank()) "${keyLabel(key)}: Foto disponible" else "", style = MaterialTheme.typography.body2)
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
