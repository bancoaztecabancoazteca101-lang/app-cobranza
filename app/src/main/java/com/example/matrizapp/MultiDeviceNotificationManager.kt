package com.example.matrizapp

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

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
        ?: (Build.MANUFACTURER + " " + Build.MODEL).trim()

    fun setDeviceName(name: String) {
        if (name.trim().isNotEmpty()) prefs.edit().putString("device_name", name.trim()).apply()
    }

    fun isEnabled(): Boolean = prefs.getBoolean("enabled", true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("enabled", enabled).apply()
    }

    suspend fun getFcmToken(): String = FirebaseMessaging.getInstance().token.await()

    suspend fun register(): Result<Unit> = request("register", JSONObject().apply {
        put("deviceId", installationId)
        put("name", getDeviceName())
        put("fcmToken", getFcmToken())
        put("appVersion", try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { "" })
    }).map { }

    suspend fun listDevices(): Result<List<RemoteDevice>> = request("list").map { root ->
        val array = root.optJSONArray("devices") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(RemoteDevice(
                    deviceId = o.optString("deviceId"),
                    name = o.optString("name", "Dispositivo"),
                    enabled = o.optBoolean("enabled", true),
                    lastSeen = o.optString("lastSeen"),
                    platform = o.optString("platform", "android")
                ))
            }
        }
    }

    suspend fun setRemoteEnabled(deviceId: String, enabled: Boolean): Result<Unit> = request("toggle", JSONObject().apply {
        put("deviceId", deviceId)
        put("enabled", enabled)
    }).map { }

    suspend fun sendTest(deviceId: String? = null): Result<Int> = request("test", JSONObject().apply {
        if (!deviceId.isNullOrBlank()) put("deviceId", deviceId)
        put("title", "Matriz App")
        put("body", "Notificación de prueba multi-dispositivo")
    }).map { it.optInt("sent", 0) }

    private suspend fun request(action: String, payload: JSONObject = JSONObject()): Result<JSONObject> = withContext(Dispatchers.IO) {
        val endpoint = NotificacionesMultiDispositivoConfig.NOTIFICATION_API_URL
        if (endpoint == "PENDIENTE_CONFIGURAR") return@withContext Result.failure(IllegalStateException("Backend de notificaciones no configurado"))
        runCatching {
            val body = JSONObject(payload.toString()).apply {
                put("action", action)
                put("apiKey", NotificacionesMultiDispositivoConfig.API_KEY)
            }
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (code !in 200..299) error("Backend HTTP $code: $response")
            val root = JSONObject(response)
            if (!root.optBoolean("ok", false)) error(root.optString("error", "Error del backend"))
            root
        }
    }

    data class RemoteDevice(
        val deviceId: String,
        val name: String,
        val enabled: Boolean,
        val lastSeen: String,
        val platform: String
    )
}
