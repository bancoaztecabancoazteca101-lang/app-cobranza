package com.example.matrizapp

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Repeat
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
    val fuente by viewModel.fuente.collectAsState()
    val plantillaTT by viewModel.plantillaTT.collectAsState()
    val plantillaRef by viewModel.plantillaRef.collectAsState()
    val agente by viewModel.agente.collectAsState()
    val contactoGestor by viewModel.contactoGestor.collectAsState()
    val subIdSeleccionado by viewModel.subscriptionIdSeleccionado.collectAsState()
    val delaySegundos by viewModel.delaySegundos.collectAsState()
    val vecesPorDia by viewModel.vecesPorDia.collectAsState()
    val horasEntreRepeticion by viewModel.horasEntreRepeticion.collectAsState()
    val enviando by viewModel.enviando.collectAsState()
    val progreso by viewModel.progreso.collectAsState()

    var permisosOk by remember { mutableStateOf(SmsHelper.tienePermisos(context)) }
    var lineas by remember { mutableStateOf(SmsHelper.lineasActivas(context)) }
    var mostrarConfirmacionEnvio by remember { mutableStateOf(false) }
    var mostrarConfirmacionProgramar by remember { mutableStateOf(false) }
    var mostrarOpciones by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resultados ->
        permisosOk = resultados.values.all { it }
        if (permisosOk) lineas = SmsHelper.lineasActivas(context)
        else Toast.makeText(context, "Se necesitan permisos de SMS y teléfono para enviar", Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(Unit) {
        if (!permisosOk) permLauncher.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE))
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

    val seleccionados = contactos.count { it.seleccionado }

    if (mostrarConfirmacionEnvio) {
        AlertDialog(
            onDismissRequest = { mostrarConfirmacionEnvio = false },
            title = { Text("Confirmar envío") },
            text = { Text("Se enviará SMS ahora a $seleccionados contactos, uno por uno con $delaySegundos s de espacio entre cada uno. ¿Continuar?") },
            confirmButton = { TextButton(onClick = { mostrarConfirmacionEnvio = false; viewModel.enviarAhora(context) }) { Text("Enviar") } },
            dismissButton = { TextButton(onClick = { mostrarConfirmacionEnvio = false }) { Text("Cancelar") } }
        )
    }
    if (mostrarConfirmacionProgramar) {
        AlertDialog(
            onDismissRequest = { mostrarConfirmacionProgramar = false },
            title = { Text("Confirmar programación") },
            text = { Text("Se enviará a $seleccionados contactos, repitiendo $vecesPorDia veces con $horasEntreRepeticion h entre cada ronda (empezando ahora mismo). Esto sigue corriendo aunque cierres la app. ¿Continuar?") },
            confirmButton = { TextButton(onClick = { mostrarConfirmacionProgramar = false; viewModel.programarRepeticiones(context) }) { Text("Programar") } },
            dismissButton = { TextButton(onClick = { mostrarConfirmacionProgramar = false }) { Text("Cancelar") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Envío masivo de SMS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Fuente: Filtro Fecha (clientes de hoy) — $seleccionados de ${contactos.size} seleccionados", color = Color.Gray, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(12.dp))

        Text("¿A quién le llega el SMS?", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FuenteSms.values().forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                    RadioButton(selected = fuente == f, onClick = { viewModel.setFuente(f) }, enabled = !enviando)
                    Text(when (f) { FuenteSms.TT -> "Titular"; FuenteSms.REF1 -> "Ref 1"; FuenteSms.REF2 -> "Ref 2" })
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (fuente == FuenteSms.TT) {
            OutlinedTextField(
                value = plantillaTT, onValueChange = { viewModel.setPlantillaTT(it) },
                label = { Text("Mensaje Titular (usa %nombre% y %monto%)") },
                modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = !enviando
            )
        } else {
            OutlinedTextField(
                value = plantillaRef, onValueChange = { viewModel.setPlantillaRef(it) },
                label = { Text("Mensaje Referencia (usa %nombre%, %agente%, %contacto%)") },
                modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = !enviando
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedTextField(
                    value = agente, onValueChange = { viewModel.setAgente(it) }, label = { Text("Nombre del gestor (%agente%)") },
                    modifier = Modifier.weight(1f).padding(end = 4.dp), enabled = !enviando, singleLine = true
                )
                OutlinedTextField(
                    value = contactoGestor, onValueChange = { viewModel.setContactoGestor(it) }, label = { Text("Teléfono contacto (%contacto%)") },
                    modifier = Modifier.weight(1f).padding(start = 4.dp), enabled = !enviando, singleLine = true
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { mostrarOpciones = !mostrarOpciones }) {
            Text(if (mostrarOpciones) "Ocultar opciones de envío" else "Más opciones (línea, tiempo entre SMS, repetición)")
        }

        if (mostrarOpciones) {
            if (lineas.size > 1) {
                Text("Línea de envío", style = MaterialTheme.typography.labelLarge)
                lineas.forEach { linea ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = subIdSeleccionado == linea.subscriptionId, onClick = { viewModel.setSim(linea.subscriptionId) }, enabled = !enviando)
                        Text(linea.etiqueta)
                    }
                }
            } else if (lineas.size == 1 && subIdSeleccionado == null) {
                viewModel.setSim(lineas.first().subscriptionId)
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = delaySegundos.toString(),
                    onValueChange = { it.toIntOrNull()?.let { v -> viewModel.setDelaySegundos(v) } },
                    label = { Text("Segundos entre SMS") },
                    modifier = Modifier.weight(1f).padding(end = 4.dp), enabled = !enviando, singleLine = true
                )
                OutlinedTextField(
                    value = vecesPorDia.toString(),
                    onValueChange = { it.toIntOrNull()?.let { v -> viewModel.setVecesPorDia(v) } },
                    label = { Text("Veces al día") },
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp), enabled = !enviando, singleLine = true
                )
                OutlinedTextField(
                    value = horasEntreRepeticion.toString(),
                    onValueChange = { it.toIntOrNull()?.let { v -> viewModel.setHorasEntreRepeticion(v) } },
                    label = { Text("Horas entre rondas") },
                    modifier = Modifier.weight(1f).padding(start = 4.dp), enabled = !enviando || vecesPorDia <= 1, singleLine = true
                )
            }
            Text("\"Veces al día\" en 1 = solo esta ronda. Más de 1 programa rondas repetidas cada N horas, aunque cierres la app.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { viewModel.seleccionarTodos() }, enabled = !enviando) { Text("Seleccionar todos") }
            TextButton(onClick = { viewModel.deseleccionarTodos() }, enabled = !enviando) { Text("Deseleccionar todos") }
        }

        Spacer(Modifier.height(12.dp))

        Row {
            Button(
                onClick = { mostrarConfirmacionEnvio = true },
                enabled = !enviando && seleccionados > 0,
                modifier = Modifier.weight(1f).padding(end = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClaySmsTeal)
            ) {
                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (enviando) "${progreso.first}/${progreso.second}…" else "Enviar ahora ($seleccionados)")
            }
            if (vecesPorDia > 1) {
                Button(
                    onClick = { mostrarConfirmacionProgramar = true },
                    enabled = !enviando && seleccionados > 0,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClayPrimary)
                ) {
                    Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Programar x$vecesPorDia")
                }
            }
        }

        if (enviando) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = if (progreso.second == 0) 0f else progreso.first.toFloat() / progreso.second,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(12.dp))

        contactos.forEach { contacto ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(checked = contacto.seleccionado, onCheckedChange = { viewModel.toggleSeleccionado(contacto.id) }, enabled = !enviando)
                        Column {
                            Text(contacto.nombre, fontWeight = FontWeight.Bold)
                            Text(contacto.telefono, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    when (contacto.estado) {
                        EstadoEnvio.PENDIENTE -> {}
                        EstadoEnvio.ENVIANDO -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        EstadoEnvio.ENVIADO -> Icon(Icons.Default.CheckCircle, contentDescription = "Enviado", tint = ClayGreenSuccess)
                        EstadoEnvio.FALLIDO -> Icon(Icons.Default.Error, contentDescription = "Falló", tint = ClayMapsRed)
                    }
                }
            }
        }
    }
}
