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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val PREFS_SEGUIMIENTO_DIA = "seguimiento_por_dia"
private const val KEY_CARTUCHO_SEGUIMIENTO = "cartucho"
private const val KEY_SMS_SEGUIMIENTO = "sms_activo"
private const val KEY_LLAMADAS_SEGUIMIENTO = "llamadas_activas"
private const val KEY_HORA_SMS_SEGUIMIENTO = "hora_sms"
private const val KEY_MIN_SMS_SEGUIMIENTO = "min_sms"
private const val KEY_HORA_LLAMADAS_SEGUIMIENTO = "hora_llamadas"
private const val KEY_MIN_LLAMADAS_SEGUIMIENTO = "min_llamadas"

private fun preferenciasSeguimiento(context: Context) =
    context.getSharedPreferences(PREFS_SEGUIMIENTO_DIA, Context.MODE_PRIVATE)

private fun inicioAyerSeguimiento(): Long {
    val c = Calendar.getInstance()
    c.add(Calendar.DAY_OF_YEAR, -1)
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun finAyerSeguimiento(): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

@Composable
fun SeguimientoPorDiaScreen() {
    val context = LocalContext.current
    val prefs = remember { preferenciasSeguimiento(context) }
    val scope = rememberCoroutineScope()
    var cartucho by remember { mutableIntStateOf(prefs.getInt(KEY_CARTUCHO_SEGUIMIENTO, 1).coerceIn(1, 3)) }
    var smsActivo by remember { mutableStateOf(prefs.getBoolean(KEY_SMS_SEGUIMIENTO, false)) }
    var llamadasActivas by remember { mutableStateOf(prefs.getBoolean(KEY_LLAMADAS_SEGUIMIENTO, false)) }
    var horaSms by remember { mutableIntStateOf(prefs.getInt(KEY_HORA_SMS_SEGUIMIENTO, 9)) }
    var minutoSms by remember { mutableIntStateOf(prefs.getInt(KEY_MIN_SMS_SEGUIMIENTO, 0)) }
    var horaLlamadas by remember { mutableIntStateOf(prefs.getInt(KEY_HORA_LLAMADAS_SEGUIMIENTO, 10)) }
    var minutoLlamadas by remember { mutableIntStateOf(prefs.getInt(KEY_MIN_LLAMADAS_SEGUIMIENTO, 0)) }
    var totalRegistros by remember { mutableIntStateOf(0) }
    var totalPendientes by remember { mutableIntStateOf(0) }
    var cargando by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf<String?>(null) }
    val fechaAyer = remember { SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "MX")).format(Date(inicioAyerSeguimiento())) }

    LaunchedEffect(cartucho) {
        cargando = true
        try {
            val container = (context.applicationContext as MainApplication).container
            val visitas = withContext(Dispatchers.IO) {
                container.database.visitaMapaDao().getVisitasSemana(inicioAyerSeguimiento(), finAyerSeguimiento()).first()
            }
            val ids = visitas.filter { it.cartucho == cartucho }.mapNotNull { it.matrizId }.toSet()
            val registros = withContext(Dispatchers.IO) { container.database.matrizDao().getAllMatriz().first().filter { it.id in ids } }
            totalRegistros = registros.size
            totalPendientes = registros.count { !esPagado(it.estado) }
        } catch (_: Exception) {
            totalRegistros = 0
            totalPendientes = 0
            mensaje = "No se pudo consultar el cartucho del día anterior."
        } finally {
            cargando = false
        }
    }

    fun guardarPreferencias() {
        prefs.edit()
            .putInt(KEY_CARTUCHO_SEGUIMIENTO, cartucho)
            .putBoolean(KEY_SMS_SEGUIMIENTO, smsActivo)
            .putBoolean(KEY_LLAMADAS_SEGUIMIENTO, llamadasActivas)
            .putInt(KEY_HORA_SMS_SEGUIMIENTO, horaSms)
            .putInt(KEY_MIN_SMS_SEGUIMIENTO, minutoSms)
            .putInt(KEY_HORA_LLAMADAS_SEGUIMIENTO, horaLlamadas)
            .putInt(KEY_MIN_LLAMADAS_SEGUIMIENTO, minutoLlamadas)
            .apply()
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(Icons.Default.CalendarMonth, contentDescription = null)
                    Text("  Seguimiento del día anterior", style = MaterialTheme.typography.titleMedium)
                }
                Text(fechaAyer.replaceFirstChar { it.uppercase(Locale("es", "MX")) }, style = MaterialTheme.typography.bodyLarge)
                Text("Esta pantalla nunca incluye registros del día actual.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("Cartucho que se procesará", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..3).forEach { numero ->
                androidx.compose.material3.FilterChip(
                    selected = cartucho == numero,
                    onClick = { cartucho = numero; prefs.edit().putInt(KEY_CARTUCHO_SEGUIMIENTO, numero).apply() },
                    label = { Text("Cartucho $numero") }
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (cargando) "Consultando registros…" else "Registros encontrados: $totalRegistros")
                Text("Pendientes después de excluir pagados: $totalPendientes")
                Text("Los estados se vuelven a comprobar justo antes de contactar.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("Canales y horarios independientes", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = smsActivo, onCheckedChange = { smsActivo = it })
            Text("Programar SMS")
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            OutlinedButton(enabled = smsActivo, onClick = {
                TimePickerDialog(context, { _, h, m -> horaSms = h; minutoSms = m }, horaSms, minutoSms, true).show()
            }) { Text(String.format(Locale.ROOT, "%02d:%02d", horaSms, minutoSms)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = llamadasActivas, onCheckedChange = { llamadasActivas = it })
            Text("Programar llamadas")
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            OutlinedButton(enabled = llamadasActivas, onClick = {
                TimePickerDialog(context, { _, h, m -> horaLlamadas = h; minutoLlamadas = m }, horaLlamadas, minutoLlamadas, true).show()
            }) { Text(String.format(Locale.ROOT, "%02d:%02d", horaLlamadas, minutoLlamadas)) }
        }
        Text("Los pagados se excluyen tanto al preparar la tarea como al procesar cada cliente.", style = MaterialTheme.typography.bodySmall)

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = (smsActivo || llamadasActivas) && !cargando,
            onClick = {
                guardarPreferencias()
                SeguimientoPorDiaScheduler.programar(context)
                mensaje = "Programación guardada. SMS y llamadas conservan horarios independientes."
            }
        ) { Text("Guardar y programar") }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                SeguimientoPorDiaScheduler.cancelar(context)
                mensaje = "Se cancelaron las tareas de Seguimiento Por Día."
            }
        ) { Text("Cancelar programación") }
        mensaje?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

