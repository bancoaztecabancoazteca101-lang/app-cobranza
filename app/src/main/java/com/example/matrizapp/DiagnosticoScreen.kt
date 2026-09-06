package com.example.matrizapp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pantalla de diagnóstico: paso "Verificar" del plan de sincronización (compara Sheet vs Room
 * sin corregir nada) + identificación de qué build de la app corre cada teléfono, reportada
 * en la hoja "Dispositivos" cada vez que alguien abre esta pantalla. */
@Composable
fun DiagnosticoScreen(viewModel: DiagnosticoViewModel) {
    LaunchedEffect(Unit) { viewModel.reportarEsteDispositivo() }

    val verificando by viewModel.verificando.collectAsState()
    val resultado by viewModel.resultadoVerificacion.collectAsState()
    val errorVerificacion by viewModel.errorVerificacion.collectAsState()
    val cargandoDispositivos by viewModel.cargandoDispositivos.collectAsState()
    val dispositivos by viewModel.dispositivos.collectAsState()
    val errorDispositivos by viewModel.errorDispositivos.collectAsState()
    val formatoFecha = remember { SimpleDateFormat("dd/MM HH:mm", Locale("es", "MX")) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Diagnóstico", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Este teléfono", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(DeviceInfo.modelo())
                Text("Build: ${DeviceInfo.buildId}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Verificar sincronización (Matriz)", fontWeight = FontWeight.Bold)
        Text(
            "Compara lo que hay en Google Sheets contra la copia local de este teléfono y " +
                "detecta diferencias. Es de solo lectura: no corrige nada por sí sola.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { viewModel.verificarMatriz() }, enabled = !verificando) {
            if (verificando) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Verificar ahora")
        }
        errorVerificacion?.let {
            Spacer(Modifier.height(8.dp))
            Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        resultado?.let { discrepancias ->
            Spacer(Modifier.height(12.dp))
            if (discrepancias.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Sin discrepancias. Todo coincide.")
                }
            } else {
                Text("${discrepancias.size} discrepancia(s) encontrada(s):", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                discrepancias.forEach { d ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${d.nombre} (Id: ${d.id})", fontWeight = FontWeight.Bold)
                            Text(d.tipo.etiqueta, style = MaterialTheme.typography.bodySmall)
                            Text(d.detalle, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Dispositivos reportados", fontWeight = FontWeight.Bold)
        Text(
            "Cada teléfono que abre esta pantalla queda registrado aquí con su build. El que " +
                "tenga un build distinto al más reciente sale marcado.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { viewModel.cargarDispositivos() }, enabled = !cargandoDispositivos) {
            if (cargandoDispositivos) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Cargar lista")
        }
        errorDispositivos?.let {
            Spacer(Modifier.height(8.dp))
            Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (dispositivos.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val buildMasReciente = dispositivos.maxByOrNull { it.ultimoReporte ?: 0L }?.buildId
            dispositivos.sortedByDescending { it.ultimoReporte ?: 0L }.forEach { dispo ->
                val desactualizado = buildMasReciente != null && dispo.buildId != buildMasReciente
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = if (desactualizado) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                             else CardDefaults.cardColors()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (desactualizado) {
                                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(dispo.modelo, fontWeight = FontWeight.Bold)
                        }
                        Text("Build: ${dispo.buildId}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Último reporte: " + (dispo.ultimoReporte?.let { formatoFecha.format(Date(it)) } ?: "—"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
