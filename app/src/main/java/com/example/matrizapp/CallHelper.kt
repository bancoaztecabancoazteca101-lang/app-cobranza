package com.example.matrizapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

object CallHelper {

    fun tienePermisos(context: Context): Boolean {
        val llamar = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val telefono = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        return llamar && telefono
    }

    /** Colgar (TelecomManager.endCall) necesita ANSWER_PHONE_CALLS, disponible desde Android 8
     * (Oreo), pero endCall() en sí requiere Android 9 (Pie) o superior. No hace falta ser la
     * app de teléfono por defecto. */
    fun tienePermisoColgar(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
    }

    /** Marca directo (ACTION_CALL, sin pasar por el marcador) en la línea indicada si es dual-SIM. */
    fun realizarLlamada(context: Context, subscriptionId: Int?, numero: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$numero")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (subscriptionId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val handle = obtenerPhoneAccountHandle(context, subscriptionId)
                if (handle != null) intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            } catch (e: SecurityException) { /* usa la línea default */ }
        }
        context.startActivity(intent)
    }

    private fun obtenerPhoneAccountHandle(context: Context, subscriptionId: Int): PhoneAccountHandle? {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecomManager.callCapablePhoneAccounts.firstOrNull { handle ->
                val tmParaCuenta = context.getSystemService(TelephonyManager::class.java)?.createForPhoneAccountHandle(handle)
                tmParaCuenta?.subscriptionId == subscriptionId
            }
        } catch (e: SecurityException) { null }
    }

    /** Cuelga la llamada en curso. Regresa false si no hay permiso o falla. */
    fun colgarLlamada(context: Context): Boolean {
        if (!tienePermisoColgar(context)) return false
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecomManager.endCall()
        } catch (e: SecurityException) { false }
    }

    /** Silencia/des-silencia el micrófono del dispositivo (funciona en cualquier llamada activa,
     * sin permisos extra a los ya requeridos por CALL_PHONE/READ_PHONE_STATE). */
    fun silenciarMicrofono(context: Context, mute: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.isMicrophoneMute = mute
    }

    fun microfonoSilenciado(context: Context): Boolean =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMicrophoneMute

    /** true si hay una llamada en curso ahora mismo (offhook o ringing). */
    fun llamadaActiva(context: Context): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        @Suppress("DEPRECATION")
        return tm.callState != TelephonyManager.CALL_STATE_IDLE
    }

    /** Espera a que la llamada termine sola hasta duracionMaximaMs; si sigue activa al llegar
     * a ese límite (nadie contestó, nadie colgó, línea de prueba, etc.), la cuelga a la fuerza
     * con colgarLlamadaConFallback() para que el flujo nunca se quede atorado esperando
     * indefinidamente. */
    suspend fun esperarFinOForzarColgar(context: Context, duracionMaximaMs: Long) {
        esperarFinDeLlamada(context, timeoutMs = duracionMaximaMs)
        if (llamadaActiva(context)) {
            colgarLlamadaConFallback(context)
        }
    }

    /** Intenta colgar con la API oficial (TelecomManager.endCall()); en fabricantes que la
     * bloquean silenciosamente para apps que no son el marcador predeterminado (MIUI/Redmi es
     * el caso conocido), recurre al CallAccessibilityService para simular el toque en el botón
     * de colgar, igual que hace Tasker con AutoInput. Reintenta un par de veces porque la
     * pantalla de llamada puede tardar un instante en estar lista para recibir el clic. */
    suspend fun colgarLlamadaConFallback(context: Context): Boolean {
        if (colgarLlamada(context)) {
            kotlinx.coroutines.delay(500)
            if (!llamadaActiva(context)) return true
        }

        if (!CallAccessibilityService.servicioActivo()) return false

        repeat(3) { intento ->
            if (!llamadaActiva(context)) return true
            CallAccessibilityService.intentarColgar()
            kotlinx.coroutines.delay(700)
            if (!llamadaActiva(context)) return true
        }
        return !llamadaActiva(context)
    }

    /** Suspende hasta que el teléfono vuelva a IDLE después de haber estado en una llamada
     * (offhook/ringing), o hasta timeoutMs como respaldo si la detección falla por algún motivo
     * (para nunca dejar el Worker colgado indefinidamente). */
    suspend fun esperarFinDeLlamada(context: Context, timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                var vioLlamadaActiva = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            if (state != TelephonyManager.CALL_STATE_IDLE) vioLlamadaActiva = true
                            if (state == TelephonyManager.CALL_STATE_IDLE && vioLlamadaActiva) {
                                tm.unregisterTelephonyCallback(this)
                                if (cont.isActive) cont.resumeWith(Result.success(Unit))
                            }
                        }
                    }
                    try { tm.registerTelephonyCallback(context.mainExecutor, callback) } catch (e: SecurityException) {
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                        return@suspendCancellableCoroutine
                    }
                    cont.invokeOnCancellation { try { tm.unregisterTelephonyCallback(callback) } catch (e: Exception) { } }
                } else {
                    @Suppress("DEPRECATION")
                    val listener = object : PhoneStateListener() {
                        @Suppress("DEPRECATION")
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                            if (state != TelephonyManager.CALL_STATE_IDLE) vioLlamadaActiva = true
                            if (state == TelephonyManager.CALL_STATE_IDLE && vioLlamadaActiva) {
                                @Suppress("DEPRECATION") tm.listen(this, PhoneStateListener.LISTEN_NONE)
                                if (cont.isActive) cont.resumeWith(Result.success(Unit))
                            }
                        }
                    }
                    try {
                        @Suppress("DEPRECATION") tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                    } catch (e: SecurityException) {
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                        return@suspendCancellableCoroutine
                    }
                    cont.invokeOnCancellation { @Suppress("DEPRECATION") tm.listen(listener, PhoneStateListener.LISTEN_NONE) }
                }
            }
        }
    }
}
