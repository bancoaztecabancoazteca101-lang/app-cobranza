package com.example.matrizapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RetornoAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val idOriginal = intent.getStringExtra("id") ?: "sin_id"
        var id = idOriginal
        var nombre = intent.getStringExtra("nombre")?.takeIf { it.isNotBlank() } ?: "Cliente"
        var numTT = intent.getStringExtra("numTT")
        var estado = intent.getStringExtra("estado")
        var ubicacion = intent.getStringExtra("ubicacion")
        var requerido = intent.getStringExtra("requerido")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Recupera los datos actuales de Room al momento de disparar la alarma.
                // Esto evita que una alarma programada anteriormente pierda Requerido/WhatsApp
                // si esos datos se actualizaron después de programarla.
                val db = AppDatabase.getDatabase(context.applicationContext)
                val matrizId = if (id.startsWith("matriz_")) id.removePrefix("matriz_") else id
                val matriz = db.matrizDao().getById(matrizId)
                if (matriz != null) {
                    id = matriz.id
                    nombre = matriz.nombre.ifBlank { nombre }
                    numTT = matriz.numTT.ifBlank { numTT ?: "" }
                    estado = matriz.estado.ifBlank { estado ?: "retorno" }
                    ubicacion = matriz.ubicacion ?: ubicacion
                    requerido = matriz.requisito.ifBlank { requerido ?: "" }
                } else {
                    val filtro = db.filtroDao().getById(id)
                    if (filtro != null) {
                        nombre = filtro.nombre.ifBlank { nombre }
                        numTT = filtro.numTT.ifBlank { numTT ?: "" }
                        estado = filtro.estado.ifBlank { estado ?: "retorno" }
                        ubicacion = filtro.ubicacion ?: ubicacion
                        requerido = filtro.req?.ifBlank { requerido ?: "" } ?: requerido
                    }
                }

                val direccion = try {
                    resolverColoniaYCalle(context, ubicacion)
                } catch (e: Exception) {
                    null to null
                }

                NotificacionesHelper.mostrarNotificacion(
                    context = context,
                    id = idOriginal,
                    nombre = nombre,
                    numTT = numTT,
                    estado = estado,
                    colonia = direccion.first,
                    calle = direccion.second,
                    ubicacion = ubicacion,
                    requerido = requerido
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
