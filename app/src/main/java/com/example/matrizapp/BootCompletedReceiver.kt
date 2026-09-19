package com.example.matrizapp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Repone las alarmas de Retorno/App, bloques de llamadas y limpieza de Ruta IA cuando Android
 * las borra: al reiniciar el dispositivo (BOOT_COMPLETED) o al instalar una actualización de la
 * app (MY_PACKAGE_REPLACED) -- ambos casos hacen que AlarmManager pierda TODO lo programado.
 * Lee directamente de Room (no depende de que la app esté abierta ni de que exista un
 * ViewModel vivo). */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context.applicationContext)
                val helper = NotificacionesHelper(context.applicationContext)
                helper.sincronizarAlarmasRetorno(db.filtroDao().getAll().first())
                helper.sincronizarAlarmasRetornoMatriz(db.matrizDao().getAllMatriz().first())
                // Bloques de llamadas automáticas: AlarmManager pierde todas sus alarmas al
                // reiniciar, igual que las de Retorno — se vuelven a programar aquí mismo.
                LlamadaAutomaticaScheduler(context.applicationContext, db.bloqueHorarioDao()).reprogramarTodos()
                // Limpieza diaria de Ruta IA: misma razón, se pierde con el reinicio.
                programarLimpiezaRutaIA(context.applicationContext)
                // Limpieza diaria de la oferta de descuento: misma razón, se pierde con el reinicio.
                programarLimpiezaDescuento(context.applicationContext)
            } catch (e: Exception) {
                // Si algo falla aquí no hay forma de avisarle al usuario (no hay UI); se
                // reintentará solo la próxima vez que se abra la app y cambien los datos.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
