package com.example.matrizapp.wear

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Consulta independiente desde el reloj. No usa el Data Layer ni requiere que Matriz
 * esté instalada/actualizada en el teléfono.
 */
object WearCloudRepository {
    private const val API_URL = "https://script.google.com/macros/s/AKfycby8NDlJmgI4amY6wqP7Wpqt0dLPYdL1LlnLdJKmzJb-xcJou89hXQWxPGF4GnTMnHLYag/exec"

    fun fetchSnapshot(token: String): JSONObject {
        require(token.length >= 24) { "Configura el token de acceso de Wear OS." }
        val connection = (URL(API_URL).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val requestBody = JSONObject().put("action", "wear_snapshot").put("token", token).toString()
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("Servidor respondió HTTP $status")
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) {
                when (json.optString("error")) {
                    "wear_unauthorized" -> throw IOException("Token incorrecto o backend sin actualizar.")
                    "wear_data_unavailable" -> throw IOException("No se pudieron leer las hojas de Matriz.")
                    else -> throw IOException("El servidor no entregó los datos.")
                }
            }
            return json
        } finally {
            connection.disconnect()
        }
    }
}
