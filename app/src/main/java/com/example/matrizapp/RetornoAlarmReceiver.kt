package com.example.matrizapp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RetornoAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: "sin_id"
        val nombre = intent.getStringExtra("nombre")?.takeIf { it.isNotBlank() } ?: "Cliente"
        val numTT = intent.getStringExtra("numTT")
        val estado = intent.getStringExtra("estado")
        val ubicacion = intent.getStringExtra("ubicacion")

        // Resolver la calle a partir de las coordenadas necesita el Geocoder (posiblemente
        // consulta internet/red), así que no puede hacerse directo en onReceive: se usa
        // goAsync() para tener tiempo de terminar la corrutina antes de que el sistema mate
        // este BroadcastReceiver.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val calle = try {
                resolverColoniaYCalle(context, ubicacion).second
            } catch (e: Exception) {
                null
            }
            NotificacionesHelper.mostrarNotificacion(context, id, nombre, numTT, estado, calle, ubicacion)
            pendingResult.finish()
        }
    }
}
