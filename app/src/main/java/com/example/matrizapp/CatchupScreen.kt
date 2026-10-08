package com.example.matrizapp

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Pantalla de Catchup: 1) horarios EDITABLES de las pasadas (antes fijas: 8:15 y 9:15), 2) la cola de clientes de
 * ayer que hoy entrarían al flujo, con acciones para sacarlos (Marcar Pagado / No contactar hoy) o abrirlos en Matriz. */
@Composable
fun CatchupScreen(
    container: AppContainer,
    matrizViewModel: MatrizViewModel,
    onOpenMatriz: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var horarios by remember { mutableStateOf(AutomatizacionPrefs.catchupHorarios(context)) }
    var activa by remember { mutableStateOf(AutomatizacionPrefs.catchupActiva(context)) }
    var cola by remember { mutableStateOf<List<ItemColaCatchup>?>(null) }
    var recarga by remember { mutableStateOf(0) }
    var minutosEstimados by remember { mutableStateOf(0) }
    var confirmarPagado by remember { mutableStateOf<ItemColaCatchup?>(null) }

    // La cola se recalcula al abrir, al tocar actualizar y después de cada acción. Cada pasada del catchup
    // la reduce sola: lo contactado cuenta como contacto de ayer y baja el déficit del cliente.
    LaunchedEffect(recarga) {
        val lista = calcularColaCatchup(context)
        cola = lista
        val cfg = container.database.configuracionAutomatizacionDao().obtenerOSembrar()
        val segundos = lista.filter { !it.excluidoHoy }.sumOf { it.numeros } * (cfg.duracionMaximaLlamada + cfg.segundosPausaEntreLlamadas)
        minutosEstimados = (segundos + 59) / 60
    }

    fun guardarHorarios(nuevos: List<Pair<Int, Int>>) {
        AutomatizacionPrefs.setCatchupHorarios(context, nuevos)
        horarios = AutomatizacionPrefs.catchupHorarios(context)
        // Reprograma las alarmas de hoy con la lista nueva (cancela las anteriores y programa una por horario).
        scope.launch { container.llamadaAutomaticaScheduler.reprogramarTodos() }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Catchup (reintento del día anterior)", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (activa) "Activo: a cada hora de la lista recontacta a quien se dio de alta ayer y se quedó corto de su meta."
                                else "Apagado: solo corren los bloques normales del día.",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray
                            )
                        }
                        Switch(checked = activa, onCheckedChange = {
                            activa = it
                            AutomatizacionPrefs.setCatchupActiva(context, it)
                            scope.launch { container.llamadaAutomaticaScheduler.reprogramarTodos() }
                        })
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Horarios de las pasadas", style = MaterialTheme.typography.titleMedium)
                    Text("Elige a qué horas se repite el flujo (máximo ${AutomatizacionPrefs.MAX_HORARIOS_CATCHUP}). Respeta tu horario permitido de cobranza.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    if (horarios.isEmpty()) Text("Sin horarios: el catchup no correrá a ninguna hora.", color = MaterialTheme.colorScheme.error)
                    horarios.forEach { (h, m) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("%02d:%02d".format(h, m), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { guardarHorarios(horarios - (h to m)) }) { Icon(Icons.Default.Close, contentDescription = "Quitar horario") }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            if (horarios.size >= AutomatizacionPrefs.MAX_HORARIOS_CATCHUP) {
                                Toast.makeText(context, "Máximo ${AutomatizacionPrefs.MAX_HORARIOS_CATCHUP} horarios", Toast.LENGTH_SHORT).show()
                            } else {
                                TimePickerDialog(context, { _, h, m ->
                                    if ((h to m) in horarios) Toast.makeText(context, "Ese horario ya está", Toast.LENGTH_SHORT).show()
                                    else guardarHorarios(horarios + (h to m))
                                }, 9, 0, true).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Agregar horario") }
                    Text("Si una hora de hoy ya pasó, esa pasada corre mañana.", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Cola de hoy: ${cola?.size ?: "…"} cliente(s)", style = MaterialTheme.typography.titleMedium)
                    if ((cola?.isNotEmpty()) == true) Text("≈ $minutosEstimados min por pasada en el peor caso (todos los números, con duración máxima y pausa).", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                IconButton(onClick = { recarga++ }) { Icon(Icons.Default.Refresh, contentDescription = "Actualizar cola") }
            }
        }
        if (cola?.isEmpty() == true) {
            item { Text("Nadie pendiente: no hay clientes de ayer con contactos por completar.", color = Color.Gray) }
        }
        items(cola.orEmpty(), key = { it.registro.id }) { item ->
            val r = item.registro
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(r.nombre, style = MaterialTheme.typography.titleSmall)
                    Text("Sem ${item.semana} · faltan ${item.deficit} contacto(s) · ayer ${item.contactosAyer} · ${item.numeros} número(s) · estado: ${r.estado.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    if (item.excluidoHoy) Text("Excluido hoy: no se le llamará ni se le mandará SMS.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { onOpenMatriz(r.id) }) { Text("Abrir") }
                        OutlinedButton(onClick = { confirmarPagado = item }) { Text("Marcar Pagado") }
                        OutlinedButton(onClick = {
                            AutomatizacionPrefs.excluirHoy(context, r.id, !item.excluidoHoy)
                            recarga++
                        }) { Text(if (item.excluidoHoy) "Reanudar" else "No hoy") }
                    }
                }
            }
        }
    }

    confirmarPagado?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmarPagado = null },
            title = { Text("¿Marcar como Pagado?") },
            text = { Text("${item.registro.nombre} cambiará a estado Pagado (se sincroniza con la hoja) y saldrá del catchup y de los bloques. Úsalo solo si el pago ya está confirmado; si aún estás validando el comprobante, usa \"No hoy\".") },
            confirmButton = {
                TextButton(onClick = {
                    matrizViewModel.guardarGestion(item.registro.id, "Pagado", item.registro.observaciones ?: "")
                    confirmarPagado = null
                    scope.launch { kotlinx.coroutines.delay(600); recarga++ }
                }) { Text("Sí, marcar Pagado") }
            },
            dismissButton = { TextButton(onClick = { confirmarPagado = null }) { Text("Cancelar") } }
        )
    }
}
