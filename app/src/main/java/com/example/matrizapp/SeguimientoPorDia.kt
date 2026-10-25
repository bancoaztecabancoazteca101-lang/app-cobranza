package com.example.matrizapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val PREFS_SEGUIMIENTO_DIA = "seguimiento_por_dia"
private const val KEY_CARTUCHO_SEGUIMIENTO = "cartucho"
private const val KEY_MODO_SEGUIMIENTO = "modo"
private const val KEY_HORARIOS_SEGUIMIENTO = "horarios"
private const val MODO_COMBINADO = "COMBINADO"
private const val MODO_SMS = "SMS"
private const val MODO_LLAMADAS = "LLAMADAS"
private const val WORK_SEGUIMIENTO = "seguimiento_por_dia_cola"
private const val EXTRA_SLOT_SEGUIMIENTO = "slot_seguimiento"
private const val EXTRA_DIA_SEGUIMIENTO = "dia_objetivo_seguimiento"
private const val KEY_DIA_SLOT_PREFIX = "dia_objetivo_slot_"
private const val SLOT_BASE_SEGUIMIENTO = 8200
private const val MAX_HORARIOS_SEGUIMIENTO = 20

private const val KEY_ULT_CONTACTO = "ult_"
private const val KEY_N_CONTACTOS = "n_"
private const val KEY_WHATSAPP = "wa_"

/** Registro propio de Seguimiento Por Día (no usa contacto_log_table para no alterar las cuentas del catchup). */
private fun registrarContactoSeguimiento(context: Context, id: String) {
    val p = preferenciasSeguimiento(context)
    p.edit().putLong(KEY_ULT_CONTACTO + id, System.currentTimeMillis())
        .putInt(KEY_N_CONTACTOS + id, p.getInt(KEY_N_CONTACTOS + id, 0) + 1).apply()
}
private fun contactosSeguimiento(context: Context, id: String): Int =
    preferenciasSeguimiento(context).getInt(KEY_N_CONTACTOS + id, 0)
private fun ultimoContactoSeguimiento(context: Context, id: String): Long =
    preferenciasSeguimiento(context).getLong(KEY_ULT_CONTACTO + id, 0L)
private fun marcarWhatsAppSeguimiento(context: Context, id: String) {
    preferenciasSeguimiento(context).edit().putLong(KEY_WHATSAPP + id, System.currentTimeMillis()).apply()
}
private fun ultimoWhatsAppSeguimiento(context: Context, id: String): Long =
    preferenciasSeguimiento(context).getLong(KEY_WHATSAPP + id, 0L)

/** Por qué un cliente del historial NO se contactaría (null = sí entra al flujo). Mismas reglas que el Worker. */
private fun motivoExclusionSeguimiento(visita: VisitaMapaEntity, registro: MatrizEntity?): String? = when {
    visita.matrizId == null -> "Sin ID de Matriz: no se contacta"
    registro == null -> "Sin registro en Matriz: no se contacta"
    esPagado(registro.estado) -> "Pagado: se excluye"
    registro.semana.trim().toIntOrNull()?.let { it in 1..5 } != true -> "Semana '${registro.semana}' fuera de 1 a 5: se omite"
    else -> null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotonWhatsAppSeguimiento(onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(Icons.Default.Chat, contentDescription = "WhatsApp Business al titular", tint = Color(0xFF0B8F6A))
    }
}

private fun preferenciasSeguimiento(context: Context) =
    context.getSharedPreferences(PREFS_SEGUIMIENTO_DIA, Context.MODE_PRIVATE)

private fun leerHorariosSeguimiento(context: Context): List<Int> {
    val guardados = preferenciasSeguimiento(context).getString(KEY_HORARIOS_SEGUIMIENTO, null)
    if (guardados == null) return listOf(9 * 60, 11 * 60, 14 * 60, 16 * 60)
    return guardados.split(",").mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 0..1439 }.distinct().sorted().take(MAX_HORARIOS_SEGUIMIENTO)
}

