package com.example.matrizapp

import android.content.Context
import android.net.Uri
import org.json.JSONObject

data class ClienteRutaIAJson(
    val nombre: String,
    val cu: String?,
    val direccion: String,
    val colonia: String?,
    val cp: String?,
    val diasAtraso: Int?,
    val saldo: Double?,
    val requerido: Double?
)

data class ResultadoImportacionRutaIA(
    val clientes: List<ClienteRutaIAJson>,
    val advertencias: List<String> = emptyList()
)

private fun JSONObject.stringOrNull(vararg keys: String): String? {
    for (key in keys) if (has(key) && !isNull(key)) optString(key).trim().ifBlank { null }?.let { return it }
    return null
}

private fun JSONObject.doubleOrNull(vararg keys: String): Double? {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val raw = opt(key)?.toString()?.trim()?.replace("$", "")?.replace(",", "") ?: continue
        raw.toDoubleOrNull()?.let { return it }
    }
    return null
}

private fun JSONObject.intOrNull(vararg keys: String): Int? {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        opt(key)?.toString()?.trim()?.toIntOrNull()?.let { return it }
    }
    return null
}

fun leerClientesRutaIAJson(context: Context, uri: Uri): ResultadoImportacionRutaIA {
    val texto = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        ?: throw IllegalArgumentException("No se pudo leer el archivo seleccionado")
    val raiz = JSONObject(texto)
    val arreglo = raiz.optJSONArray("clientes")
        ?: throw IllegalArgumentException("El JSON debe contener un arreglo 'clientes'")
    val clientes = mutableListOf<ClienteRutaIAJson>()
    val advertencias = mutableListOf<String>()
    for (i in 0 until arreglo.length()) {
        val o = arreglo.optJSONObject(i)
        if (o == null) { advertencias += "Registro ${i + 1}: objeto inválido"; continue }
        val nombre = o.stringOrNull("nombre")
        val direccion = o.stringOrNull("direccion")
        if (nombre == null || direccion == null) { advertencias += "Registro ${i + 1}: falta nombre o dirección"; continue }
        val saldo = o.doubleOrNull("saldo", "Saldo")
        val requerido = o.doubleOrNull("requerido", "pago_requerido", "pagoRequerido")
        if (saldo != null && requerido != null && kotlin.math.abs(saldo - requerido) > 0.01)
            advertencias += "$nombre: saldo y requerido son diferentes"
        clientes += ClienteRutaIAJson(
            nombre, o.stringOrNull("cu", "CU"), direccion,
            o.stringOrNull("colonia"), o.stringOrNull("cp", "codigo_postal"),
            o.intOrNull("dias_atraso", "diasAtraso"), saldo, requerido
        )
    }
    if (clientes.isEmpty()) throw IllegalArgumentException("No hay clientes válidos en el JSON")
    return ResultadoImportacionRutaIA(clientes, advertencias)
}

const val RUTA_IA_JSON_INSTRUCCION_GEMINI = """
Devuelve únicamente JSON válido, sin Markdown ni texto adicional, con esta estructura:
{"clientes":[{"nombre":"JUAN PEREZ","cu":"12-34-56789-123","direccion":"AV. PRINCIPAL 123","colonia":"CENTRO","cp":"68000","dias_atraso":95,"saldo":12500,"requerido":12500}]}
Conserva cada cliente visible. No inventes datos. Si un campo no se puede leer, usa null.
"""
