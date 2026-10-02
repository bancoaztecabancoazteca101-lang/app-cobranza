package com.example.matrizapp

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainApplication : Application(), coil.ImageLoaderFactory {
    lateinit var container: AppContainer
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Coil con tope de memoria para fotos (12 % de la RAM): en teléfonos modestos evita presión de memoria/GC. */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.12).build() }
        .crossfade(false)
        .build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SmsStatusWorker.programarPeriodicamente(this)
        notificationScope.launch {
            runCatching { MultiDeviceNotificationManager(this@MainApplication).register() }
        }
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(filesDir, "crash_log.txt").writeText(sw.toString())
            } catch (e: Exception) { /* no-op */ }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
