package com.example.matrizapp

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private fun nombreTipoLlamada(t: TipoLlamada): String = when (t) {
    TipoLlamada.TT -> "Titular"
    TipoLlamada.REF1 -> "Ref 1"
    TipoLlamada.REF2 -> "Ref 2"
}

@Composable
fun CallScreen(viewModel: CallViewModel) {
    val context = LocalContext.current
    val tipo by viewModel.tipo.collectAsState()

    var permisosOk by remember { mutableStateOf(SmsHelper.tienePermisos(context) && CallHelper.tienePermisos(context)) }
    var permisoColgarOk by remember { mutableStateOf(CallHelper.tienePermisoColgar(context)) }
    var servicioAccesibilidadOk by remember { mutableStateOf(CallAccessibilityService.servicioActivo()) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resultados ->
        permisosOk = resultados[Manifest.permission.CALL_PHONE] == true && resultados[Manifest.permission.READ_PHONE_STATE] == true
        if (!permisosOk) Toast.makeText(context, "Se necesitan permisos de llamadas y teléfono", Toast.LENGTH_LONG).show()
    }
    val permColgarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permisoColgarOk = CallHelper.tienePermisoColgar(context)
    }

    LaunchedEffect(Unit) {
        if (!permisosOk) permLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE))
        if (!permisoColgarOk && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            permColgarLauncher.launch(Manifest.permission.ANSWER_PHONE_CALLS)
        }
    }

    // Activar el servicio de accesibilidad se hace en Ajustes del sistema, fuera de la app,
    // así que revisamos su estado cada vez que la pantalla vuelve a primer plano.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                servicioAccesibilidadOk = CallAccessibilityService.servicioActivo()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!permisosOk) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Se necesitan permisos de llamadas", color = Color.Gray)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)) }) {
                    Text("Conceder permisos")
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Llamadas automáticas",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )

        TabRow(selectedTabIndex = tipo.ordinal) {
            TipoLlamada.values().forEach { t ->
                Tab(
                    selected = tipo == t,
                    onClick = { viewModel.setTipo(t) },
                    text = { Text(nombreTipoLlamada(t)) }
                )
            }
        }

        SubMenuLlamadas(
            viewModel = viewModel, tipo = tipo, context = context,
            permisoColgarOk = permisoColgarOk, servicioAccesibilidadOk = servicioAccesibilidadOk,
            onSolicitarPermisoColgar = {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    permColgarLauncher.launch(Manifest.permission.ANSWER_PHONE_CALLS)
                }
            }
        )
    }
}