private fun guardarHorariosSeguimiento(context: Context, horarios: List<Int>) {
    preferenciasSeguimiento(context).edit()
        .putString(KEY_HORARIOS_SEGUIMIENTO, horarios.distinct().sorted().joinToString(",")).apply()
}

private fun inicioAyerSeguimiento(): Long {
    val c = Calendar.getInstance()
    c.add(Calendar.DAY_OF_YEAR, -1)
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun finAyerSeguimiento(): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

/** Devuelve el rango del día anterior a la fecha local planificada para una alarma. */
private fun rangoAyerParaDiaSeguimiento(diaObjetivo: Long): Pair<Long, Long> {
    val fin = Calendar.getInstance().apply {
        timeInMillis = diaObjetivo
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val inicio = (fin.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return inicio.timeInMillis to fin.timeInMillis
}

private fun textoHoraSeguimiento(minutoDia: Int): String =
    String.format(Locale.ROOT, "%02d:%02d", minutoDia / 60, minutoDia % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeguimientoPorDiaScreen(onAbrirPlantillas: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { preferenciasSeguimiento(context) }
    var cartucho by remember { mutableIntStateOf(prefs.getInt(KEY_CARTUCHO_SEGUIMIENTO, 1).coerceAtLeast(1)) }
    val horarios = remember { mutableStateListOf<Int>().apply { addAll(leerHorariosSeguimiento(context)) } }
    var totalRegistros by remember { mutableIntStateOf(0) }
    var totalIdsCartucho by remember { mutableIntStateOf(0) }
    // Clientes únicos de ayer por cartucho, tal como quedaron en el Mapa (la única fuente del cartucho).
    var clientesPorCartucho by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var listadoVerificacion by remember { mutableStateOf<List<VisitaMapaEntity>>(emptyList()) }
    var estadosPorId by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var totalPendientes by remember { mutableIntStateOf(0) }
    var totalMatrizAyer by remember { mutableIntStateOf(0) }
    var totalConCartucho by remember { mutableIntStateOf(0) }
    var totalSinCartucho by remember { mutableIntStateOf(0) }
    var cargando by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var pestana by remember { mutableIntStateOf(0) }
    var refresco by remember { mutableIntStateOf(0) }
    var registrosPorId by remember { mutableStateOf<Map<String, MatrizEntity>>(emptyMap()) }
    val fechaAyer = remember { SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "MX")).format(Date(inicioAyerSeguimiento())) }

    LaunchedEffect(cartucho) {
        cargando = true
        try {
            val container = (context.applicationContext as MainApplication).container
            val inicioAyer = inicioAyerSeguimiento()
            val finAyer = finAyerSeguimiento()
            val matrizDao = container.database.matrizDao()
            val todos = withContext(Dispatchers.IO) { matrizDao.getAllMatriz().first() }
            val matrizAyer = todos.filter { registro ->
                val fecha = registro.fecha
                fecha != null && fecha >= inicioAyer && fecha < finAyer
            }
            val visitas = withContext(Dispatchers.IO) {
                container.database.visitaMapaDao().getVisitasSemana(inicioAyer, finAyer).first()
            }
            listadoVerificacion = visitas
            clientesPorCartucho = visitas.filter { it.matrizId != null }.groupBy { it.cartucho }
                .mapValues { (_, filas) -> filas.mapNotNull { it.matrizId }.toSet().size }
            estadosPorId = todos.associate { it.id to it.estado }
            val idsConCartucho = visitas.mapNotNull { it.matrizId }.toSet()
            val idsCartuchoSeleccionado = visitas.filter { it.cartucho == cartucho }.mapNotNull { it.matrizId }.toSet()
            // El historial determina el número de visitas; buscar por ID evita que getAllMatriz()
            // oculte una visita por sus filtros generales de nombre.
            val registrosHistorial = withContext(Dispatchers.IO) {
                idsCartuchoSeleccionado.mapNotNull { id -> matrizDao.getById(id) }
            }
            registrosPorId = registrosHistorial.associateBy { it.id }
            totalMatrizAyer = matrizAyer.size
            totalConCartucho = idsConCartucho.size
            totalSinCartucho = matrizAyer.count { it.id !in idsConCartucho }
            // Separar filas reales del historial de IDs únicos que se pueden contactar.
            // Así la lista permite auditar cualquier diferencia sin ocultar filas.
            totalRegistros = visitas.count { it.cartucho == cartucho }
            totalIdsCartucho = idsCartuchoSeleccionado.size
            totalPendientes = registrosHistorial.count { !esPagado(it.estado) }
            if (totalSinCartucho > 0 && registrosHistorial.isEmpty()) {
                mensaje = "Hay registros de ayer en Matriz sin cartucho guardado. No se asignan automáticamente para evitar contactar al grupo equivocado."
            } else if (registrosHistorial.isEmpty() && matrizAyer.isNotEmpty()) {
                mensaje = "Hay registros de ayer en Matriz, pero ninguno tiene asignado el cartucho $cartucho."
            }
        } catch (_: Exception) {
            totalRegistros = 0; totalPendientes = 0
            totalMatrizAyer = 0; totalConCartucho = 0; totalSinCartucho = 0
            totalIdsCartucho = 0; listadoVerificacion = emptyList(); estadosPorId = emptyMap(); clientesPorCartucho = emptyMap(); registrosPorId = emptyMap()
            mensaje = "No se pudo consultar Matriz y el historial de cartuchos del día anterior."
        } finally { cargando = false }
    }

    fun guardarYProgramar() {
        prefs.edit().putInt(KEY_CARTUCHO_SEGUIMIENTO, cartucho).putString(KEY_MODO_SEGUIMIENTO, MODO_COMBINADO).apply()
        guardarHorariosSeguimiento(context, horarios.toList())
        SeguimientoPorDiaScheduler.programar(context)
        mensaje = "Configuración guardada. Se programaron ${horarios.size} horario(s) independientes de los bloques normales."
    }

    val horariosOrdenados = horarios.sorted()
    val filasCartucho = listadoVerificacion.filter { it.cartucho == cartucho }.sortedBy { it.timestamp }
    val clientes = filasCartucho.distinctBy { it.matrizId ?: it.clave }
    val hoyInicio = remember(refresco) { inicioDelDia() }
    val programado = remember(refresco, mensaje) { prefs.getString(KEY_MODO_SEGUIMIENTO, "") == MODO_COMBINADO }

    fun horarioDe(id: String?): String =
        if (id == null || horariosOrdenados.isEmpty()) "—"
        else textoHoraSeguimiento(horariosOrdenados[Math.floorMod(id.hashCode(), horariosOrdenados.size)])

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pestana) {
            listOf("Configuración", "Clientes del flujo", "Resultados").forEachIndexed { i, titulo ->
                Tab(selected = pestana == i, onClick = { pestana = i }, text = { Text(titulo, maxLines = 1) })
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (pestana) {
                0 -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Icon(Icons.Default.CalendarMonth, contentDescription = null)
                                Text("  Seguimiento del día anterior", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(fechaAyer.replaceFirstChar { it.uppercase(Locale("es", "MX")) }, style = MaterialTheme.typography.bodyLarge)
                            Text("Solo procesa registros del día anterior; nunca incorpora registros del día actual.", style = MaterialTheme.typography.bodySmall)
                            Text(if (programado) "Programación: ACTIVA" else "Programación: inactiva (guarda para activarla)", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
        Text("Cartucho que se procesará", style = MaterialTheme.typography.titleMedium)
        Text("Cada cartucho se toma tal como quedó en el Mapa (color y número). Matriz no guarda el cartucho.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ((1..3).toSet() + clientesPorCartucho.keys + cartucho).toSortedSet().forEach { numero ->
                FilterChip(
                    selected = cartucho == numero,
                    onClick = {
                        cartucho = numero
                        prefs.edit().putInt(KEY_CARTUCHO_SEGUIMIENTO, numero).apply()
                    },
                    leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(colorCartucho(numero))) },
                    label = { Text("Cartucho $numero · ${clientesPorCartucho[numero] ?: 0}") }
                )
            }
        }
                    Text("Flujo", style = MaterialTheme.typography.titleMedium)
                    Text("Llamada y después SMS a cada número, igual que en los bloques de horario (titular, Ref1 a Ref4 y contactos extra). Se usa la misma pausa y duración de llamada configuradas en Bloques de horario.", style = MaterialTheme.typography.bodySmall)
        Text("Horarios durante el día", style = MaterialTheme.typography.titleMedium)
        Text("Se ejecutan en orden cronológico. Cada horario es propio de Seguimiento Por Día.", style = MaterialTheme.typography.bodySmall)
        horarios.forEachIndexed { index, minutoDia ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Icon(Icons.Default.Schedule, contentDescription = null)
                Text("Horario ${index + 1}", modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, h, m ->
                        val nuevo = h * 60 + m
                        if (nuevo !in horarios.filterIndexed { i, _ -> i != index }) {
                            horarios[index] = nuevo
                            val ordenados = horarios.distinct().sorted()
                            horarios.clear(); horarios.addAll(ordenados)
                        } else mensaje = "Ese horario ya está agregado."
                    }, minutoDia / 60, minutoDia % 60, true).show()
                }) { Text(textoHoraSeguimiento(minutoDia)) }
                OutlinedButton(onClick = { horarios.removeAt(index) }, enabled = horarios.size > 1) { Text("Quitar") }
            }
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = horarios.size < MAX_HORARIOS_SEGUIMIENTO, onClick = {
            val disponible = (0..1439).firstOrNull { it !in horarios }
            if (disponible != null) {
                horarios.add(disponible)
                val ordenados = horarios.sorted()
                horarios.clear(); horarios.addAll(ordenados)
            }
        }) { Text("Agregar horario") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onAbrirPlantillas) { Text("Editar textos de SMS y WhatsApp (Plantillas de SMS)") }
                    Text("Solo los registros Pagado se excluyen y el estado se revalida antes de cada contacto. Los horarios normales de llamadas no se modifican.", style = MaterialTheme.typography.bodySmall)
        Button(modifier = Modifier.fillMaxWidth(), enabled = horarios.isNotEmpty() && !cargando, onClick = { guardarYProgramar() }) { Text("Guardar y programar") }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
            SeguimientoPorDiaScheduler.cancelar(context)
            mensaje = "Se canceló la programación de Seguimiento Por Día."
        }) { Text("Cancelar programación") }
                }
                1 -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (cargando) "Consultando registros…" else "Registros de ayer en Matriz: $totalMatrizAyer")
                            Text("Registros identificados en el historial: $totalConCartucho")
                            Text("Registros de ayer sin cartucho identificado: $totalSinCartucho")
                            Text("Filas del historial en Cartucho $cartucho: $totalRegistros")
                            Text("Clientes con ID único para seguimiento: $totalIdsCartucho")
                            Text("Pendientes (excluye solo Pagado): $totalPendientes")
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Frecuencia", style = MaterialTheme.typography.titleMedium)
                            if (horariosOrdenados.isEmpty()) Text("Sin horarios configurados.")
                            else {
                                Text("${horariosOrdenados.size} horario(s) al día: ${horariosOrdenados.joinToString(", ") { textoHoraSeguimiento(it) }}")
                                Text("Cada cliente se contacta UNA vez al día, en el horario que le toca. Los horarios se reparten el cartucho entre sí (no se llama a todo el cartucho en cada horario).", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Text("Cartucho que se muestra", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ((1..3).toSet() + clientesPorCartucho.keys + cartucho).toSortedSet().forEach { numero ->
                            FilterChip(
                                selected = cartucho == numero,
                                onClick = {
                                    cartucho = numero
                                    prefs.edit().putInt(KEY_CARTUCHO_SEGUIMIENTO, numero).apply()
                                },
                                leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(colorCartucho(numero))) },
                                label = { Text("Cartucho $numero · ${clientesPorCartucho[numero] ?: 0}") }
                            )
                        }
                    }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        val fechaHora = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("es", "MX"))
                        val contenido = buildString {
                            append("Seguimiento por Día - Cartucho $cartucho\n")
                            append("Clientes: ${clientes.size} · Filas historial: ${filasCartucho.size}\n")
                            append("Nombre | ID Matriz | Estado actual | Horario | Fecha/hora visita\n")
                            clientes.forEachIndexed { i, v ->
                                append("${i + 1}. ${v.nombre} | ${v.matrizId ?: "(sin ID)"} | ${v.matrizId?.let { estadosPorId[it] } ?: "(sin registro Matriz)"} | ${horarioDe(v.matrizId)} | ${fechaHora.format(Date(v.timestamp))}\n")
                            }
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Listado cartucho $cartucho", contenido))
                        mensaje = "Listado copiado. Pégalo en el chat para comparar los nombres e IDs."
                    }) { Text("Copiar listado completo") }
                    Text("Clientes del Cartucho $cartucho (${clientes.size})", style = MaterialTheme.typography.titleMedium)
                    Text("Fuente: historial del Mapa. Botón verde = abrir WhatsApp Business al titular con el texto editable (toque largo = WhatsApp normal).", style = MaterialTheme.typography.bodySmall)
                    clientes.forEachIndexed { index, visita ->
                        val registro = visita.matrizId?.let { registrosPorId[it] }
                        val motivo = motivoExclusionSeguimiento(visita, registro)
                        val ult = visita.matrizId?.let { ultimoContactoSeguimiento(context, it) } ?: 0L
                        val ultWa = visita.matrizId?.let { ultimoWhatsAppSeguimiento(context, it) } ?: 0L
                        val hora = SimpleDateFormat("HH:mm", Locale.ROOT)
                        Divider()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${index + 1}. ${visita.nombre}", style = MaterialTheme.typography.bodyMedium)
                                Text("ID ${visita.matrizId ?: "sin ID"} · ${registro?.estado?.ifBlank { "sin estado" } ?: "sin registro Matriz"}", style = MaterialTheme.typography.bodySmall)
                                Text(motivo ?: "Horario asignado: ${horarioDe(visita.matrizId)}", style = MaterialTheme.typography.bodySmall)
                                if (ult >= hoyInicio) Text("✓ Contactado hoy ${hora.format(Date(ult))} (${contactosSeguimiento(context, visita.matrizId.orEmpty())} en total)", style = MaterialTheme.typography.bodySmall)
                                if (ultWa >= hoyInicio) Text("WhatsApp abierto hoy ${hora.format(Date(ultWa))}", style = MaterialTheme.typography.bodySmall)
                                if (registro != null && WhatsAppHelper.normalizar(registro.numTT) == null) Text("TT sin número válido para WhatsApp", style = MaterialTheme.typography.bodySmall)
                            }
                            if (registro != null && !esPagado(registro.estado) && WhatsAppHelper.normalizar(registro.numTT) != null) {
                                suspend fun abrirWa(alterno: Boolean) {
                                    val container = (context.applicationContext as MainApplication).container
                                    val sem = registro.semana.trim().toIntOrNull()?.coerceIn(1, 5) ?: 1
                                    val variante = withContext(Dispatchers.IO) { container.database.contactoLogDao().contarTotalContactos(registro.id) } + contactosSeguimiento(context, registro.id)
                                    val texto = withContext(Dispatchers.IO) { MensajesCobranza.paraWhatsAppTT(container.database.plantillaSmsDao(), registro.nombre, registro.requisito, sem, variante) }
                                    WhatsAppHelper.abrir(context, registro.numTT, negocio = true, alterno = alterno, texto = texto)
                                    marcarWhatsAppSeguimiento(context, registro.id)
                                    refresco++
                                }
                                BotonWhatsAppSeguimiento(
                                    onClick = { scope.launch { abrirWa(false) } },
                                    onLongClick = { scope.launch { abrirWa(true) } }
                                )
                            }
                        }
                    }
                    if (clientes.isEmpty()) Text("No hay clientes del historial para este cartucho y fecha.")
                }
                else -> {
                    val contactadosHoy = clientes.filter { v -> v.matrizId?.let { ultimoContactoSeguimiento(context, it) >= hoyInicio } == true }
                    val waHoy = clientes.count { v -> v.matrizId?.let { ultimoWhatsAppSeguimiento(context, it) >= hoyInicio } == true }
                    val motivos = clientes.map { v -> motivoExclusionSeguimiento(v, v.matrizId?.let { registrosPorId[it] }) }
                    val entran = motivos.count { it == null }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Cartucho $cartucho · hoy", style = MaterialTheme.typography.titleMedium)
                            Text("Entran al flujo: $entran de ${clientes.size}")
                            Text("Ya contactados hoy: ${contactadosHoy.size}")
                            Text("Pendientes por contactar: ${(entran - contactadosHoy.size).coerceAtLeast(0)}")
                            Text("WhatsApp abiertos hoy: $waHoy")
                            Text("Excluidos por Pagado: ${motivos.count { it?.startsWith("Pagado") == true }}")
                            Text("Omitidos por semana fuera de 1 a 5: ${motivos.count { it?.startsWith("Semana") == true }}")
                            Text("Sin ID o sin registro en Matriz: ${motivos.count { it?.startsWith("Sin") == true }}")
                            Text("Ayer sin cartucho en el Mapa (no se contactan): $totalSinCartucho")
                        }
                    }
                    Text("Se anota el cliente cuando el flujo termina con él (llamada y SMS a todos sus números). Aquí no se distingue si una llamada o SMS puntual falló.", style = MaterialTheme.typography.bodySmall)
                    Text("Contactados hoy", style = MaterialTheme.typography.titleMedium)
                    val hora = SimpleDateFormat("HH:mm", Locale.ROOT)
                    contactadosHoy.sortedByDescending { v -> v.matrizId?.let { ultimoContactoSeguimiento(context, it) } ?: 0L }.forEach { v ->
                        val ult = v.matrizId?.let { ultimoContactoSeguimiento(context, it) } ?: 0L
                        Divider()
                        Text("${hora.format(Date(ult))} · ${v.nombre}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (contactadosHoy.isEmpty()) Text("Todavía no se ha contactado a nadie hoy.")
                }
            }
            mensaje?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

