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

private const val REQUEST_CODE_LIMPIEZA_RUTA_IA = 90210
private const val HORA_LIMPIEZA = 4
private const val MINUTO_LIMPIEZA = 0

/** Programa (o reprograma) la alarma de limpieza diaria de Ruta IA a las 4:00 AM. Se llama al
 * arrancar la app (AppContainer.init) y al reiniciar el dispositivo (BootCompletedReceiver,
 * porque un reboot borra TODAS las alarmas de AlarmManager). El propio receptor se vuelve a
 * reprogramar para el día siguiente cada vez que dispara -- no depende de Apps Script ni de
 * ninguna cuenta de Google aparte de la que ya usa la app para Sheets. */
fun programarLimpiezaRutaIA(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val ahora = LocalDateTime.now()
    var disparo = ahora.toLocalDate().atTime(HORA_LIMPIEZA, MINUTO_LIMPIEZA)
    if (disparo.isBefore(ahora)) disparo = disparo.plusDays(1)
    val millis = disparo.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val intent = Intent(context, RutaIALimpiezaAlarmReceiver::class.java)
    val pending = PendingIntent.getBroadcast(
        context, REQUEST_CODE_LIMPIEZA_RUTA_IA, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    try {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
    } catch (e: SecurityException) {
        // Dispositivo con "Alarmas y recordatorios exactos" desactivado para la app (Android 12+):
        // se degrada a una alarma no exacta -- puede correr con algunos minutos de retraso, pero
        // sigue limpiando la ruta cada día sin necesidad de que Diego abra la app.
        alarmManager.set(AlarmManager.RTC_WAKEUP, millis, pending)
    }
}

class RutaIALimpiezaAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Reprogramar primero para mañana, sin importar si la limpieza de hoy falla por algún
        // motivo (sin red, etc.) -- así nunca se pierde el ciclo diario por una falla puntual.
        programarLimpiezaRutaIA(context)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as MainApplication).container
                container.database.rutaIADao().deleteAll()
                try {
                    container.repository.reemplazarRutaIAEnSheet(emptyList())
                } catch (e: Exception) {
                    // Sin conexión a esta hora: la tabla local ya quedó limpia, que es lo que
                    // más importa para que la próxima ruta del día no se mezcle con la de ayer.
                    // La hoja remota se limpiará igual en el próximo `reemplazarRutaIAEnSheet`
                    // (al procesar el siguiente lote de fotos), que primero borra y luego sube.
                }
            } catch (e: Exception) {
                // Sin UI que avisar desde un BroadcastReceiver; se reintenta mañana igual.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