object SeguimientoPorDiaScheduler {
    fun programar(context: Context) {
        cancelarAlarmas(context)
        val prefs = preferenciasSeguimiento(context)
        val sms = prefs.getBoolean(KEY_SMS_SEGUIMIENTO, false)
        val llamadas = prefs.getBoolean(KEY_LLAMADAS_SEGUIMIENTO, false)
        if (sms) programarCanal(context, "SMS", prefs.getInt(KEY_HORA_SMS_SEGUIMIENTO, 9), prefs.getInt(KEY_MIN_SMS_SEGUIMIENTO, 0))
        if (llamadas) programarCanal(context, "LLAMADAS", prefs.getInt(KEY_HORA_LLAMADAS_SEGUIMIENTO, 10), prefs.getInt(KEY_MIN_LLAMADAS_SEGUIMIENTO, 0))
    }

    fun cancelar(context: Context) {
        preferenciasSeguimiento(context).edit()
            .putBoolean(KEY_SMS_SEGUIMIENTO, false)
            .putBoolean(KEY_LLAMADAS_SEGUIMIENTO, false)
            .apply()
        cancelarAlarmas(context)
    }

    private fun cancelarAlarmas(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf(8101, 8102).forEach { requestCode ->
            val intent = Intent(context, SeguimientoPorDiaAlarmReceiver::class.java)
            val pending = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.cancel(pending)
        }
        WorkManager.getInstance(context).cancelUniqueWork("seguimiento_por_dia_sms")
        WorkManager.getInstance(context).cancelUniqueWork("seguimiento_por_dia_llamadas")
    }

    private fun programarCanal(context: Context, canal: String, hora: Int, minuto: Int) {
        val ahora = Calendar.getInstance()
        val disparo = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hora)
            set(Calendar.MINUTE, minuto)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= ahora.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        val requestCode = if (canal == "SMS") 8101 else 8102
        val intent = Intent(context, SeguimientoPorDiaAlarmReceiver::class.java).putExtra("canal_seguimiento", canal)
        val pending = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, disparo.timeInMillis, pending)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, disparo.timeInMillis, pending)
        }
    }
}

class SeguimientoPorDiaAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val canal = intent.getStringExtra("canal_seguimiento") ?: return
        val nombre = if (canal == "SMS") "seguimiento_por_dia_sms" else "seguimiento_por_dia_llamadas"
        val request = OneTimeWorkRequestBuilder<SeguimientoPorDiaWorker>()
            .setInputData(workDataOf("canal_seguimiento" to canal))
            .addTag("seguimiento_por_dia")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(nombre, ExistingWorkPolicy.REPLACE, request)
        // Programa la siguiente ejecución diaria de este canal. El otro canal conserva su propio horario.
        val prefs = preferenciasSeguimiento(context)
        val activo = if (canal == "SMS") prefs.getBoolean(KEY_SMS_SEGUIMIENTO, false) else prefs.getBoolean(KEY_LLAMADAS_SEGUIMIENTO, false)
        if (activo) {
            val hora = if (canal == "SMS") prefs.getInt(KEY_HORA_SMS_SEGUIMIENTO, 9) else prefs.getInt(KEY_HORA_LLAMADAS_SEGUIMIENTO, 10)
            val minuto = if (canal == "SMS") prefs.getInt(KEY_MIN_SMS_SEGUIMIENTO, 0) else prefs.getInt(KEY_MIN_LLAMADAS_SEGUIMIENTO, 0)
            val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hora); set(Calendar.MINUTE, minuto); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.DAY_OF_YEAR, 1) }
            val code = if (canal == "SMS") 8101 else 8102
            val next = Intent(context, SeguimientoPorDiaAlarmReceiver::class.java).putExtra("canal_seguimiento", canal)
            val pending = PendingIntent.getBroadcast(context, code, next, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending)
        }
    }
}

class SeguimientoPorDiaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val canal = inputData.getString("canal_seguimiento") ?: return Result.failure()
        val container = (applicationContext as MainApplication).container
        val prefs = preferenciasSeguimiento(applicationContext)
        val cartucho = prefs.getInt(KEY_CARTUCHO_SEGUIMIENTO, 1).coerceIn(1, 3)
        val inicio = inicioAyerSeguimiento()
        val fin = finAyerSeguimiento()
        val visitas = container.database.visitaMapaDao().getVisitasSemana(inicio, fin).first()
            .filter { it.cartucho == cartucho }
        val ids = visitas.mapNotNull { it.matrizId }.toSet()
        if (ids.isEmpty()) return Result.success()
        val matrizDao = container.database.matrizDao()
        val registros = container.database.matrizDao().getAllMatriz().first().filter { it.id in ids }
        val config = container.database.configuracionAutomatizacionDao().obtenerOSembrar()
        val plantillaDao = container.database.plantillaSmsDao()
        val vistos = mutableSetOf<String>()
        for (registro in registros) {
            // Releer estado justo antes de cada cliente: si fue pagado después de formar la lista, se omite.
            val actual = matrizDao.getById(registro.id) ?: continue
            if (esPagado(actual.estado)) continue
            val sem = actual.semana.trim().toIntOrNull()?.coerceIn(1, 5) ?: 1
            val variante = container.database.contactoLogDao().contarTotalContactos(actual.id)
            val contactos = buildList {
                actual.numTT.trim().takeIf { it.filter(Char::isDigit).length >= 7 }?.let { add(it to true) }
                listOf(actual.ref1, actual.ref2, actual.ref3.orEmpty(), actual.ref4.orEmpty())
                    .map { it.trim() }
                    .filter { it.filter(Char::isDigit).length >= 7 }
                    .forEach { add(it to false) }
            }.distinctBy { ultimos10Digitos(it.first) }
                .filter { vistos.add(ultimos10Digitos(it.first)) }

            for ((telefono, esTitular) in contactos) {
                // El estado se consulta de nuevo antes de cada envío/llamada, incluso dentro del mismo registro.
                val estadoFresco = matrizDao.getById(actual.id)?.estado ?: continue
                if (esPagado(estadoFresco)) break
                if (canal == "SMS") {
                    if (!SmsHelper.tienePermisos(applicationContext)) return Result.failure()
                    val mensaje = if (esTitular) {
                        MensajesCobranza.paraTT(plantillaDao, actual.nombre, actual.requisito, sem, variante)
                    } else {
                        MensajesCobranza.paraReferencia(plantillaDao, actual.nombre, sem, variante)
                    }
                    SmsHelper.enviarSms(applicationContext, config.simSms, telefono, mensaje)
                } else if (canal == "LLAMADAS") {
                    if (!CallHelper.tienePermisos(applicationContext)) return Result.failure()
                    CallHelper.realizarLlamada(applicationContext, config.simSeleccionada, telefono, ocultarNumero = config.ocultarNumero)
                    kotlinx.coroutines.delay(2_000)
                    CallHelper.esperarFinOForzarColgar(applicationContext, (config.duracionMaximaLlamada * 1_000L - 2_000L).coerceAtLeast(1_000L))
                    kotlinx.coroutines.delay(config.segundosPausaEntreLlamadas * 1_000L)
                }
            }
        }
        return Result.success()
    }
}
