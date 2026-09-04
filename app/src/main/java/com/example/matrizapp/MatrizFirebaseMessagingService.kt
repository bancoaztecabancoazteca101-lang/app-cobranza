package com.example.matrizapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
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

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        NotificationManagerCompat.from(this).notify(
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            notification
        )
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
            description = "Avisos de RETORNO con fecha y hora"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
            setSound(soundUri, audioAttributes)
        }

        notificationManager.createNotificationChannel(channel)
    }
}