object SeguimientoPorDiaScheduler {
    fun programar(context: Context) = programar(context, preservarCohortePendiente = false)

    /** Al reiniciar/actualizar se conserva el día objetivo de cada alarma que aún no se ejecutó. */
    fun programarTrasReinicio(context: Context) = programar(context, preservarCohortePendiente = true)

    private fun programar(context: Context, preservarCohortePendiente: Boolean) {
        cancelarAlarmas(context)
        val prefs = preferenciasSeguimiento(context)
        val modo = prefs.getString(KEY_MODO_SEGUIMIENTO, MODO_COMBINADO) ?: MODO_COMBINADO
        if (modo != MODO_COMBINADO) return
        val horarios = leerHorariosSeguimiento(context)
        val hoy = inicioDelDia()
        horarios.forEachIndexed { index, minutoDia ->
            val diaGuardado = prefs.getLong(KEY_DIA_SLOT_PREFIX + index, 0L)
            // No conservar cohortes vencidas tras varios días apagado: si el slot quedó
            // guardado en una fecha anterior a hoy, el próximo disparo debe volver a procesar AYER.
            // Se conserva hoy o una fecha futura para no mezclar los slots que siguen pendientes.
            val diaObjetivo = if (preservarCohortePendiente && diaGuardado >= hoy) diaGuardado else hoy
            // Si un horario ya pasó, se ejecuta lo antes posible manteniendo su día objetivo;
            // no se empuja a mañana ni se mezcla la cohorte con la de otra fecha.
            programarSlot(context, index, minutoDia, diaObjetivo)
        }
    }