@Composable
private fun SubMenuLlamadas(
    viewModel: CallViewModel, tipo: TipoLlamada, context: android.content.Context,
    permisoColgarOk: Boolean, servicioAccesibilidadOk: Boolean, onSolicitarPermisoColgar: () -> Unit
) {
    val cola by viewModel.cola.collectAsState()
    val fechaInicio by viewModel.fechaInicio.collectAsState()
    val fechaFin by viewModel.fechaFin.collectAsState()
    val coloniaTexto by viewModel.coloniaTexto.collectAsState()
    val coloniaCargando by viewModel.coloniaCargando.collectAsState()
    val coloniasDisponibles by viewModel.coloniasDisponibles.collectAsState()
    val coloniasCargando by viewModel.coloniasCargando.collectAsState()
    val subIdSeleccionado by viewModel.subscriptionIdSeleccionado.collectAsState()
    val config by viewModel.configFlow(tipo).collectAsState()
    val enviarSmsAlColgar by viewModel.enviarSmsAlColgarFlow(tipo).collectAsState()
    val plantillaSms by viewModel.plantillaSmsFlow(tipo).collectAsState()
    val agenteSms by viewModel.agenteSms.collectAsState()
    val contactoSms by viewModel.contactoSms.collectAsState()
    val llamando by viewModel.llamando.collectAsState()
    val progreso by viewModel.progreso.collectAsState()
    val itemActual by viewModel.itemActual.collectAsState()
    val bloquesProgramadosActivos by viewModel.bloquesProgramadosActivos.collectAsState()

    var lineas by remember { mutableStateOf(SmsHelper.lineasActivas(context)) }
    var mostrarConfirmacionLlamar by remember { mutableStateOf(false) }
    var mostrarConfirmacionProgramar by remember { mutableStateOf(false) }
    var mostrarOpciones by remember { mutableStateOf(false) }
    var mostrarListaColonias by remember { mutableStateOf(false) }
    var silenciado by remember { mutableStateOf(false) }

    val seleccionados = cola.count { it.seleccionado }
    val formatoFecha = remember { SimpleDateFormat("dd/MM/yyyy", Locale("es", "MX")) }

    if (mostrarConfirmacionLlamar) {
        AlertDialog(
            onDismissRequest = { mostrarConfirmacionLlamar = false },
            title = { Text("Confirmar llamadas — ${nombreTipoLlamada(tipo)}") },
            text = { Text("Se marcará a $seleccionados números, uno por uno, esperando a que termine cada llamada y ${config.segundosEntreLlamadas} s de pausa entre cada una. ¿Continuar?") },
            confirmButton = { TextButton(onClick = { mostrarConfirmacionLlamar = false; viewModel.llamarAhora(context) }) { Text("Llamar") } },
            dismissButton = { TextButton(onClick = { mostrarConfirmacionLlamar = false }) { Text("Cancelar") } }
        )
    }
    if (mostrarConfirmacionProgramar) {
        val horaTxt = "%02d:%02d".format(config.horaInicioBloque, config.minutoInicioBloque)
        AlertDialog(
            onDismissRequest = { mostrarConfirmacionProgramar = false },
            title = { Text("Confirmar programación — ${nombreTipoLlamada(tipo)}") },
            text = { Text("Se marcará a $seleccionados números empezando a las $horaTxt, repitiendo ${config.repeticionesBloque} veces cada ${config.horasEntreBloques} h. Sigue corriendo aunque cierres la app. ¿Continuar?") },
            confirmButton = { TextButton(onClick = { mostrarConfirmacionProgramar = false; viewModel.programarBloques(context) }) { Text("Programar") } },
            dismissButton = { TextButton(onClick = { mostrarConfirmacionProgramar = false }) { Text("Cancelar") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Fuente: Matriz — $seleccionados de ${cola.size} en cola", color = Color.Gray, style = MaterialTheme.typography.bodySmall)

        if (!permisoColgarOk) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = ClayMapsRed.copy(alpha = 0.12f))) {
                Column(Modifier.padding(12.dp)) {
                    Text("Falta el permiso \"Responder llamadas\": la app no podrá colgar sola cuando se cumpla la duración máxima.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onSolicitarPermisoColgar) { Text("Conceder permiso") }
                }
            }
        }

        if (!servicioAccesibilidadOk) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = ClayMapsRed.copy(alpha = 0.12f))) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Activa el servicio de accesibilidad \"Matriz App\" para que la app pueda colgar la llamada aunque el sistema (MIUI/Redmi) bloquee el colgado directo.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Abrir ajustes de accesibilidad") }
                }
            }
        }

        if (llamando) {
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = ClaySmsTeal.copy(alpha = 0.15f))) {
                Column(Modifier.padding(12.dp)) {
                    Text("Llamando ahora: ${itemActual?.nombre ?: ""} (${nombreTipoLlamada(tipo)})", fontWeight = FontWeight.Bold)
                    Text(itemActual?.telefono ?: "", color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(
                            onClick = {
                                viewModel.colgarActual(context) { ok ->
                                    if (!ok) Toast.makeText(context, "No se pudo colgar: activa el permiso o el servicio de accesibilidad", Toast.LENGTH_LONG).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ClayMapsRed), modifier = Modifier.padding(end = 8.dp)
                        ) { Icon(Icons.Default.CallEnd, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Colgar") }
                        Button(onClick = {
                            silenciado = !silenciado
                            viewModel.silenciar(context, silenciado)
                        }) {
                            Icon(if (silenciado) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = null)
                            Spacer(Modifier.width(4.dp)); Text(if (silenciado) "Reactivar mic" else "Silenciar")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Rango de fecha", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    val cal = Calendar.getInstance().apply { timeInMillis = fechaInicio }
                    android.app.DatePickerDialog(context, { _, y, m, d ->
                        viewModel.setFechaInicio(Calendar.getInstance().apply { set(y, m, d) }.timeInMillis)
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                },
                enabled = !llamando, modifier = Modifier.weight(1f).padding(end = 4.dp)
            ) { Text(formatoFecha.format(fechaInicio)) }
            Text("a", modifier = Modifier.padding(horizontal = 4.dp))
            OutlinedButton(
                onClick = {
                    val cal = Calendar.getInstance().apply { timeInMillis = fechaFin }
                    android.app.DatePickerDialog(context, { _, y, m, d ->
                        viewModel.setFechaFin(Calendar.getInstance().apply { set(y, m, d) }.timeInMillis)
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                },
                enabled = !llamando, modifier = Modifier.weight(1f).padding(start = 4.dp)
            ) { Text(formatoFecha.format(fechaFin)) }
        }

        Spacer(Modifier.height(8.dp))
        Text("Filtro por Colonia (opcional)", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
                OutlinedTextField(
                    value = coloniaTexto, onValueChange = { viewModel.setColoniaTexto(it) },
                    label = { Text("Nombre de colonia (escribe o elige)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), enabled = !llamando && !coloniaCargando,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (coloniasDisponibles.isEmpty() && !coloniasCargando) viewModel.cargarColoniasDisponibles(context)
                                mostrarListaColonias = true
                            },
                            enabled = !llamando && !coloniasCargando
                        ) {
                            if (coloniasCargando) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.ArrowDropDown, contentDescription = "Ver colonias disponibles")
                        }
                    }
                )
                DropdownMenu(expanded = mostrarListaColonias && coloniasDisponibles.isNotEmpty(), onDismissRequest = { mostrarListaColonias = false }) {
                    coloniasDisponibles.forEach { nombre ->
                        DropdownMenuItem(text = { Text(nombre) }, onClick = { mostrarListaColonias = false; viewModel.seleccionarColoniaDelCatalogo(context, nombre) })
                    }
                }
            }
            Button(onClick = { viewModel.aplicarFiltroColonia(context) }, enabled = !llamando && !coloniaCargando) {
                if (coloniaCargando) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White) else Text("Aplicar")
            }
            if (coloniaTexto.isNotBlank()) TextButton(onClick = { viewModel.limpiarFiltroColonia() }, enabled = !llamando) { Text("Quitar") }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = enviarSmsAlColgar, onCheckedChange = { viewModel.setEnviarSmsAlColgar(tipo, it) }, enabled = !llamando)
            Text("Al colgar, mandar SMS a ese mismo número (como en Tasker)")
        }
        if (enviarSmsAlColgar) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = plantillaSms, onValueChange = { viewModel.setPlantillaSms(tipo, it) },
                label = { Text("SMS para ${nombreTipoLlamada(tipo)} (usa %nombre%, %monto%, %agente%, %contacto%)") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, enabled = !llamando
            )
            Spacer(Modifier.height(4.dp))
            Row {
                OutlinedTextField(
                    value = agenteSms, onValueChange = { viewModel.setAgenteSms(it) }, label = { Text("Nombre del gestor (%agente%)") },
                    modifier = Modifier.weight(1f).padding(end = 4.dp), enabled = !llamando, singleLine = true
                )
                OutlinedTextField(
                    value = contactoSms, onValueChange = { viewModel.setContactoSms(it) }, label = { Text("Teléfono contacto (%contacto%)") },
                    modifier = Modifier.weight(1f).padding(start = 4.dp), enabled = !llamando, singleLine = true
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { mostrarOpciones = !mostrarOpciones }) {
            Text(if (mostrarOpciones) "Ocultar opciones de llamada" else "Más opciones (línea, pausa, bloques)")
        }

        if (mostrarOpciones) {
            if (lineas.size > 1) {
                Text("Línea de llamada", style = MaterialTheme.typography.labelLarge)
                lineas.forEach { linea ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = subIdSeleccionado == linea.subscriptionId, onClick = { viewModel.setSim(linea.subscriptionId) }, enabled = !llamando)
                        Text(linea.etiqueta)
                    }
                }
            } else if (lineas.size == 1 && subIdSeleccionado == null) {
                viewModel.setSim(lineas.first().subscriptionId)
            }

            Spacer(Modifier.height(8.dp))
            CampoNumerico(
                valor = config.segundosEntreLlamadas, onValorValido = { viewModel.setSegundosEntreLlamadas(tipo, it) },
                etiqueta = "Segundos de pausa entre llamadas", modifier = Modifier.fillMaxWidth(), enabled = !llamando, minimo = 1, maximo = 600
            )
            Spacer(Modifier.height(8.dp))
            CampoNumerico(
                valor = config.duracionMaximaSegundos, onValorValido = { viewModel.setDuracionMaximaSegundos(tipo, it) },
                etiqueta = "Duración máxima por llamada (segundos)", modifier = Modifier.fillMaxWidth(), enabled = !llamando, minimo = 5, maximo = 600
            )
            Text("Si nadie contesta o nadie cuelga, la app cuelga sola al llegar a este tiempo (necesita el permiso \"Responder llamadas\").", color = Color.Gray, style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(8.dp))
            Text("Bloques programados", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(
                onClick = {
                    android.app.TimePickerDialog(context, { _, h, m -> viewModel.setHoraInicioBloque(tipo, h, m) }, config.horaInicioBloque, config.minutoInicioBloque, true).show()
                },
                enabled = !llamando
            ) { Text("Hora de inicio: %02d:%02d".format(config.horaInicioBloque, config.minutoInicioBloque)) }

            Spacer(Modifier.height(8.dp))
            Row {
                CampoNumerico(
                    valor = config.horasEntreBloques, onValorValido = { viewModel.setHorasEntreBloques(tipo, it) },
                    etiqueta = "Horas entre bloques", modifier = Modifier.weight(1f).padding(end = 4.dp), enabled = !llamando, minimo = 1, maximo = 72
                )
                CampoNumerico(
                    valor = config.repeticionesBloque, onValorValido = { viewModel.setRepeticionesBloque(tipo, it) },
                    etiqueta = "Repeticiones", modifier = Modifier.weight(1f).padding(start = 4.dp), enabled = !llamando, minimo = 1, maximo = 50
                )
            }
            Text("Ej.: cada 1 hora, 9 repeticiones = 9 bloques de llamadas espaciados 1 h, empezando a la hora de inicio.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { viewModel.seleccionarTodos() }, enabled = !llamando) { Text("Seleccionar todos") }
            TextButton(onClick = { viewModel.deseleccionarTodos() }, enabled = !llamando) { Text("Deseleccionar todos") }
        }

        Spacer(Modifier.height(10.dp))
        Row {
            Button(
                onClick = { if (llamando) viewModel.detenerLlamadas() else mostrarConfirmacionLlamar = true },
                enabled = seleccionados > 0 || llamando,
                modifier = Modifier.weight(1f).padding(end = 4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (llamando) ClayMapsRed else ClaySmsTeal)
            ) {
                Icon(if (llamando) Icons.Default.CallEnd else Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (llamando) "${progreso.first}/${progreso.second}" else "Llamar ($seleccionados)",
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = { mostrarConfirmacionProgramar = true },
                enabled = !llamando && seleccionados > 0,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClayPrimary)
            ) {
                Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    "Programar x${config.repeticionesBloque}",
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (llamando) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = if (progreso.second == 0) 0f else progreso.first.toFloat() / progreso.second, modifier = Modifier.fillMaxWidth())
        }

        // Siempre visible (no solo cuando bloquesProgramadosActivos es true) en los 3 submenús
        // (Titular/Ref1/Ref2) — el bloque programado es uno solo compartido entre los tres
        // tipos, así que este botón detiene el que esté activo sin importar desde qué pestaña
        // se abra.
        Spacer(Modifier.height(8.dp))
        EstadoAutomatizacionBanner(
            activo = bloquesProgramadosActivos,
            textoActivo = "Bloques programados activos",
            textoInactivo = "Sin llamadas automáticas activas",
            colorActivo = ClayMapsRed,
            etiquetaDetener = "Cancelar",
            onDetener = { viewModel.cancelarBloquesProgramados() }
        )

        Spacer(Modifier.height(12.dp))

        cola.forEach { item ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(checked = item.seleccionado, onCheckedChange = { viewModel.toggleSeleccionado(item.id) }, enabled = !llamando)
                        Column {
                            Text(item.nombre, fontWeight = FontWeight.Bold)
                            Text(item.telefono, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    when (item.estado) {
                        EstadoLlamada.PENDIENTE -> {}
                        EstadoLlamada.LLAMANDO -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        EstadoLlamada.HECHA -> Icon(Icons.Default.Call, contentDescription = "Hecha", tint = ClayGreenSuccess)
                    }
                }
            }
        }
    }
}
