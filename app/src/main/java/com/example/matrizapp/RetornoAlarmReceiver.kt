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
        val requerido = intent.getStringExtra("requerido")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val direccion = try {
                resolverColoniaYCalle(context, ubicacion)
            } catch (e: Exception) {
                null to null
            }
            NotificacionesHelper.mostrarNotificacion(
                context = context,
                id = id,
                nombre = nombre,
                numTT = numTT,
                estado = estado,
                colonia = direccion.first,
                calle = direccion.second,
                ubicacion = ubicacion,
                requerido = requerido
            )
            pendingResult.finish()
        }
    }
}
