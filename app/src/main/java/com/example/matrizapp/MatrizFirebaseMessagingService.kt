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
        val nombre = data["nombre"]?.takeIf { it.isNotBlank() }
        val requerido = data["requerido"]?.takeIf { it.isNotBlank() }
        val eventId = data["eventId"] ?: ""
        val ubicacion = data["ubicacion"]
        val colonia = data["colonia"]?.takeIf { it.isNotBlank() }
        val calle = data["calle"]?.takeIf { it.isNotBlank() }

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // La notificación ya no muestra el teléfono. Muestra el requerido/saldo y la dirección.
        val fallbackBody = data["body"] ?: message.notification?.body ?: "Nuevo retorno"
        val direccion = listOfNotNull(colonia, calle).joinToString(" — ")
        val body = buildString {
            if (!requerido.isNullOrBlank()) append("Requerido: $requerido")
            if (direccion.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(direccion)
            }
            if (isEmpty()) append(fallbackBody)
        }

        val displayTitle = if (!nombre.isNullOrBlank()) "$title: $nombre" else title

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(displayTitle)
            .setContentText(body.replace("\n", " — "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        // Botón "Iniciar ruta": abre Google Maps directamente en navegación.
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

        NotificationManagerCompat.from(this).notify(
            (eventId.ifBlank { System.currentTimeMillis().toString() }).hashCode(),
            builder.build()
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
            description = "Avisos de RETORNO con requerido, colonia, calle y acceso directo a la ruta"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
            setSound(soundUri, audioAttributes)
        }

        notificationManager.createNotificationChannel(channel)
    }
}