    fun cancelar(context: Context) {
        preferenciasSeguimiento(context).edit().putString(KEY_MODO_SEGUIMIENTO, "").apply()
        cancelarAlarmas(context)
    }

    private fun cancelarAlarmas(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (slot in 0 until MAX_HORARIOS_SEGUIMIENTO) {
            val intent = Intent(context, SeguimientoPorDiaAlarmReceiver::class.java).putExtra(EXTRA_SLOT_SEGUIMIENTO, slot)
            val pending = PendingIntent.getBroadcast(context, SLOT_BASE_SEGUIMIENTO + slot, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.cancel(pending)
        }
        listOf(8101, 8102).forEach { code ->
            val pending = PendingIntent.getBroadcast(context, code, Intent(context, SeguimientoPorDiaAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.cancel(pending)
        }
        WorkManager.getInstance(context).cancelUniqueWork(WORK_SEGUIMIENTO)
    }

    internal fun programarSlot(context: Context, slot: Int, minutoDia: Int, diaObjetivo: Long) {
        if (slot !in 0 until MAX_HORARIOS_SEGUIMIENTO || minutoDia !in 0..1439) return
        val prefs = preferenciasSeguimiento(context)
        prefs.edit().putLong(KEY_DIA_SLOT_PREFIX + slot, diaObjetivo).apply()
        val ahora = System.currentTimeMillis()
        val disparo = Calendar.getInstance().apply {
            timeInMillis = diaObjetivo
            set(Calendar.HOUR_OF_DAY, minutoDia / 60); set(Calendar.MINUTE, minutoDia % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= ahora) timeInMillis = ahora + 1_000L
        }
        val intent = Intent(context, SeguimientoPorDiaAlarmReceiver::class.java)
            .putExtra(EXTRA_SLOT_SEGUIMIENTO, slot)
            .putExtra(EXTRA_DIA_SEGUIMIENTO, diaObjetivo)
        val pending = PendingIntent.getBroadcast(context, SLOT_BASE_SEGUIMIENTO + slot, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, disparo.timeInMillis, pending)
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, disparo.timeInMillis, pending)
    }
}

class SeguimientoPorDiaAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slot = intent.getIntExtra(EXTRA_SLOT_SEGUIMIENTO, -1)
        if (slot !in 0 until MAX_HORARIOS_SEGUIMIENTO) return
        val prefs = preferenciasSeguimiento(context)
        val modo = prefs.getString(KEY_MODO_SEGUIMIENTO, "") ?: ""
        if (modo != MODO_COMBINADO) return
        val minuto = leerHorariosSeguimiento(context).getOrNull(slot) ?: return
        val diaObjetivo = intent.getLongExtra(EXTRA_DIA_SEGUIMIENTO, inicioDelDia())
        val cantidadSlotsActual = leerHorariosSeguimiento(context).size
        val request = OneTimeWorkRequestBuilder<SeguimientoPorDiaWorker>()
            .setInputData(workDataOf(
                "modo_seguimiento" to modo,
                "slot_seguimiento" to slot,
                "cantidad_slots" to cantidadSlotsActual,
                EXTRA_DIA_SEGUIMIENTO to diaObjetivo
            )).addTag("seguimiento_por_dia").build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_SEGUIMIENTO, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        val siguienteDia = Calendar.getInstance().apply {
            timeInMillis = diaObjetivo
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        SeguimientoPorDiaScheduler.programarSlot(context, slot, minuto, siguienteDia)
    }
}

class SeguimientoPorDiaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val modo = inputData.getString("modo_seguimiento") ?: return Result.failure()
        if (modo != MODO_COMBINADO) return Result.failure()
        val prefs = preferenciasSeguimiento(applicationContext)
        // Si el usuario canceló o cambió la modalidad después de que sonó la alarma, no ejecutar
        // una tarea antigua que quedó esperando en la cola.
        if (prefs.getString(KEY_MODO_SEGUIMIENTO, "") != modo) return Result.success()
        // Validar todos los permisos requeridos antes de empezar. En modo combinado no se
        // debe llamar primero y descubrir después que no puede completar la parte SMS.
        if (modo != MODO_LLAMADAS && !SmsHelper.tienePermisos(applicationContext)) return Result.failure()
        if (modo != MODO_SMS && !CallHelper.tienePermisos(applicationContext)) return Result.failure()
        if (modo != MODO_SMS) promoverSeguimientoEnPrimerPlano()
        val container = (applicationContext as MainApplication).container
        val cartucho = prefs.getInt(KEY_CARTUCHO_SEGUIMIENTO, 1).coerceAtLeast(1)
        val diaObjetivo = inputData.getLong(EXTRA_DIA_SEGUIMIENTO, inicioDelDia())
        val (inicioAyer, finAyer) = rangoAyerParaDiaSeguimiento(diaObjetivo)
        val visitas = container.database.visitaMapaDao().getVisitasSemana(inicioAyer, finAyer).first()
            .filter { it.cartucho == cartucho }
        val ids = visitas.mapNotNull { it.matrizId }.toSet()
        if (ids.isEmpty()) return Result.success()
        val matrizDao = container.database.matrizDao()
        // El historial del Mapa fija el día y cartucho de la visita. No volver a filtrar por
        // la fecha actual de Matriz: si después se edita esa fecha, no debe borrarse la visita histórica.
        // PASE también cuenta y puede recibir seguimiento; solo Pagado queda excluido del contacto.
        // Consultar por ID, no mediante getAllMatriz(): esa consulta general excluye ciertos
        // nombres y podría dejar fuera una visita válida que sí figura en el historial del Mapa.
        val registros = ids.mapNotNull { id -> matrizDao.getById(id) }
        if (registros.isEmpty()) return Result.success()
        val config = container.database.configuracionAutomatizacionDao().obtenerOSembrar()
        val logDao = container.database.contactoLogDao()
        val plantillaDao = container.database.plantillaSmsDao()
        val contactoExtraDao = container.database.contactoExtraDao()
        val vistos = mutableSetOf<String>()
        val slot = inputData.getInt("slot_seguimiento", 0)
        val cantidadSlots = inputData.getInt("cantidad_slots", 1).coerceAtLeast(1)
        // Si cambió el número de horarios después de encolar esta tarea, descartarla: la nueva
        // programación ya asignará los clientes con la distribución actualizada.
        if (cantidadSlots != leerHorariosSeguimiento(applicationContext).size || slot !in 0 until cantidadSlots) return Result.success()
        // Cada horario atiende una parte estable de los registros: los siguientes horarios no vuelven a llamar al cartucho completo.
        val registrosDelHorario = registros.filter { Math.floorMod(it.id.hashCode(), cantidadSlots) == slot }

