package com.example.matrizapp
import android.content.Context
import android.os.Build as AndroidBuild
import android.provider.Settings

/** Identifica el dispositivo y la build de la app que está corriendo, para poder saber desde
 * la hoja "Dispositivos" qué teléfonos se quedaron en una versión vieja del APK. */
object DeviceInfo {
    /** Id estable por instalación (no requiere permisos; no cambia entre reinicios/sesiones,
     * solo cambia si se reinstala de cero o se restaura de fábrica). */
    fun androidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "sin_id"

    fun modelo(): String = "${AndroidBuild.MANUFACTURER} ${AndroidBuild.MODEL}"

    /** SHA corto del commit + número de corrida de GitHub Actions con los que se compiló este
     * APK (inyectados en build.gradle desde las variables de entorno que Actions ya expone,
     * sin tocar el workflow). "local-0" si se compiló fuera de Actions (ej. Android Studio). */
    val buildId: String get() = "${BuildConfig.BUILD_SHA}-${BuildConfig.BUILD_NUMBER}"
}
