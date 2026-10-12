package com.example.matrizapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Aviso para no olvidar cambiar de cartucho: al llegar a [VISITAS_AVISO] visitas en el cartucho activo del día se
 * manda una notificación en la barra del teléfono con un botón "Cambiar a cartucho N+1" (y un aviso dentro de la app).
 * Se avisa UNA sola vez por día y por cartucho. */
object CartuchoAviso {
    const val VISITAS_AVISO = 20
    const val CANAL = "cartucho_aviso"
    const val NOTIF_ID = 7301
    const val EXTRA_DIA = "dia"
    const val EXTRA_NUEVO = "nuevo"
    private const val PREFS = "cartucho_aviso"

    /** true si es la primera vez que se avisa de este cartucho hoy (y lo deja marcado). */
    fun marcarSiNuevo(context: Context, dia: Long, cartucho: Int): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val clave = "$dia|$cartucho"
        if (p.getBoolean(clave, false)) return false
        // se limpian los avisos de otros días
        val editor = p.edit()
        p.all.keys.filter { !it.startsWith("$dia|") }.forEach { editor.remove(it) }
        editor.putBoolean(clave, true).apply()
        return true
    }

    fun mostrar(context: Context, dia: Long, cartuchoActivo: Int, visitas: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CANAL) == null) {
                nm.createNotificationChannel(NotificationChannel(CANAL, "Cambio de cartucho", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Te recuerda cambiar de cartucho al llegar a $VISITAS_AVISO visitas"
                })
            }
        }
        val nuevo = cartuchoActivo + 1
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val cambiar = PendingIntent.getBroadcast(
            context, NOTIF_ID,
            Intent(context, CambiarCartuchoReceiver::class.java).putExtra(EXTRA_DIA, dia).putExtra(EXTRA_NUEVO, nuevo), flags
        )
        val abrirApp = PendingIntent.getActivity(
            context, NOTIF_ID, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags
        )
        val texto = "Llevas $visitas visitas en el cartucho $cartuchoActivo. Cambia al cartucho $nuevo para no olvidarlo."
        val notif = NotificationCompat.Builder(context, CANAL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Cartucho $cartuchoActivo: $visitas visitas")
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(abrirApp)
            .addAction(android.R.drawable.ic_menu_rotate, "Cambiar a cartucho $nuevo", cambiar)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
    }
}

/** Botón "Cambiar a cartucho N+1" de la notificación: sube el cartucho activo del día sin abrir la app. */
class CambiarCartuchoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dia = intent.getLongExtra(CartuchoAviso.EXTRA_DIA, 0L)
        val nuevo = intent.getIntExtra(CartuchoAviso.EXTRA_NUEVO, 0)
        if (dia == 0L || nuevo <= 1) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = (context.applicationContext as MainApplication).container.database.visitaMapaDao()
                dao.asegurarCartuchoDia(dia)
                dao.setCartuchoActivo(dia, nuevo)
                NotificationManagerCompat.from(context).cancel(CartuchoAviso.NOTIF_ID)
                Handler(Looper.getMainLooper()).post { Toast.makeText(context, "Cartucho $nuevo activo", Toast.LENGTH_SHORT).show() }
            } finally {
                pending.finish()
            }
        }
    }
}
