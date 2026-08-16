package com.example.matrizapp
import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MainApplication : Application() {
    lateinit var container: AppContainer
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
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
