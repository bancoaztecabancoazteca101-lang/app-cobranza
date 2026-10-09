package com.example.matrizapp.wear

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Guarda la última consulta recibida para que siga disponible sin internet. */
class MatrizWearDataService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        when (event.path) {
            PATH_SNAPSHOT -> prefs.edit().putString(KEY_SNAPSHOT, String(event.data, Charsets.UTF_8))
                .putLong(KEY_UPDATED, System.currentTimeMillis()).remove(KEY_ERROR).apply()
            PATH_ERROR -> prefs.edit().putString(KEY_ERROR, String(event.data, Charsets.UTF_8)).apply()
        }
    }
    companion object {
        const val PATH_REQUEST = "/matriz/request"
        const val PATH_SNAPSHOT = "/matriz/snapshot"
        const val PATH_ERROR = "/matriz/error"
        const val PREFS = "matriz_wear_cache"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_UPDATED = "updated"
        const val KEY_ERROR = "error"
    }
}
