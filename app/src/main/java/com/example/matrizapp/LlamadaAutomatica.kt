package com.example.matrizapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
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
    private const val KEY_CATCHUP_ACTIVA = "catchup_activa"

    /** Tag común en los Workers de llamada automática (bloque normal y catchup) para poder
     * cancelarlos de inmediato con WorkManager.cancelAllWorkByTag() cuando se apaga el
     * interruptor general -- antes no había forma de tocar un Worker que ya estaba corriendo. */
    const val TAG_AUTOMATIZACION = "automatizacion_llamadas"

    fun activa(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVA, false)

    fun setActiva(context: Context, valor: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACTIVA, valor).apply()
    }

    /** Interruptor independiente del general: permite dejar prendidos los bloques normales
     * del día pero apagar solo las 2 corridas fijas de catchup (8:15/9:15). Prendido por
     * defecto para no cambiar el comportamiento de quien ya tenía la automatización activa. */
    fun catchupActiva(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_CATCHUP_ACTIVA, true)

    fun setCatchupActiva(context: Context, valor: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_CATCHUP_ACTIVA, valor).apply()
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
        if (AutomatizacionPrefs.catchupActiva(context)) programarCatchup()
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
            .addTag(AutomatizacionPrefs.TAG_AUTOMATIZACION)
            .build()
        // Cola ÚNICA y en serie: bloques y catchup nunca corren al mismo tiempo (el teléfono no puede
        // hacer dos llamadas a la vez, y en paralelo mandaban SMS duplicados al mismo número).
        WorkManager.getInstance(context).enqueueUniqueWork(COLA_AUTOMATICA, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
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
        val request = OneTimeWorkRequestBuilder<CatchupLlamadaWorker>()
            .addTag(AutomatizacionPrefs.TAG_AUTOMATIZACION)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(COLA_AUTOMATICA, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

/** Sube el Worker a primer plano (notificación fija). Sin esto Android lo corta a los ~10 min y
 * lo REINICIA desde el primer cliente -- y con llamadas a Ref1-Ref4 un bloque tarda mucho más que
 * eso. Si el sistema no permite primer plano en ese momento, sigue como antes (sin abortar). */
private suspend fun CoroutineWorker.mantenerEnPrimerPlano(texto: String) {
    try {
        val ctx = applicationContext
        val canal = "bloques_automaticos"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (nm.getNotificationChannel(canal) == null) {
                nm.createNotificationChannel(android.app.NotificationChannel(canal, "Bloques automáticos", android.app.NotificationManager.IMPORTANCE_LOW))
            }
        }
        val notif = androidx.core.app.NotificationCompat.Builder(ctx, canal)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Bloque automático en curso")
            .setContentText(texto)
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
        val info = if (android.os.Build.VERSION.SDK_INT >= 29)
            androidx.work.ForegroundInfo(7301, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else androidx.work.ForegroundInfo(7301, notif)
        setForeground(info)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // sin primer plano: continúa como antes
    }
}

private const val COLA_AUTOMATICA = "automatizacion_llamadas_cola"

/** Reclama (check + insert atómico dentro del proceso) el contacto de `clienteId` en este bloque.
 * Devuelve false si ya se le contactó en este bloque hoy: reintento del Worker tras ser
 * interrumpido, o Worker duplicado. Se reclama ANTES de llamar, así ni un reinicio a mitad de
 * cliente ni dos Workers repiten los SMS. */
private val reclamoMutex = kotlinx.coroutines.sync.Mutex()
private suspend fun reclamarContactoEnBloque(logDao: ContactoLogDao, clienteId: String, fechaDia: Long, bloqueIndex: Int): Boolean =
    reclamoMutex.withLock {
        if (logDao.contarContactosEnBloque(clienteId, fechaDia, bloqueIndex) > 0) false
        else { logDao.insertar(ContactoLogEntity(clienteId = clienteId, fechaDia = fechaDia, bloqueIndex = bloqueIndex)); true }
    }

// ============================================================
// Lógica de contacto compartida entre el worker de bloque normal
// y el de catchup — llama al titular (+ SMS) y manda SMS a las
// referencias, reusando CallHelper/SmsHelper. Lee su propia
// ConfiguracionAutomatizacionEntity (SIM, ocultar número, pausa,
// duración máxima) — independiente de la pantalla manual de
// Llamadas, para que ajustar una no afecte a la otra.
// ============================================================
/** Marca `numero`, silencia el micrófono mientras dura y espera a que termine (o la cuelga a la
 * fuerza al llegar a la duración máxima). Compartido por la llamada al titular y a cada Ref. */
private suspend fun llamarSilenciadoYEsperar(context: Context, subIdLlamada: Int?, numero: String, config: ConfiguracionAutomatizacionEntity) {
    CallHelper.realizarLlamada(context, subIdLlamada, numero, ocultarNumero = config.ocultarNumero)
    delay(2_000)
    // Silencia el micrófono del lado del titular durante la llamada automática -- es un
    // recordatorio unidireccional, no una conversación, así que no debe captar audio
    // ambiente mientras espera a que conteste o cuelgue. Se restaura al terminar para no
    // dejar el micrófono mudo en llamadas manuales posteriores.
    // try/finally con NonCancellable: si el interruptor se apaga (o el Worker se cancela
    // por tag) MIENTRAS esperamos a que la llamada termine, esperarFinOForzarColgar se
    // corta por la cancelación -- pero eso dejaría la llamada activa y el micrófono mudo.
    // El finally cuelga y des-silencia igual, aunque la corrutina ya esté cancelándose.
    CallHelper.silenciarMicrofono(context, true)
    try {
        CallHelper.esperarFinOForzarColgar(context, duracionMaximaMs = (config.duracionMaximaLlamada * 1_000L - 2_000L).coerceAtLeast(1_000L)) // ya pasaron 2 s desde que se marcó: la duración total cuenta desde la marcación
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            if (CallHelper.llamadaActiva(context)) CallHelper.colgarLlamadaConFallback(context)
            CallHelper.silenciarMicrofono(context, false)
        }
    }
}

private suspend fun procesarClienteLlamadaAutomatica(context: Context, r: MatrizEntity, sem: Int, config: ConfiguracionAutomatizacionEntity, logDao: ContactoLogDao, plantillaDao: PlantillaSmsDao, contactoExtraDao: ContactoExtraDao, yaContactados: MutableSet<String>? = null, varianteFija: Int? = null): String {
    // Devuelve un resumen de lo que se hizo -- lo usa probarClienteAhora() (botón de prueba en
    // Bloques de horario) para mostrarle a Diego exactamente qué se mandó y qué no, sin tener
    // que esperar a que corra un bloque real ni adivinar por qué algo no llegó.
    val resumen = StringBuilder()
    // La pausa configurada ("Segundos de pausa entre llamadas") debe respetarse entre CADA llamada,
    // no solo entre un cliente y el siguiente: antes, tras llamar al titular las referencias se
    // marcaban de inmediato, sin esperar nada.
    var huboLlamada = false
    val variante = varianteFija ?: logDao.contarTotalContactos(r.id)
    // En corridas reales (yaContactados != null) el SMS pasa por un candado final por número;
    // el botón de prueba (yaContactados == null) manda siempre, a propósito.
    fun smsPermitido(numero: String) = yaContactados == null || NumeroContactadoGuard.reservarSms(context, numero)
    val subIdLlamada = config.simSeleccionada
    val subIdSms = config.simSms // línea independiente para SMS -- puede ser distinta a la de llamadas
    // Solo en corridas reales (yaContactados != null): el botón de prueba no pasa por el guard.
    val ttLibre = yaContactados == null || r.numTT.isBlank() || NumeroContactadoGuard.reservar(context, r.numTT)
    if (r.numTT.isNotBlank() && ttLibre) {
        resumen.appendLine("• Llamada + SMS normal a TT (${r.numTT})")
        yaContactados?.add(ultimos10Digitos(r.numTT))
        llamarSilenciadoYEsperar(context, subIdLlamada, r.numTT, config)
        huboLlamada = true
        val smsTtOk = smsPermitido(r.numTT)
        if (smsTtOk) SmsHelper.enviarSms(context, subIdSms, r.numTT, MensajesCobranza.paraTT(plantillaDao, r.nombre, r.requisito, sem, variante))
        else resumen.appendLine("• SMS a TT omitido: ese número ya recibió un SMS automático hace menos de 30 min")
        // Oferta de descuento del día: se agrega como línea extra después del SMS normal,
        // solo al titular -- nunca a Ref1/Ref2 (ver el forEach de telefonosReferencia abajo,
        // que no la toca). Si no hay descuentoPago/descuentoAhorro capturados hoy, no manda nada.
        val oferta = MensajesCobranza.ofertaDescuento(r.descuentoPago, r.descuentoAhorro)
        if (oferta != null && smsTtOk) {
            SmsHelper.enviarSms(context, subIdSms, r.numTT, oferta)
            resumen.appendLine("• Oferta de descuento SÍ enviada (pago=${r.descuentoPago}, ahorro=${r.descuentoAhorro})")
        } else {
            resumen.appendLine("• Oferta de descuento NO enviada -- descuentoPago='${r.descuentoPago}' / descuentoAhorro='${r.descuentoAhorro}' (falta uno o los dos)")
        }
    } else {
        resumen.appendLine(if (r.numTT.isBlank()) "• Sin NumTT -- no se llamó ni se mandó nada al titular" else "• TT contactado hace menos de 15 min -- se omite para no repetir")
    }
    // Referencias propias del cliente (Ref1 a Ref4 capturados en Matriz) + referencias extra
    // confirmadas a mano desde Filtrar (números de un "cercano" que probablemente conoce al
    // titular). A CADA una se le llama (mismo silenciado de micrófono y duración máxima que al
    // titular) y después se le manda el mensaje de referencia, mencionando siempre el nombre
    // del titular (r.nombre), nunca el del cercano de donde salió el número. Se omiten números
    // repetidos (por sus últimos 10 dígitos), el mismo del titular (ya se llamó) y basura sin
    // dígitos suficientes (ej. "N/A").
    fun ultimos10(t: String) = t.filter { it.isDigit() }.takeLast(10)
    val ttDigitos = ultimos10(r.numTT)
    val telefonosReferencia = (listOf(r.ref1, r.ref2, r.ref3, r.ref4) + contactoExtraDao.obtenerPara(r.id).map { it.telefono })
        .mapNotNull { it?.trim()?.takeIf { t -> t.filter { c -> c.isDigit() }.length >= 7 } }
        .distinctBy { ultimos10(it) }
        .filter { ttDigitos.isEmpty() || ultimos10(it) != ttDigitos }
        // Una referencia compartida por varios clientes (o que ya es titular de otro) se contacta
        // UNA sola vez por corrida: antes recibía un SMS (y llamada) por cada cliente, todos juntos.
        .filter { yaContactados == null || ultimos10(it) !in yaContactados }
        .filter { yaContactados == null || NumeroContactadoGuard.reservar(context, it) } // no repetir al mismo número en 15 min, por cualquier camino
    yaContactados?.addAll(telefonosReferencia.map { ultimos10(it) })
    telefonosReferencia.forEach { tel ->
        if (huboLlamada) delay(config.segundosPausaEntreLlamadas * 1_000L)
        llamarSilenciadoYEsperar(context, subIdLlamada, tel, config)
        huboLlamada = true
        if (smsPermitido(tel)) SmsHelper.enviarSms(context, subIdSms, tel, MensajesCobranza.paraReferencia(plantillaDao, r.nombre, sem, variante))
    }
    if (telefonosReferencia.isNotEmpty()) resumen.appendLine("• Llamada + SMS de referencia a ${telefonosReferencia.size} número(s): ${telefonosReferencia.joinToString(", ")}")
    else resumen.appendLine("• Sin referencias con número válido (Ref1-Ref4 / contactos extra)")
    return resumen.toString().trim()
}

/** Botón de prueba (Bloques de horario): ejecuta llamada+SMS+oferta para UN cliente elegido por
 * ID, YA MISMO -- sin esperar a que un bloque real corra ni depender de si el cliente le toca
 * o no en el bloque actual (ReglaRepeticion.debeContactarseEnBloque). Sirve para separar 2
 * preguntas que se confunden fácil: "¿el código de mandar la oferta funciona?" vs "¿le toca a
 * este cliente ser contactado en el bloque de ahorita?". No registra ContactoLogEntity (no debe
 * contar como un contacto real hacia la meta de la semana ni afectar el cálculo del catchup). */
suspend fun ejecutarPruebaCliente(context: Context, clienteId: String): String {
    val container = (context.applicationContext as MainApplication).container
    val matrizDao = container.database.matrizDao()
    val plantillaDao = container.database.plantillaSmsDao()
    val configDao = container.database.configuracionAutomatizacionDao()
    val contactoExtraDao = container.database.contactoExtraDao()
    // logDao real solo para leer la variante de rotación de plantilla -- se le pasa un log
    // "de mentiras" (no se inserta nada) para no ensuciar el conteo de contactos reales.
    val logDao = container.database.contactoLogDao()

    val r = matrizDao.getById(clienteId) ?: return "No existe ningún cliente con ID '$clienteId'"
    val sem = r.semana.trim().toIntOrNull()
    if (sem == null || sem !in 1..5) return "Sem inválida ('${r.semana}') -- debe ser 1 a 5 para que el flujo automático lo procese"
    val config = configDao.obtenerOSembrar()

    return "Cliente: ${r.nombre} (Sem $sem, Estado='${r.estado}')\n" +
        procesarClienteLlamadaAutomatica(context, r, sem, config, logDao, plantillaDao, contactoExtraDao)
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
        mantenerEnPrimerPlano("Llamando y mandando SMS a los clientes del bloque")

        val bloqueId = inputData.getLong(KEY_BLOQUE_ID, -1)
        if (bloqueId < 0) return Result.failure()

        val container = (applicationContext as MainApplication).container
        val bloqueDao = container.database.bloqueHorarioDao()
        val matrizDao = container.database.matrizDao()
        val logDao = container.database.contactoLogDao()
        val plantillaDao = container.database.plantillaSmsDao()
        val configDao = container.database.configuracionAutomatizacionDao()
        val reglaSemanaDao = container.database.reglaSemanaDao()
        val contactoExtraDao = container.database.contactoExtraDao()

        val bloquesActivos = bloqueDao.obtenerBloquesActivos()
        val bloqueActualIndex = bloquesActivos.indexOfFirst { it.id == bloqueId }
        if (bloqueActualIndex == -1) return Result.success() // el bloque fue eliminado/desactivado desde entonces

        val registros = matrizDao.getAllMatriz().first()
        var config = configDao.obtenerOSembrar()
        val reglas = reglaSemanaDao.obtenerMapaOSembrar()
        val hoyMillis = inicioDeDiaMillis(LocalDate.now())
        var esPrimerContacto = true
        val yaContactados = mutableSetOf<String>() // teléfonos ya contactados en esta corrida (dedup de referencias compartidas)

        for (r in registros) {
            // Chequeo por-cliente (no solo al inicio de doWork()): si el interruptor se apaga
            // a la mitad del bloque, antes seguía procesando a TODOS los clientes restantes
            // porque solo se miraba activa() una vez al arrancar. Ahora corta aquí mismo, entre
            // un cliente y el siguiente, sin esperar a que termine todo el bloque.
            if (!AutomatizacionPrefs.activa(applicationContext)) break
            if (esPagado(r.estado)) continue
            val sem = r.semana.trim().toIntOrNull() ?: continue
            if (sem !in 1..5) continue

            val fechaAlta = ReglaRepeticion.fechaAltaDe(r) ?: continue
            if (fechaAlta.toLocalDate() != LocalDate.now()) continue // solo el día de alta -- el remanente lo cubre el catchup de mañana
            val bloqueAltaIndex = ReglaRepeticion.calcularBloqueDeAlta(fechaAlta, bloquesActivos)
            if (!ReglaRepeticion.debeContactarseEnBloque(sem, bloqueActualIndex, bloqueAltaIndex, reglas)) continue

            // Ya contactado en este bloque (Worker reiniciado por Android): no repetir ni esperar la pausa.
            if (logDao.contarContactosEnBloque(r.id, hoyMillis, bloqueActualIndex) > 0) continue

            config = configDao.obtenerOSembrar() // relee: lo que se ajuste en Bloques de horario aplica desde el siguiente cliente, incluso a mitad de bloque
            if (!esPrimerContacto) delay(config.segundosPausaEntreLlamadas * 1_000L)
            esPrimerContacto = false
            // Relee el registro justo antes de llamar (la lista `registros` es una foto de cuando
            // arrancó el bloque, y entre la pausa y las llamadas anteriores pueden pasar minutos):
            // si en ese tiempo se marcó Pagado (ticket, edición o sync), ya no se le llama.
            val fresco = matrizDao.getById(r.id) ?: continue
            if (esPagado(fresco.estado)) continue
            if (!AutomatizacionPrefs.activa(applicationContext)) break
            val variante = logDao.contarTotalContactos(r.id) // antes de reclamar, para no correr la rotación de plantillas
            if (!reclamarContactoEnBloque(logDao, r.id, hoyMillis, bloqueActualIndex)) continue
            procesarClienteLlamadaAutomatica(applicationContext, fresco, sem, config, logDao, plantillaDao, contactoExtraDao, yaContactados, variante)
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
        if (!AutomatizacionPrefs.catchupActiva(applicationContext)) return Result.success() // se apagó el interruptor de catchup mientras esta alarma esperaba
        mantenerEnPrimerPlano("Catchup de ayer en curso")

        val container = (applicationContext as MainApplication).container
        val matrizDao = container.database.matrizDao()
        val logDao = container.database.contactoLogDao()
        val plantillaDao = container.database.plantillaSmsDao()
        val configDao = container.database.configuracionAutomatizacionDao()
        val reglaSemanaDao = container.database.reglaSemanaDao()
        val contactoExtraDao = container.database.contactoExtraDao()
        val registros = matrizDao.getAllMatriz().first()
        var config = configDao.obtenerOSembrar()
        val entidadesRegla = reglaSemanaDao.obtenerEntidadesOSembrar()
        val reglas = entidadesRegla.associate { it.semana to it.offsetsList() }
        val semanasConCatchup = entidadesRegla.filter { it.catchupActivo }.map { it.semana }.toSet()
        val ayer = LocalDate.now().minusDays(1)
        val ayerMillis = inicioDeDiaMillis(ayer)
        var esPrimerContacto = true
        val yaContactados = mutableSetOf<String>()

        for (r in registros) {
            // Mismo chequeo por-cliente que el worker de bloque normal (ver comentario ahí):
            // corta el catchup en curso en cuanto se apaga cualquiera de los 2 interruptores.
            if (!AutomatizacionPrefs.activa(applicationContext)) break
            if (!AutomatizacionPrefs.catchupActiva(applicationContext)) break
            if (esPagado(r.estado)) continue
            val sem = r.semana.trim().toIntOrNull() ?: continue
            if (sem !in 1..5) continue
            if (sem !in semanasConCatchup) continue // catchup apagado para esta semana específica

            // Bug corregido: antes no se filtraba por fecha de alta, así que el catchup
            // recontactaba a CUALQUIER cliente activo con sem 1-5 todos los días (su meta
            // semanal completa casi siempre es mayor a los contactos hechos solo "ayer"),
            // sin importar cuándo se había dado de alta -- por eso aparecían llamadas a
            // nombres que no correspondían al día anterior. El catchup de 8:15/9:15 debe
            // cubrir únicamente a quien se dio de alta AYER (mismo día que procesó el
            // worker normal de bloques), igual que ese worker solo procesa altas de HOY.
            val fechaAlta = ReglaRepeticion.fechaAltaDe(r) ?: continue
            if (fechaAlta.toLocalDate() != ayer) continue

            val contactosAyer = logDao.contarContactosEnDia(r.id, ayerMillis)
            val deficit = ReglaRepeticion.calcularDeficit(sem, contactosAyer, reglas)
            if (deficit <= 0) continue

            config = configDao.obtenerOSembrar() // relee: lo que se ajuste en Bloques de horario aplica desde el siguiente cliente, incluso a mitad de bloque
            if (!esPrimerContacto) delay(config.segundosPausaEntreLlamadas * 1_000L)
            esPrimerContacto = false
            val fresco = matrizDao.getById(r.id) ?: continue // relee: puede haberse marcado Pagado durante la pausa
            if (esPagado(fresco.estado)) continue
            if (!AutomatizacionPrefs.activa(applicationContext)) break
            procesarClienteLlamadaAutomatica(applicationContext, fresco, sem, config, logDao, plantillaDao, contactoExtraDao, yaContactados)
            logDao.insertar(ContactoLogEntity(clienteId = r.id, fechaDia = ayerMillis, bloqueIndex = -1))
        }

        // Limpieza: el catchup solo mira "ayer", no hace falta conservar más de una semana.
        logDao.limpiarAnteriores(inicioDeDiaMillis(LocalDate.now().minusDays(7)))
        return Result.success()
    }
}
