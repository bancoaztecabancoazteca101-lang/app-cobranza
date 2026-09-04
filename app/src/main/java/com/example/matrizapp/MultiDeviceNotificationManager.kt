package com.example.matrizapp

import android.content.Context
import android.provider.Settings
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import java.util.UUID

/** Registro local de este teléfono/tablet para notificaciones multi-dispositivo.
 * El backend debe almacenar el token FCM asociado al installationId y permitir habilitarlo.
 */
class MultiDeviceNotificationManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("multi_device_notifications", Context.MODE_PRIVATE)

    val installationId: String
        get() {
            val current = prefs.getString("installation_id", null)
            if (current != null) return current
            val created = UUID.randomUUID().toString()
            prefs.edit().putString("installation_id", created).apply()
            return created
        }

    fun getDeviceName(): String = prefs.getString("device_name", null)
        ?: Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        ?: "Dispositivo"

    fun setDeviceName(name: String) {
        prefs.edit().putString("device_name", name.trim()).apply()
    }

    fun isEnabled(): Boolean = prefs.getBoolean("enabled", true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("enabled", enabled).apply()
    }

    suspend fun getFcmToken(): String = FirebaseMessaging.getInstance().token.await()
}
