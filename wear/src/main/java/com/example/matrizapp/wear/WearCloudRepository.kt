package com.example.matrizapp.wear

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Consulta independiente desde el reloj. No usa el Data Layer ni requiere que Matriz
 * esté instalada/actualizada en el teléfono.
 */
object WearCloudRepository {
    private const val API_URL = "https://script.google.com/macros/s/AKfycby8NDlJmgI4amY6wqP7Wpqt0dLPYdL1LlnLdJKmzJb-xcJou89hXQWxPGF4GnTMnHLYag/exec"

    fun fetchSnapshot(token: String): JSONObject {
        require(token.length >= 24) { "Configura el token de acceso de Wear OS." }
        val encoded = URLEncoder.encode(token, "UTF-8")
        val connection = (URL("$API_URL?action=wear_snapshot&token=$encoded").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
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
