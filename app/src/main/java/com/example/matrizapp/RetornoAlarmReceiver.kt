package com.example.matrizapp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RetornoAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nombre = intent.getStringExtra("nombre")?.takeIf { it.isNotBlank() } ?: "Cliente"
        val numTT = intent.getStringExtra("numTT")
        val estado = intent.getStringExtra("estado")
        NotificacionesHelper.mostrarNotificacion(context, nombre, numTT, estado)
    }
}