        for (registro in registrosDelHorario) {
            if (prefs.getString(KEY_MODO_SEGUIMIENTO, "") != modo) return Result.success()
            if (modo != MODO_LLAMADAS && !SmsHelper.tienePermisos(applicationContext)) return Result.failure()
            if (modo != MODO_SMS && !CallHelper.tienePermisos(applicationContext)) return Result.failure()
            val actual = matrizDao.getById(registro.id) ?: continue
            if (esPagado(actual.estado)) continue
            val sem = actual.semana.trim().toIntOrNull()?.takeIf { it in 1..5 } ?: continue
            // Mismo índice de rotación de plantillas que en bloques, más los contactos de Seguimiento (que
            // no escriben en contacto_log_table): así cada contacto sucesivo usa una variante distinta.
            val variante = logDao.contarTotalContactos(actual.id) + contactosSeguimiento(applicationContext, actual.id)
            val fresco = matrizDao.getById(actual.id) ?: continue
            if (esPagado(fresco.estado)) continue
            procesarClienteLlamadaAutomatica(
                applicationContext, fresco, sem, config, logDao, plantillaDao,
                contactoExtraDao, vistos, variante,
                estadoActual = { matrizDao.getById(fresco.id)?.estado }
            )
            registrarContactoSeguimiento(applicationContext, fresco.id)
        }
        return Result.success()
    }

    private suspend fun promoverSeguimientoEnPrimerPlano() {
        try {
            val ctx = applicationContext
            val channelId = "seguimiento_por_dia"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                if (manager.getNotificationChannel(channelId) == null) manager.createNotificationChannel(
                    android.app.NotificationChannel(channelId, "Seguimiento Por Día", android.app.NotificationManager.IMPORTANCE_LOW)
                )
            }
            val notification = androidx.core.app.NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Seguimiento Por Día")
                .setContentText("Procesando contactos del cartucho seleccionado")
                .setOngoing(true).setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW).build()
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                androidx.work.ForegroundInfo(7310, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else androidx.work.ForegroundInfo(7310, notification)
            setForeground(info)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) { }
    }
}