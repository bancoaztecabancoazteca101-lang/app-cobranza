package com.example.matrizapp

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private fun nombreFuente(f: FuenteSms): String = when (f) {
    FuenteSms.TT -> "Titular"
    FuenteSms.REF1 -> "Ref 1"
    FuenteSms.REF2 -> "Ref 2"
}

@Composable
fun SmsScreen(viewModel: SmsViewModel) {
    val context = LocalContext.current
    val fuente by viewModel.fuente.collectAsState()

    var permisosOk by remember { mutableStateOf(SmsHelper.tienePermisos(context)) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resultados ->
        permisosOk = resultados.values.all { it }
        if (!permisosOk) Toast.makeText(context, "Se necesitan permisos de SMS y teléfono para enviar", Toast.LENGTH_LONG).show()
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

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Envío masivo de SMS",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )

        TabRow(selectedTabIndex = fuente.ordinal) {
            FuenteSms.values().forEach { f ->
                Tab(
                    selected = fuente == f,
                    onClick = { viewModel.setFuente(f) },
                    text = { Text(nombreFuente(f)) }
                )
            }
        }

        SubMenuSms(viewModel = viewModel, fuente = fuente, context = context)
    }
}

@Composable
private fun SubMenuSms(viewModel: SmsViewModel, fuente: FuenteSms, context: android.content.Context) {
    val contactos by viewModel.contactos.collectAsState()
    val fechaInicio by viewModel.fechaInicio.collectAsState()
    val fechaFin by viewModel.fechaFin.collectAsState()
    val coloniaTexto by viewModel.coloniaTexto.collectAsState()
    val coloniaCargando by viewModel.coloniaCargando.collectAsState()
    val coloniasDisponibles by viewModel.coloniasDisponibles.collectAsState()
    val coloniasCargando by viewModel.coloniasCargando.collectAsState()
    val plantilla by viewModel.plantillaFlow(fuente).collectAsState()
    val agente by viewModel.agente.collectAsState()
    val contactoGestor by viewModel.contactoGestor.collectAsState()
    val subIdSeleccionado by viewModel.subscriptionIdSeleccionado.collectAsState()
    val config by viewModel.configFlow(fuente).collectAsState()
    val enviando by viewModel.enviando.collectAsState()
    val progreso by viewModel.progreso.collectAsState()
    val repeticionesProgramadasActivas by viewModel.repeticionesProgramadasActivas.collectAsState()

    var lineas by remember { mutableStateOf(SmsHelper.lineasActivas(context)) }
    var mostrarConfirmacionEnvio by remember { mutableStateOf(false) }
    var mostrarConfirmacionProgramar by remember { mutableStateOf(false) }
    var mostrarOpciones by remember { mutableStateOf(false) }
    var mostrarListaColonias by remember { mutableStateOf(false) }

    val seleccionados = contactos.count { it.seleccionado }
    val formatoFecha = remember { SimpleDateFormat("dd/MM/yyyy", Locale("es", "MX")) }

    if (mostrarConfirmacionEnvio) {
        AlertDialog(
            onDismissRequest = { mostrarConfirmacionEnvio = false },
            title = { Text("Confirmar envío — ${nombreFuente(fuente)}") },
            text = { Text("Se enviará SMS ahora a $seleccionados contactos, uno por uno con ${config.delaySegundos} s de espacio entre cada uno. ¿Continuar?") },
            confirmButton = { TextButton(onClick = { mostrarConfirmacionEnvio = false; viewModel.enviarAhora(context) }) { Text("Enviar") } },
            dismissButton = { TextButton(onClick = { mostrarConfirmacionEnvio = false }) { Text("Cancelar") } }
        )
    }
    if (mostrarConfirmacionProgramar) {
        AlertDialog(
            onDismissRequest = { mostrarConfirmacionProgramar = false },
            title = { Text("Confirmar programación — ${nombreFuente(fuente)}") },
            text = { Text("Se enviará a $seleccionados contactos, repitiendo ${config.vecesPorDia} veces con ${config.horasEntreRepeticion} h entre cada ronda (empezando ahora mismo). Esto sigue corriendo aunque cierres la app. ¿Continuar?") },
            confirmButton = { TextButton(onClick = { mostrarConfirmacionProgramar = false; viewModel.programarRepeticiones(context) }) { Text("Programar") } },
            dismissButton = { TextButton(onClick = { mostrarConfirmacionProgramar = false }) { Text("Cancelar") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Fuente: Matriz — $seleccionados de ${contactos.size} seleccionados", color = Color.Gray, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(12.dp))
        Text("Rango de fecha", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    val cal = Calendar.getInstance().apply { timeInMillis = fechaInicio }
                    android.app.DatePickerDialog(context, { _, y, m, d ->
                        val nueva = Calendar.getInstance().apply { set(y, m, d) }
                        viewModel.setFechaInicio(nueva.timeInMillis)
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                },
                enabled = !enviando, modifier = Modifier.weight(1f).padding(end = 4.dp)
            ) { Text(formatoFecha.format(fechaInicio)) }
            Text("a", modifier = Modifier.padding(horizontal = 4.dp))
            OutlinedButton(
                onClick = {
                    val cal = Calendar.getInstance().apply { timeInMillis = fechaFin }
                    android.app.DatePickerDialog(context, { _, y, m, d ->
                        val nueva = Calendar.getInstance().apply { set(y, m, d) }
                        viewModel.setFechaFin(nueva.timeInMillis)
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                },
                enabled = !enviando, modifier = Modifier.weight(1f).padding(start = 4.dp)
            ) { Text(formatoFecha.format(fechaFin)) }
        }

        Spacer(Modifier.height(8.dp))
        Text("Filtro por Colonia (opcional)", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
                OutlinedTextField(
                    value = coloniaTexto, onValueChange = { viewModel.setColoniaTexto(it) },
                    label = { Text("Nombre de colonia (escribe o elige de la lista)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), enabled = !enviando && !coloniaCargando,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (coloniasDisponibles.isEmpty() && !coloniasCargando) viewModel.cargarColoniasDisponibles(context)
                                mostrarListaColonias = true
                            },
                            enabled = !enviando && !coloniasCargando
                        ) {
                            if (coloniasCargando) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.ArrowDropDown, contentDescription = "Ver colonias disponibles")
                        }
                    }
                )
                DropdownMenu(expanded = mostrarListaColonias && coloniasDisponibles.isNotEmpty(), onDismissRequest = { mostrarListaColonias = false }) {
                    coloniasDisponibles.forEach { nombre ->
                        DropdownMenuItem(
                            text = { Text(nombre) },
                            onClick = {
                                mostrarListaColonias = false
                                viewModel.seleccionarColoniaDelCatalogo(context, nombre)
                            }
                        )
                    }
                }
            }
            Button(onClick = { viewModel.aplicarFiltroColonia(context) }, enabled = !enviando && !coloniaCargando) {
                if (coloniaCargando) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("Aplicar")
            }
            if (coloniaTexto.isNotBlank()) {
                TextButton(onClick = { viewModel.limpiarFiltroColonia() }, enabled = !enviando && !coloniaCargando) { Text("Quitar") }
            }
        }
        if (coloniasCargando) {
            Text("Cargando lista de colonias del rango de fecha seleccionado…", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
        if (coloniaCargando) {
            Text("Buscando colonia de cada registro (geocoding local, puede tardar unos segundos)…", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))

        if (fuente == FuenteSms.TT) {
            OutlinedTextField(
                value = plantilla, onValueChange = { viewModel.setPlantilla(fuente, it) },
                label = { Text("Mensaje Titular (usa %nombre% y %monto%)") },
                modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = !enviando
            )
        } else {
            OutlinedTextField(
                value = plantilla, onValueChange = { viewModel.setPlantilla(fuente, it) },
                label = { Text("Mensaje ${nombreFuente(fuente)} (usa %nombre%, %agente%, %contacto%)") },
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
                CampoNumerico(
                    valor = config.delaySegundos, onValorValido = { viewModel.setDelaySegundos(fuente, it) },
                    etiqueta = "Segundos entre SMS", modifier = Modifier.weight(1f).padding(end = 4.dp),
                    enabled = !enviando, minimo = 1, maximo = 300
                )
                CampoNumerico(
                    valor = config.vecesPorDia, onValorValido = { viewModel.setVecesPorDia(fuente, it) },
                    etiqueta = "Veces al día", modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    enabled = !enviando, minimo = 1, maximo = 9
                )
                CampoNumerico(
                    valor = config.horasEntreRepeticion, onValorValido = { viewModel.setHorasEntreRepeticion(fuente, it) },
                    etiqueta = "Horas entre rondas", modifier = Modifier.weight(1f).padding(start = 4.dp),
                    enabled = !enviando || config.vecesPorDia <= 1, minimo = 1, maximo = 12
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
            if (config.vecesPorDia > 1) {
                Button(
                    onClick = { mostrarConfirmacionProgramar = true },
                    enabled = !enviando && seleccionados > 0,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClayPrimary)
                ) {
                    Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Programar x${config.vecesPorDia}")
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

        // Siempre visible (no solo cuando repeticionesProgramadasActivas es true) en los 3
        // submenús (Titular/Ref1/Ref2) — el envío automático programado es uno solo compartido
        // entre las tres fuentes, así que este botón detiene el que esté activo sin importar
        // desde qué pestaña se abra.
        Spacer(Modifier.height(8.dp))
        Card(colors = CardDefaults.cardColors(containerColor = if (repeticionesProgramadasActivas) Color.Red.copy(alpha = 0.12f) else Color.LightGray.copy(alpha = 0.25f))) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (repeticionesProgramadasActivas) "Envíos programados activos" else "Sin envíos automáticos activos", fontWeight = FontWeight.Bold)
                    Text(
                        if (repeticionesProgramadasActivas) "Sigue mandando SMS automáticamente hasta que lo detengas o se agoten las repeticiones."
                        else "No hay ninguna ronda de SMS programada en este momento (Titular, Ref1 o Ref2).",
                        color = Color.Gray, style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(
                    onClick = { viewModel.cancelarRepeticionesProgramadas() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Detener")
                }
            }
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
