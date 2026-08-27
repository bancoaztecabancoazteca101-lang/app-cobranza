package com.example.matrizapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/** Representa una línea activa del teléfono para el selector de SIM. */
data class LineaSim(val subscriptionId: Int, val etiqueta: String)

object SmsHelper {

    fun tienePermisos(context: Context): Boolean {
        val sms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val telefono = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        return sms && telefono
    }

    /** Lista las líneas activas del dispositivo (una o dos si es dual-SIM). Si no hay permiso o
     * falla la lectura, regresa una lista vacía y quien llame debe usar el envío sin subscriptionId. */
    fun lineasActivas(context: Context): List<LineaSim> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        return try {
            val sm = SubscriptionManager.from(context)
            val lista = sm.activeSubscriptionInfoList ?: return emptyList()
            lista.map { info ->
                val nombre = info.displayName?.toString()?.takeIf { it.isNotBlank() }
                    ?: info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                    ?: "SIM ${info.simSlotIndex + 1}"
                val numero = info.number?.takeIf { it.isNotBlank() }
                val etiqueta = if (numero != null) "$nombre ($numero)" else "$nombre — Slot ${info.simSlotIndex + 1}"
                LineaSim(info.subscriptionId, etiqueta)
            }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** Envía un SMS por la línea indicada (o la default del sistema si subscriptionId es null).
     * Divide mensajes largos automáticamente (multipart). Regresa true si la llamada de envío
     * no lanzó excepción (no confirma entrega, solo que el sistema aceptó el mensaje). */
    fun enviarSms(context: Context, subscriptionId: Int?, numero: String, mensaje: String): Boolean {
        return try {
            val smsManager: SmsManager = if (subscriptionId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
            } else if (subscriptionId != null) {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val partes = smsManager.divideMessage(mensaje)
            smsManager.sendMultipartTextMessage(numero, null, partes, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Reemplaza los placeholders de la plantilla: %nombre% (nombre del cliente/titular),
     * %monto% (Req/importe de atraso, solo aplica a plantilla TT), %agente% y %contacto%
     * (datos fijos del gestor, solo aplican a plantilla Referencia). */
    fun armarMensaje(plantilla: String, nombre: String, monto: String = "", agente: String = "", contacto: String = ""): String =
        plantilla
            .replace("%nombre%", nombre.trim(), ignoreCase = true)
            .replace("%monto%", monto.trim(), ignoreCase = true)
            .replace("%agente%", agente.trim(), ignoreCase = true)
            .replace("%contacto%", contacto.trim(), ignoreCase = true)
}
