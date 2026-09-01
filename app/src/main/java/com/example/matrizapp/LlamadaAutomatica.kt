package com.example.matrizapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// ============================================================
// INTERRUPTOR GENERAL — SharedPreferences (no Room) porque lo lee
// BootCompletedReceiver y los Workers sin depender de un ViewModel
// vivo. Apagado por defecto: instalaciones existentes no arrancan
// a llamar solas hasta que el usuario lo prenda a propósito.
// ============================================================
object AutomatizacionPrefs {
    private const val PREFS_NAME = "automatizacion_prefs"
    private const val KEY_ACTIVA = "activa"

    fun activa(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVA, false)

    fun setActiva(context: Context, valor: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACTIVA, valor).apply()
    }
}

// ============================================================
// SCHEDULER — una alarma exacta por bloque activo, se reprograma
// sola cada medianoche. Mismo patrón que RetornoAlarmReceiver/
// BootCompletedReceiver ya usan en la app. También programa las
// dos alarmas fijas de catchup (8:15/9:15).
// ============================================================
class LlamadaAutomaticaScheduler(
    private val context: Context,
    private val dao: BloqueHorarioDao
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Con el interruptor general apagado, cancela cualquier alarma que hubiera quedado
     * (de cuando estaba prendido) y no programa nada nuevo — los bloques quedan guardados
     * en la lista, listos para cuando se vuelva a prender. */
    suspend fun reprogramarTodos() {
        for (idPosible in 1..MAX_ID_ESPERADO) cancelarBloque(idPosible)
        cancelarReprogramacionMedianoche()
        cancelarCatchup()
        if (!AutomatizacionPrefs.activa(context)) return

        val bloques = dao.obtenerBloquesActivos()
        bloques.forEach { programarBloque(it) }
        programarReprogramacionMedianoche()
        programarCatchup()
    }

    private fun programarBloque(bloque: BloqueHorarioEntity) {
        val ahora = LocalDateTime.now()
        var disparo = ahora.toLocalDate().atTime(bloque.hora, bloque.minuto)
        if (disparo.isBefore(ahora)) disparo = disparo.plusDays(1)

        val millis = disparo.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(context, LlamadaBloqueAlarmReceiver::class.java).apply {
            putExtra(LlamadaBloqueAlarmReceiver.EXTRA_BLOQUE_ID, bloque.id)
        }
        val pending = PendingIntent.getBroadcast(
            context, requestCodeParaBloque(bloque.id), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
    }

    private fun cancelarBloque(id: Long) {
        val intent = Intent(context, LlamadaBloqueAlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, requestCodeParaBloque(id), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pending)
    }

    private fun programarReprogramacionMedianoche() {
        val medianoche = LocalDateTime.now().toLocalDate().plusDays(1).atStartOfDay()
        val millis = medianoche.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingMedianoche())
    }

    private fun cancelarReprogramacionMedianoche() {
        alarmManager.cancel(pendingMedianoche())
    }

    private fun pendingMedianoche(): PendingIntent {
        val intent = Intent(context, ReprogramarLlamadaBloquesReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE_MEDIANOCHE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Las dos corridas fijas de catchup: recalculan el déficit de AYER (meta de la semana
     * menos lo realmente contactado) y reintentan solo a quien le sigue faltando — cubre tanto
     * fallas puntuales (batería/señal durante el día) como el caso estructural de clientes con
     * alta tardía + semana alta cuyos offsets se pasan del último bloque del día. */
    private fun programarCatchup() {
        programarAlarmaCatchup(hora = 8, minuto = 15, requestCode = REQUEST_CODE_CATCHUP_815)
        programarAlarmaCatchup(hora = 9, minuto = 15, requestCode = REQUEST_CODE_CATCHUP_915)
    }

    private fun cancelarCatchup() {
        alarmManager.cancel(pendingCatchup(REQUEST_CODE_CATCHUP_815))
        alarmManager.cancel(pendingCatchup(REQUEST_CODE_CATCHUP_915))
    }

    private fun pendingCatchup(requestCode: Int): PendingIntent {
        val intent = Intent(context, CatchupLlamadaAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun programarAlarmaCatchup(hora: Int, minuto: Int, requestCode: Int) {
        val ahora = LocalDateTime.now()
        var disparo = ahora.toLocalDate().atTime(hora, minuto)
        if (disparo.isBefore(ahora)) disparo = disparo.plusDays(1)

        val millis = disparo.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingCatchup(requestCode))
    }

    private fun requestCodeParaBloque(id: Long): Int = (REQUEST_CODE_BASE + id).toInt()

    companion object {
        private const val REQUEST_CODE_BASE = 6_000
        private const val REQUEST_CODE_MEDIANOCHE = 6_999
        private const val REQUEST_CODE_CATCHUP_815 = 6_997
        private const val REQUEST_CODE_CATCHUP_915 = 6_998
        private const val MAX_ID_ESPERADO = 500L
    }
}

// ============================================================
// RECEIVERS — solo encolan Workers, para no arriesgar el límite
// de ~10s que Android da a un BroadcastReceiver.
// ============================================================
class LlamadaBloqueAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bloqueId = intent.getLongExtra(EXTRA_BLOQUE_ID, -1)
        if (bloqueId < 0) return
        val request = OneTimeWorkRequestBuilder<LlamadaAutomaticaWorker>()
            .setInputData(workDataOf(LlamadaAutomaticaWorker.KEY_BLOQUE_ID to bloqueId))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
    companion object { const val EXTRA_BLOQUE_ID = "bloque_id" }
}

class ReprogramarLlamadaBloquesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<ReprogramarLlamadaBloquesWorker>().build())
    }
}

class ReprogramarLlamadaBloquesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MainApplication).container
        LlamadaAutomaticaScheduler(applicationContext, container.database.bloqueHorarioDao()).reprogramarTodos()
        return Result.success()
    }
}

class CatchupLlamadaAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<CatchupLlamadaWorker>().build())
    }
}

// ============================================================
// Lógica de contacto compartida entre el worker de bloque normal
// y el de catchup — llama al titular (+ SMS) y manda SMS a las
// referencias, reusando CallHelper/SmsHelper. Lee su propia
// ConfiguracionAutomatizacionEntity (SIM, ocultar número, pausa,
// duración máxima) — independiente de la pantalla manual de
// Llamadas, para que ajustar una no afecte a la otra.
// ============================================================
private suspend fun procesarClienteLlamadaAutomatica(context: Context, r: MatrizEntity, sem: Int, config: ConfiguracionAutomatizacionEntity, logDao: ContactoLogDao, plantillaDao: PlantillaSmsDao) {
    val variante = logDao.contarTotalContactos(r.id)
    val subId = config.simSeleccionada
    if (r.numTT.isNotBlank()) {
        CallHelper.realizarLlamada(context, subId, r.numTT, ocultarNumero = config.ocultarNumero)
        delay(2_000)
        CallHelper.esperarFinOForzarColgar(context, duracionMaximaMs = config.duracionMaximaLlamada * 1_000L)
        SmsHelper.enviarSms(context, subId, r.numTT, MensajesCobranza.paraTT(plantillaDao, r.nombre, r.requisito, sem, variante))
    }
    listOfNotNull(r.ref1.takeIf { it.isNotBlank() }, r.ref2.takeIf { it.isNotBlank() }).forEach { tel ->
        SmsHelper.enviarSms(context, subId, tel, MensajesCobranza.paraReferencia(plantillaDao, r.nombre, sem, variante))
    }
}

private fun inicioDeDiaMillis(fecha: LocalDate): Long =
    fecha.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

