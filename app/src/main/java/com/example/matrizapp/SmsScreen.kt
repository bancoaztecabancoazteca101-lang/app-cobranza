package com.example.matrizapp

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SmsScreen(viewModel: SmsViewModel) {
    val context = LocalContext.current
    val contactos by viewModel.contactos.collectAsState()
    val plantilla by viewModel.plantilla.collectAsState()
    val subIdSeleccionado by viewModel.subscriptionIdSeleccionado.collectAsState()
    val enviando by viewModel.enviando.collectAsState()
    val progreso by viewModel.progreso.collectAsState()

    var permisosOk by remember { mutableStateOf(SmsHelper.tienePermisos(context)) }
    var lineas by remember { mutableStateOf(SmsHelper.lineasActivas(context)) }
    var mostrarConfirmacion by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resultados ->
        permisosOk = resultados.values.all { it }
        if (permisosOk) lineas = SmsHelper.lineasActivas(context)
        else Toast.makeText(context, "Se necesitan permisos de SMS y teléfono para enviar", Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(Unit) {
        if (!permisosOk) {
            permLauncher.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE))
        }
    }

    if (!permisosOk) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Se necesitan permisos de SMS", color = Color.Gray)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permLauncher.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)) }) {
                    Text("Conceder permisos")
                }
            }
        }
        return
    }

    if (mostrarConfirmacion) {
        AlertDialog(
            onDismissRequest = { mostrarConfirmacion = false },
            title = { Text("Confirmar envío masivo") },
            text = { Text("Se enviará SMS a ${contactos.size} contactos, uno por uno con unos segundos de espacio entre cada uno. Esto no se puede cancelar a la mitad. ¿Continuar?") },
            confirmButton = {
                TextButton(onClick = {
                    mostrarConfirmacion = false
                    viewModel.enviarATodos(context)
                }) { Text("Enviar") }
            },
            dismissButton = { TextButton(onClick = { mostrarConfirmacion = false }) { Text("Cancelar") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Envío masivo de SMS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Fuente: Filtro Fecha (clientes de hoy) — ${contactos.size} contactos", color = Color.Gray, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = plantilla,
            onValueChange = { viewModel.setPlantilla(it) },
            label = { Text("Mensaje (usa %nombre% para insertar el nombre)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            enabled = !enviando
        )

        if (lineas.size > 1) {
            Spacer(Modifier.height(12.dp))
            Text("Línea de envío", style = MaterialTheme.typography.labelLarge)
            Column {
                lineas.forEach { linea ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(
                            selected = subIdSeleccionado == linea.subscriptionId,
                            onClick = { viewModel.setSim(linea.subscriptionId) },
                            enabled = !enviando
                        )
                        Text(linea.etiqueta)
                    }
                }
            }
        } else if (lineas.size == 1 && subIdSeleccionado == null) {
            viewModel.setSim(lineas.first().subscriptionId)
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { mostrarConfirmacion = true },
            enabled = !enviando && contactos.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ClaySmsTeal)
        ) {
            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (enviando) "Enviando ${progreso.first}/${progreso.second}…" else "Enviar a todos (${contactos.size})")
        }

        if (enviando) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (progreso.second == 0) 0f else progreso.first.toFloat() / progreso.second },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(contactos, key = { it.id }) { contacto ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(contacto.nombre, fontWeight = FontWeight.Bold)
                            Text(contacto.telefono, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                        when (contacto.estado) {
                            EstadoEnvio.PENDIENTE -> Text("Pendiente", color = Color.Gray)
                            EstadoEnvio.ENVIANDO -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            EstadoEnvio.ENVIADO -> Icon(Icons.Default.CheckCircle, contentDescription = "Enviado", tint = ClayGreenSuccess)
                            EstadoEnvio.FALLIDO -> Icon(Icons.Default.Error, contentDescription = "Falló", tint = ClayMapsRed)
                        }
                    }
                }
            }
        }
    }
}
