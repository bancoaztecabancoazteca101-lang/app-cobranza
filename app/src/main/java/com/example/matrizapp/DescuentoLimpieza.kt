package com.example.matrizapp
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

private const val REQUEST_CODE_LIMPIEZA_DESCUENTO = 90211
private const val HORA_LIMPIEZA_DESCUENTO = 0
private const val MINUTO_LIMPIEZA_DESCUENTO = 0

/** Programa (o reprograma) la alarma de limpieza diaria de la oferta de descuento (campos
 * descuentoPago/descuentoAhorro de Matriz) a las 00:00 -- la oferta es válida solo por el
 * día en que se capturó, así que al cruzar la medianoche se borra sola para no seguir
 * ofreciendo por SMS un descuento ya vencido. Mismo patrón que programarLimpiezaRutaIA: se
 * llama al arrancar la app (AppContainer.init) y al reiniciar el dispositivo
 * (BootCompletedReceiver), porque un reboot borra TODAS las alarmas de AlarmManager. */
fun programarLimpiezaDescuento(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val ahora = LocalDateTime.now()
    var disparo = ahora.toLocalDate().atTime(HORA_LIMPIEZA_DESCUENTO, MINUTO_LIMPIEZA_DESCUENTO)
    if (disparo.isBefore(ahora)) disparo = disparo.plusDays(1)
    val millis = disparo.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val intent = Intent(context, DescuentoLimpiezaAlarmReceiver::class.java)
    val pending = PendingIntent.getBroadcast(
        context, REQUEST_CODE_LIMPIEZA_DESCUENTO, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    try {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
    } catch (e: SecurityException) {
        // Dispositivo con "Alarmas y recordatorios exactos" desactivado para la app (Android 12+):
        // se degrada a una alarma no exacta -- puede correr con algunos minutos de retraso, pero
        // sigue limpiando la oferta cada día sin necesidad de que Diego abra la app.
        alarmManager.set(AlarmManager.RTC_WAKEUP, millis, pending)
    }
}

class DescuentoLimpiezaAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Reprogramar primero para mañana, sin importar si la limpieza de hoy falla por algún
        // motivo -- así nunca se pierde el ciclo diario por una falla puntual.
        programarLimpiezaDescuento(context)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = (context.applicationContext as MainApplication).container.database.matrizDao()
                // Dato 100% local (no vive en el Sheet, ver el fix en SheetsRepository.refreshMatriz),
                // así que no hace falta marcar isDirty ni disparar sync -- solo limpiar Room.
                dao.limpiarDescuentosVencidos()
            } catch (e: Exception) {
                // Sin UI que avisar desde un BroadcastReceiver; se reintenta mañana igual.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
