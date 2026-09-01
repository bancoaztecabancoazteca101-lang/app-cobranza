@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.matrizapp

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val AZUL_ACENTO = Color(0xFF1565C0)
private val FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm")

/** Desde Android 12 (API 31) el sistema exige que el usuario autorice manualmente las alarmas
 * exactas — sin esto, los bloques quedarían configurados pero jamás se dispararían solos. */
private fun tienePermisoAlarmasExactas(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = context.getSystemService(android.content.Context.ALARM_SERVICE) as AlarmManager
    return alarmManager.canScheduleExactAlarms()
}

@Composable
fun BloqueHorarioScreen(viewModel: BloqueHorarioViewModel) {
    val bloques by viewModel.bloques.collectAsState()
    val automatizacionActiva by viewModel.automatizacionActiva.collectAsState()
    val configAutomatizacion by viewModel.configAutomatizacion.collectAsState()
    val reglasSemana by viewModel.reglasSemana.collectAsState()
    val context = LocalContext.current
    val lineas = remember { SmsHelper.lineasActivas(context) }

    var mostrarDialogoNuevo by remember { mutableStateOf(false) }
    var bloqueEnEdicion by remember { mutableStateOf<BloqueHorarioEntity?>(null) }
    var bloqueAEliminar by remember { mutableStateOf<BloqueHorarioEntity?>(null) }
    var permisoAlarmasOk by remember { mutableStateOf(tienePermisoAlarmasExactas(context)) }

    // Revisa el permiso cada vez que la pantalla vuelve a primer plano (por si el usuario
    // fue a Ajustes a autorizarlo y regresó).
    androidx.compose.runtime.DisposableEffect(Unit) {
        val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permisoAlarmasOk = tienePermisoAlarmasExactas(context)
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { mostrarDialogoNuevo = true },
                containerColor = AZUL_ACENTO
            ) {
                Icon(Icons.Default.Add, contentDescription = "Agregar bloque", tint = Color.White)
            }
        }
    ) { padding ->
        val totalBloquesActivos = bloques.count { it.activo }
        val numerosGuia = remember(bloques) {
            bloques.filter { it.activo }.mapIndexed { i, b -> b.id to (i + 1) }.toMap()
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                InterruptorGeneralCard(
                    activa = automatizacionActiva,
                    onCambiar = { viewModel.setAutomatizacionActiva(it) }
                )
            }
            if (!permisoAlarmasOk) {
                item {
                    PermisoAlarmasBanner(
                        onAutorizar = {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    )
                }
            }
            item {
                ConfiguracionAutomatizacionCard(
                    config = configAutomatizacion,
                    lineas = lineas,
                    onSim = { viewModel.setSimAutomatizacion(it) },
                    onOcultarNumero = { viewModel.setOcultarNumeroAutomatizacion(it) },
                    onSegundosPausa = { viewModel.setSegundosPausaAutomatizacion(it) },
                    onDuracionMaxima = { viewModel.setDuracionMaximaAutomatizacion(it) }
                )
            }
            item {
                FrecuenciaPorSemanaCard(
                    reglas = reglasSemana,
                    totalBloquesActivos = totalBloquesActivos,
                    onToggleOffset = { semana, offset -> viewModel.toggleOffsetEnSemana(semana, offset) }
                )
            }
            if (bloques.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("Sin bloques configurados. Agrega el primero con el botón +.")
                    }
                }
            } else {
                items(bloques, key = { it.id }) { bloque ->
                    Box(Modifier.padding(horizontal = 12.dp)) {
                        BloqueCard(
                            numeroGuia = numerosGuia[bloque.id], // null si el bloque está inactivo -- no cuenta para la programación
                            bloque = bloque,
                            onToggleActivo = { viewModel.toggleActivo(bloque) },
                            onEditar = { bloqueEnEdicion = bloque },
                            onEliminar = { bloqueAEliminar = bloque }
                        )
                    }
                }
            }
        }
    }

    if (mostrarDialogoNuevo) {
        SelectorHoraDialog(
            horaInicial = LocalTime.of(10, 30),
            titulo = "Nuevo bloque",
            onConfirmar = { hora ->
                viewModel.agregarBloque(hora)
                mostrarDialogoNuevo = false
            },
            onCancelar = { mostrarDialogoNuevo = false }
        )
    }

    bloqueEnEdicion?.let { bloque ->
        SelectorHoraDialog(
            horaInicial = bloque.toLocalTime(),
            titulo = "Editar hora del bloque",
            onConfirmar = { nuevaHora ->
                viewModel.editarHora(bloque, nuevaHora)
                bloqueEnEdicion = null
            },
            onCancelar = { bloqueEnEdicion = null }
        )
    }

    bloqueAEliminar?.let { bloque ->
        AlertDialog(
            onDismissRequest = { bloqueAEliminar = null },
            title = { Text("Eliminar bloque") },
            text = { Text("Se eliminará el bloque de las ${bloque.toLocalTime().format(FORMATO_HORA)} y su alarma programada. ¿Continuar?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.eliminarBloque(bloque)
                    bloqueAEliminar = null
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { bloqueAEliminar = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun InterruptorGeneralCard(activa: Boolean, onCambiar: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (activa) Color(0xFFE3F2FD) else Color(0xFFF5F5F5))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Automatización de llamadas y SMS",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (activa) AZUL_ACENTO else Color.Gray
                )
                Text(
                    if (activa) "Activa — se dispara sola cada día en los bloques de abajo"
                    else "Apagada — los bloques quedan guardados pero no van a marcar solos",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Switch(checked = activa, onCheckedChange = onCambiar)
        }
    }
}

/** Configuración exclusiva del flujo automático de Bloques de horario (SIM, ocultar número,
 * pausa entre llamadas, duración máxima) — independiente de la que usa la pantalla manual de
 * Llamadas, para que ajustar una no cambie el comportamiento de la otra. */
@Composable
private fun ConfiguracionAutomatizacionCard(
    config: ConfiguracionAutomatizacionEntity,
    lineas: List<LineaSim>,
    onSim: (Int?) -> Unit,
    onOcultarNumero: (Boolean) -> Unit,
    onSegundosPausa: (Int) -> Unit,
    onDuracionMaxima: (Int) -> Unit
) {
    var expandido by remember { mutableStateOf(false) }
    var textoPausa by remember(config.segundosPausaEntreLlamadas) { mutableStateOf(config.segundosPausaEntreLlamadas.toString()) }
    var textoDuracion by remember(config.duracionMaximaLlamada) { mutableStateOf(config.duracionMaximaLlamada.toString()) }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Configuración de llamadas automáticas", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { expandido = !expandido }) {
                    Text(if (expandido) "Ocultar" else "Editar")
                }
            }
            if (expandido) {
                Spacer(Modifier.height(4.dp))
                if (lineas.size > 1) {
                    Text("Línea para llamadas automáticas", style = MaterialTheme.typography.labelLarge)
                    lineas.forEach { linea ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = config.simSeleccionada == linea.subscriptionId, onClick = { onSim(linea.subscriptionId) })
                            Text(linea.etiqueta)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Ocultar número al marcar", style = MaterialTheme.typography.bodyMedium)
                        Text("Solo confirmado con Movistar — con otras compañías puede no marcar", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = config.ocultarNumero, onCheckedChange = onOcultarNumero)
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        value = textoPausa,
                        onValueChange = { v -> textoPausa = v.filter { it.isDigit() }; v.filter { it.isDigit() }.toIntOrNull()?.let(onSegundosPausa) },
                        label = { Text("Segundos de pausa entre llamadas") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).padding(end = 4.dp), singleLine = true
                    )
                    OutlinedTextField(
                        value = textoDuracion,
                        onValueChange = { v -> textoDuracion = v.filter { it.isDigit() }; v.filter { it.isDigit() }.toIntOrNull()?.let(onDuracionMaxima) },
                        label = { Text("Duración máxima por llamada (seg)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).padding(start = 4.dp), singleLine = true
                    )
                }
                Text(
                    "Si nadie contesta o nadie cuelga, la app cuelga sola al llegar a este tiempo (necesita el permiso \"Responder llamadas\").",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** Frecuencia de contacto editable por semana de atraso. Cada offset seleccionado hace
 * referencia al "número de guía" de un bloque (offset 0 = Bloque #1, el de alta del cliente;
 * offset 2 = Bloque #3, 2 bloques después del suyo, etc.) -- mismo número que ve Diego en la
 * lista de bloques de arriba. */
@Composable
private fun FrecuenciaPorSemanaCard(
    reglas: List<ReglaSemanaEntity>,
    totalBloquesActivos: Int,
    onToggleOffset: (semana: Int, offset: Int) -> Unit
) {
    var expandido by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Frecuencia de contacto por semana", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { expandido = !expandido }) {
                    Text(if (expandido) "Ocultar" else "Editar")
                }
            }
            if (expandido) {
                if (totalBloquesActivos == 0) {
                    Text(
                        "Activa al menos un bloque para poder elegir la frecuencia.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray
                    )
                } else {
                    Text(
                        "El número de cada chip es el Bloque #N que ves arriba, contado desde el bloque en que el cliente se dio de alta (#1 = su propio bloque de alta).",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp)
                    )
                    reglas.sortedBy { it.semana }.forEach { regla ->
                        val seleccionados = regla.offsetsList().toSet()
                        Text("Semana ${regla.semana}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                        (0 until totalBloquesActivos).chunked(6).forEach { fila ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                                fila.forEach { offset ->
                                    FilterChip(
                                        selected = offset in seleccionados,
                                        onClick = { onToggleOffset(regla.semana, offset) },
                                        label = { Text("#${offset + 1}") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermisoAlarmasBanner(onAutorizar: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFF8A6D00))
                Text(
                    "  Falta autorizar alarmas exactas",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF5C4600)
                )
            }
            Text(
                "Sin este permiso, los bloques quedan guardados pero no se van a disparar solos cada día.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF5C4600),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            Button(onClick = onAutorizar) { Text("Autorizar en Ajustes") }
        }
    }
}

@Composable
private fun BloqueCard(
    numeroGuia: Int?,
    bloque: BloqueHorarioEntity,
    onToggleActivo: () -> Unit,
    onEditar: () -> Unit,
    onEliminar: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (bloque.activo) Color(0xFFE3F2FD) else Color(0xFFF5F5F5)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = if (numeroGuia != null) "Bloque #$numeroGuia" else "Inactivo",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (bloque.activo) AZUL_ACENTO else Color.Gray
                )
                Text(
                    text = bloque.toLocalTime().format(FORMATO_HORA),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (bloque.activo) AZUL_ACENTO else Color.Gray
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = bloque.activo, onCheckedChange = { onToggleActivo() })
                IconButton(onClick = onEditar) {
                    Icon(Icons.Default.Edit, contentDescription = "Editar hora")
                }
                IconButton(onClick = onEliminar) {
                    Icon(Icons.Default.Delete, contentDescription = "Eliminar bloque")
                }
            }
        }
    }
}

@Composable
private fun SelectorHoraDialog(
    horaInicial: LocalTime,
    titulo: String,
    onConfirmar: (LocalTime) -> Unit,
    onCancelar: () -> Unit
) {
    val estado = rememberTimePickerState(
        initialHour = horaInicial.hour,
        initialMinute = horaInicial.minute,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text(titulo) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = estado)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirmar(LocalTime.of(estado.hour, estado.minute))
            }) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onCancelar) { Text("Cancelar") }
        }
    )
}
