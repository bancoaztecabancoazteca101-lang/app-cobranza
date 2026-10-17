package com.example.matrizapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
private const val SLOT_BASE_SEGUIMIENTO = 8200
private const val MAX_HORARIOS_SEGUIMIENTO = 20

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
fun SeguimientoPorDiaScreen() {
    val context = LocalContext.current
    val prefs = remember { preferenciasSeguimiento(context) }
    var cartucho by remember { mutableIntStateOf(prefs.getInt(KEY_CARTUCHO_SEGUIMIENTO, 1).coerceIn(1, 3)) }
    var modo by remember { mutableStateOf(prefs.getString(KEY_MODO_SEGUIMIENTO, MODO_COMBINADO) ?: MODO_COMBINADO) }
    val horarios = remember { mutableStateListOf<Int>().apply { addAll(leerHorariosSeguimiento(context)) } }
    var totalRegistros by remember { mutableIntStateOf(0) }
    var totalPendientes by remember { mutableIntStateOf(0) }
    var totalMatrizAyer by remember { mutableIntStateOf(0) }
    var totalConCartucho by remember { mutableIntStateOf(0) }
    var totalSinCartucho by remember { mutableIntStateOf(0) }
    var cargando by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf<String?>(null) }
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
                fecha != null && fecha >= inicioAyer && fecha < finAyer &&
                    !registro.estado.equals("PASE", ignoreCase = true)
            }
            val visitas = withContext(Dispatchers.IO) {
                container.database.visitaMapaDao().getVisitasSemana(inicioAyer, finAyer).first()
            }
            val idsConCartucho = visitas.mapNotNull { it.matrizId }.toSet()
            val idsCartuchoSeleccionado = visitas.filter { it.cartucho == cartucho }.mapNotNull { it.matrizId }.toSet()
            val registros = matrizAyer.filter { it.id in idsCartuchoSeleccionado }
            totalMatrizAyer = matrizAyer.size
            totalConCartucho = matrizAyer.count { it.id in idsConCartucho }
            totalSinCartucho = (matrizAyer.size - totalConCartucho).coerceAtLeast(0)
            totalRegistros = registros.size
            totalPendientes = registros.count { !esPagado(it.estado) }
            if (totalSinCartucho > 0 && registros.isEmpty()) {
                mensaje = "Hay registros de ayer en Matriz sin cartucho guardado. No se asignan automáticamente para evitar contactar al grupo equivocado."
            } else if (registros.isEmpty() && matrizAyer.isNotEmpty()) {
                mensaje = "Hay registros de ayer en Matriz, pero ninguno tiene asignado el cartucho $cartucho."
            }
        } catch (_: Exception) {
            totalRegistros = 0; totalPendientes = 0
            totalMatrizAyer = 0; totalConCartucho = 0; totalSinCartucho = 0
            mensaje = "No se pudo consultar Matriz y el historial de cartuchos del día anterior."
        } finally { cargando = false }
    }

    fun guardarYProgramar() {
        prefs.edit().putInt(KEY_CARTUCHO_SEGUIMIENTO, cartucho).putString(KEY_MODO_SEGUIMIENTO, modo).apply()
        guardarHorariosSeguimiento(context, horarios.toList())
        SeguimientoPorDiaScheduler.programar(context)
        mensaje = "Configuración guardada. Se programaron ${horarios.size} horario(s) independientes de los bloques normales."
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(Icons.Default.CalendarMonth, contentDescription = null)
                    Text("  Seguimiento del día anterior", style = MaterialTheme.typography.titleMedium)
                }
                Text(fechaAyer.replaceFirstChar { it.uppercase(Locale("es", "MX")) }, style = MaterialTheme.typography.bodyLarge)
                Text("Solo procesa registros del día anterior; nunca incorpora registros del día actual.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Cartucho que se procesará", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..3).forEach { numero ->
                FilterChip(selected = cartucho == numero, onClick = {
                    cartucho = numero
                    prefs.edit().putInt(KEY_CARTUCHO_SEGUIMIENTO, numero).apply()
                }, label = { Text("Cartucho $numero") })
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (cargando) "Consultando registros…" else "Registros de ayer en Matriz: $totalMatrizAyer")
                Text("Registros con cartucho guardado: $totalConCartucho")
                Text("Registros sin cartucho identificado: $totalSinCartucho")
                Text("En Cartucho $cartucho: $totalRegistros")
                Text("Pendientes después de excluir pagados: $totalPendientes")
                Text("El estado pagado se vuelve a comprobar antes de contactar.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Modalidad", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = modo == MODO_COMBINADO, onClick = { modo = MODO_COMBINADO }, label = { Text("Llamada + SMS (secuencia original de Matriz)") })
            FilterChip(selected = modo == MODO_SMS, onClick = { modo = MODO_SMS }, label = { Text("Solo SMS") })
            FilterChip(selected = modo == MODO_LLAMADAS, onClick = { modo = MODO_LLAMADAS }, label = { Text("Solo llamadas") })
        }
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
        Text("Los registros pagados se excluyen y el estado se revalida antes de cada contacto. Los horarios normales de llamadas no se modifican.", style = MaterialTheme.typography.bodySmall)
        Button(modifier = Modifier.fillMaxWidth(), enabled = horarios.isNotEmpty() && !cargando, onClick = { guardarYProgramar() }) { Text("Guardar y programar") }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
            SeguimientoPorDiaScheduler.cancelar(context)
            mensaje = "Se canceló la programación de Seguimiento Por Día."
        }) { Text("Cancelar programación") }
        mensaje?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

