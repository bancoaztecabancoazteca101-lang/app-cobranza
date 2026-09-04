package com.example.matrizapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Recibe avisos FCM y los muestra como notificación del sistema. */
class MatrizFirebaseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelId = "retornos_multidispositivo"

    override fun onNewToken(token: String) {
        getSharedPreferences("multi_device_notifications", MODE_PRIVATE)
            .edit().putString("fcm_token", token).apply()
        scope.launch {
            MultiDeviceNotificationManager(this@MatrizFirebaseMessagingService).register()
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"] ?: message.notification?.title ?: "Matriz App"
        val body = data["body"] ?: message.notification?.body ?: "Nuevo retorno"
        val ubicacion = data["ubicacion"]
        val eventId = data["eventId"] ?: ""

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        // Si el retorno trae coordenadas "lat, lng", muestra un botón que abre
        // Google Maps directamente en modo navegación.
        val latLng = parseLatLng(ubicacion)
        if (latLng != null) {
            val navIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("google.navigation:q=${latLng.first},${latLng.second}&mode=d")
            ).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val navPendingIntent = PendingIntent.getActivity(
                this,
                ("nav_$eventId").hashCode(),
                navIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder.addAction(
                android.R.drawable.ic_menu_mylocation,
                "Iniciar ruta",
                navPendingIntent
            )
        }

        val notification = builder.build()

        NotificationManagerCompat.from(this).notify(
            (eventId.ifBlank { System.currentTimeMillis().toString() }).hashCode(),
            notification
        )
    }

    private fun parseLatLng(raw: String?): Pair<Double, Double>? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(",").map { it.trim() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (notificationManager.getNotificationChannel(channelId) != null) return

        val soundUri = android.media.RingtoneManager.getDefaultUri(
            android.media.RingtoneManager.TYPE_NOTIFICATION
        )

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            channelId,
            "Retornos multi-dispositivo",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Avisos de RETORNO con fecha y hora y acceso directo a la ruta"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
            setSound(soundUri, audioAttributes)
        }

        notificationManager.createNotificationChannel(channel)
    }
}