// ============================================================
// WORKER PRINCIPAL — decide automáticamente a quién le toca este
// bloque (según ReglaRepeticion) y ejecuta llamada+SMS. Registra
// cada contacto en ContactoLogEntity para que el catchup pueda
// saber, al día siguiente, quién se quedó corto de su meta.
// ============================================================
class LlamadaAutomaticaWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!AutomatizacionPrefs.activa(applicationContext)) return Result.success() // se apagó el interruptor general mientras este worker esperaba encolado

        val bloqueId = inputData.getLong(KEY_BLOQUE_ID, -1)
        if (bloqueId < 0) return Result.failure()

        val container = (applicationContext as MainApplication).container
        val bloqueDao = container.database.bloqueHorarioDao()
        val matrizDao = container.database.matrizDao()
        val logDao = container.database.contactoLogDao()
        val plantillaDao = container.database.plantillaSmsDao()
        val configDao = container.database.configuracionAutomatizacionDao()

        val bloquesActivos = bloqueDao.obtenerBloquesActivos()
        val bloqueActualIndex = bloquesActivos.indexOfFirst { it.id == bloqueId }
        if (bloqueActualIndex == -1) return Result.success() // el bloque fue eliminado/desactivado desde entonces

        val registros = matrizDao.getAllMatriz().first()
        val config = configDao.obtenerOSembrar()
        val hoyMillis = inicioDeDiaMillis(LocalDate.now())
        var esPrimerContacto = true

        for (r in registros) {
            if (r.estado.equals("Pagado", ignoreCase = true)) continue
            val sem = r.semana.trim().toIntOrNull() ?: continue
            if (sem !in 1..5) continue

            val fechaAlta = ReglaRepeticion.fechaAltaDe(r) ?: continue
            if (fechaAlta.toLocalDate() != LocalDate.now()) continue // solo el día de alta -- el remanente lo cubre el catchup de mañana
            val bloqueAltaIndex = ReglaRepeticion.calcularBloqueDeAlta(fechaAlta, bloquesActivos)
            if (!ReglaRepeticion.debeContactarseEnBloque(sem, bloqueActualIndex, bloqueAltaIndex)) continue

            if (!esPrimerContacto) delay(config.segundosPausaEntreLlamadas * 1_000L)
            esPrimerContacto = false
            procesarClienteLlamadaAutomatica(applicationContext, r, sem, config, logDao, plantillaDao)
            logDao.insertar(ContactoLogEntity(clienteId = r.id, fechaDia = hoyMillis, bloqueIndex = bloqueActualIndex))
        }
        return Result.success()
    }

    companion object {
        const val KEY_BLOQUE_ID = "bloque_id"
    }
}

// ============================================================
// WORKER DE CATCHUP — corre a las 8:15 y a las 9:15. Para cada
// cliente activo, compara cuántas veces se le contactó AYER
// (ContactoLogEntity) contra la meta de su semana de atraso
// (ReglaRepeticion.metaContactos) y, si quedó corto, lo contacta
// ahora — acreditando el contacto hacia "ayer", así la segunda
// corrida (9:15) ve lo que ya cubrió la primera y no duplica.
// ============================================================
class CatchupLlamadaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!AutomatizacionPrefs.activa(applicationContext)) return Result.success()

        val container = (applicationContext as MainApplication).container
        val matrizDao = container.database.matrizDao()
        val logDao = container.database.contactoLogDao()
        val plantillaDao = container.database.plantillaSmsDao()
        val configDao = container.database.configuracionAutomatizacionDao()
        val registros = matrizDao.getAllMatriz().first()
        val config = configDao.obtenerOSembrar()
        val ayerMillis = inicioDeDiaMillis(LocalDate.now().minusDays(1))
        var esPrimerContacto = true

        for (r in registros) {
            if (r.estado.equals("Pagado", ignoreCase = true)) continue
            val sem = r.semana.trim().toIntOrNull() ?: continue
            if (sem !in 1..5) continue

            val contactosAyer = logDao.contarContactosEnDia(r.id, ayerMillis)
            val deficit = ReglaRepeticion.calcularDeficit(sem, contactosAyer)
            if (deficit <= 0) continue

            if (!esPrimerContacto) delay(config.segundosPausaEntreLlamadas * 1_000L)
            esPrimerContacto = false
            procesarClienteLlamadaAutomatica(applicationContext, r, sem, config, logDao, plantillaDao)
            logDao.insertar(ContactoLogEntity(clienteId = r.id, fechaDia = ayerMillis, bloqueIndex = -1))
        }

        // Limpieza: el catchup solo mira "ayer", no hace falta conservar más de una semana.
        logDao.limpiarAnteriores(inicioDeDiaMillis(LocalDate.now().minusDays(7)))
        return Result.success()
    }
}