object SeguimientoPorDiaScheduler {
    fun programar(context: Context) {
        cancelarAlarmas(context)
        val modo = preferenciasSeguimiento(context).getString(KEY_MODO_SEGUIMIENTO, MODO_COMBINADO) ?: MODO_COMBINADO
        if (modo !in setOf(MODO_COMBINADO, MODO_SMS, MODO_LLAMADAS)) return
        val horarios = leerHorariosSeguimiento(context)
        // Un solo conjunto de horarios debe trabajar sobre la misma cohorte de ayer.
        // Si al guardar ya pasó alguno, mover TODOS a mañana evita mezclar en una misma
        // programación clientes de dos días distintos.
        val ahora = Calendar.getInstance()
        val minutoActual = ahora.get(Calendar.HOUR_OF_DAY) * 60 + ahora.get(Calendar.MINUTE)
        val moverTodosAManana = horarios.any { it <= minutoActual }
        horarios.forEachIndexed { index, minutoDia ->
            programarSlot(context, index, minutoDia, moverTodosAManana)
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

    internal fun programarSlot(context: Context, slot: Int, minutoDia: Int, siguienteDia: Boolean) {
        if (slot !in 0 until MAX_HORARIOS_SEGUIMIENTO || minutoDia !in 0..1439) return
        val ahora = Calendar.getInstance()
        val disparo = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutoDia / 60); set(Calendar.MINUTE, minutoDia % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (siguienteDia || timeInMillis <= ahora.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        val diaProgramado = Calendar.getInstance().apply {
            timeInMillis = disparo.timeInMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val intent = Intent(context, SeguimientoPorDiaAlarmReceiver::class.java)
            .putExtra(EXTRA_SLOT_SEGUIMIENTO, slot)
            .putExtra(EXTRA_DIA_SEGUIMIENTO, diaProgramado)
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
        if (modo !in setOf(MODO_COMBINADO, MODO_SMS, MODO_LLAMADAS)) return
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
        SeguimientoPorDiaScheduler.programarSlot(context, slot, minuto, true)
    }
}

class SeguimientoPorDiaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val modo = inputData.getString("modo_seguimiento") ?: return Result.failure()
        if (modo !in setOf(MODO_COMBINADO, MODO_SMS, MODO_LLAMADAS)) return Result.failure()
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
        val cartucho = prefs.getInt(KEY_CARTUCHO_SEGUIMIENTO, 1).coerceIn(1, 3)
        val diaObjetivo = inputData.getLong(EXTRA_DIA_SEGUIMIENTO, inicioDelDia())
        val (inicioAyer, finAyer) = rangoAyerParaDiaSeguimiento(diaObjetivo)
        val visitas = container.database.visitaMapaDao().getVisitasSemana(inicioAyer, finAyer).first()
            .filter { it.cartucho == cartucho }
        val ids = visitas.mapNotNull { it.matrizId }.toSet()
        if (ids.isEmpty()) return Result.success()
        val matrizDao = container.database.matrizDao()
        // La fecha de Matriz también debe corresponder a ayer; el historial de mapa por sí solo no basta.
        val registros = matrizDao.getAllMatriz().first().filter { registro ->
            registro.id in ids && registro.fecha != null &&
                registro.fecha >= inicioAyer && registro.fecha < finAyer &&
                !registro.estado.equals("PASE", ignoreCase = true)
        }
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
        var huboFalloSms = false

        for (registro in registrosDelHorario) {
            if (prefs.getString(KEY_MODO_SEGUIMIENTO, "") != modo) return Result.success()
            val actual = matrizDao.getById(registro.id) ?: continue
            if (esPagado(actual.estado)) continue
            val sem = actual.semana.trim().toIntOrNull()?.takeIf { it in 1..5 } ?: continue
            val variante = logDao.contarTotalContactos(actual.id)
            if (modo == MODO_COMBINADO) {
                val fresco = matrizDao.getById(actual.id) ?: continue
                if (esPagado(fresco.estado)) continue
                procesarClienteLlamadaAutomatica(
                    applicationContext, fresco, sem, config, logDao, plantillaDao,
                    contactoExtraDao, vistos, variante
                ) { matrizDao.getById(actual.id)?.estado }
                continue
            }
            val telefonos = buildList {
                actual.numTT.trim().takeIf { it.filter { c -> c.isDigit() }.length >= 7 }?.let { add(it to true) }
                listOf(actual.ref1, actual.ref2, actual.ref3.orEmpty(), actual.ref4.orEmpty())
                    .map { it.trim() }.filter { it.filter { c -> c.isDigit() }.length >= 7 }
                    .forEach { add(it to false) }
                contactoExtraDao.obtenerPara(actual.id).map { it.telefono.trim() }
                    .filter { it.filter { c -> c.isDigit() }.length >= 7 }.forEach { add(it to false) }
            }.distinctBy { ultimos10Digitos(it.first) }.filter { vistos.add(ultimos10Digitos(it.first)) }

            for ((telefono, esTitular) in telefonos) {
                val estadoFresco = matrizDao.getById(actual.id)?.estado ?: continue
                if (esPagado(estadoFresco)) break
                if (modo == MODO_SMS) {
                    if (!SmsHelper.tienePermisos(applicationContext)) return Result.failure()
                    val marcaReserva = NumeroContactadoGuard.reservarSmsConMarca(applicationContext, telefono) ?: continue
                    val mensaje = if (esTitular) MensajesCobranza.paraTT(plantillaDao, actual.nombre, actual.requisito, sem, variante)
                    else MensajesCobranza.paraReferencia(plantillaDao, actual.nombre, sem, variante)
                    val estadoAntesDeEnviar = matrizDao.getById(actual.id)?.estado
                    if (estadoAntesDeEnviar == null) {
                        NumeroContactadoGuard.liberarReservaSms(applicationContext, telefono, marcaReserva)
                        continue
                    }
                    if (esPagado(estadoAntesDeEnviar)) {
                        NumeroContactadoGuard.liberarReservaSms(applicationContext, telefono, marcaReserva)
                        break
                    }
                    if (!SmsHelper.enviarSms(applicationContext, config.simSms, telefono, mensaje)) {
                        // Un rechazo de SmsManager no debe dejar el número bloqueado 30 min ni
                        // tratarse como envío exitoso. Se reintenta el Worker sin duplicar los
                        // SMS que sí fueron aceptados por el sistema.
                        NumeroContactadoGuard.liberarReservaSms(applicationContext, telefono, marcaReserva)
                        huboFalloSms = true
                        android.util.Log.e("SeguimientoPorDia", "SmsManager rechazó un SMS automático (teléfono terminado en ${telefono.filter { it.isDigit() }.takeLast(4)})")
                    }
                } else {
                    if (!CallHelper.tienePermisos(applicationContext)) return Result.failure()
                    if (!NumeroContactadoGuard.reservar(applicationContext, telefono)) continue
                    if (esPagado(matrizDao.getById(actual.id)?.estado ?: continue)) break
                    llamarSilenciadoYEsperar(applicationContext, config.simSeleccionada, telefono, config)
                    delay(config.segundosPausaEntreLlamadas * 1_000L)
                }
            }
        }
        if (huboFalloSms) {
            // WorkManager reintentará con backoff. Los envíos ya aceptados siguen protegidos por
            // NumeroContactadoGuard; solo se vuelven a intentar los números cuya reserva se liberó.
            return if (runAttemptCount < 2) Result.retry() else Result.failure()
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
