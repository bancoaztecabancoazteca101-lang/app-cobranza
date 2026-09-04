package com.example.matrizapp

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainApplication : Application() {
    lateinit var container: AppContainer
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
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
