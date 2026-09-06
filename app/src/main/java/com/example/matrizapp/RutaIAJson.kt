package com.example.matrizapp

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Registro crudo ya validado para entrar al motor de Ruta IA. */
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
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        opt(key)?.toString()?.trim()?.ifBlank { null }?.let { return it }
    }
    return null
}

private fun JSONObject.doubleOrNull(vararg keys: String): Double? {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val raw = opt(key)?.toString()?.trim()
            ?.replace("$", "")
            ?.replace(" ", "") ?: continue
        val normalizado = when {
            raw.contains(".") && raw.contains(",") && raw.lastIndexOf(',') > raw.lastIndexOf('.') ->
                raw.replace(".", "").replace(",", ".")
            raw.contains(",") && raw.substringAfterLast(',').length == 2 ->
                raw.replace(".", "").replace(",", ".")
            else -> raw.replace(",", "")
        }
        normalizado.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it }
    }
    return null
}

private fun JSONObject.intOrNull(vararg keys: String): Int? {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val raw = opt(key)?.toString()?.trim()?.replace(",", "") ?: continue
        raw.toIntOrNull()?.let { return it }
        raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it.toInt() }
    }
    return null
}

private fun normalizarClaveDuplicado(valor: String?): String? = valor
    ?.uppercase(Locale.ROOT)
    ?.replace(Regex("[^A-Z0-9]"), "")
    ?.ifBlank { null }

private fun normalizarNombreDuplicado(valor: String): String = valor
    .uppercase(Locale.ROOT)
    .normalize(java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .replace(Regex("[^A-Z0-9]"), "")

fun leerClientesRutaIAJson(context: Context, uri: Uri): ResultadoImportacionRutaIA {
    val texto = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        ?: throw IllegalArgumentException("No se pudo leer el archivo seleccionado")

    val textoLimpio = texto.trim()
        .removePrefix("```json")
        .removePrefix("```JSON")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

    val arreglo = try {
        if (textoLimpio.startsWith("[")) {
            JSONArray(textoLimpio)
        } else {
            val raiz = JSONObject(textoLimpio)
            raiz.optJSONArray("clientes")
                ?: raiz.optJSONArray("data")
                ?: raiz.optJSONArray("clientes_ruta")
                ?: throw IllegalArgumentException("El JSON debe contener un arreglo 'clientes'")
        }
    } catch (e: org.json.JSONException) {
        throw IllegalArgumentException("El archivo no contiene JSON válido")
    }

    val clientes = mutableListOf<ClienteRutaIAJson>()
    val advertencias = mutableListOf<String>()
    val clavesCU = mutableSetOf<String>()
    val nombresSinCU = mutableSetOf<String>()

    for (i in 0 until arreglo.length()) {
        val o = arreglo.optJSONObject(i)
        if (o == null) {
            advertencias += "Registro ${i + 1}: objeto inválido; se omitió"
            continue
        }

        val nombre = o.stringOrNull("nombre", "cliente", "nombre_cliente", "name")
        val direccion = o.stringOrNull("direccion", "dirección", "domicilio", "address")
        val cu = o.stringOrNull("cu", "CU", "numero_cuenta", "numeroCuenta", "cuenta")
        val dias = o.intOrNull("dias_atraso", "diasAtraso", "días_atraso", "dias", "atraso")
        val saldo = o.doubleOrNull("saldo", "Saldo", "saldo_total", "saldoTotal")
        val requerido = o.doubleOrNull("requerido", "pago_requerido", "pagoRequerido", "Pago requerido")

        if (nombre == null) {
            advertencias += "Registro ${i + 1}: falta nombre; se omitió"
            continue
        }
        if (direccion == null) {
            advertencias += "$nombre: falta dirección; se omitió porque no es visitable"
            continue
        }

        if (dias != null && dias < 0) {
            advertencias += "$nombre: días de atraso inválidos ($dias); se omitió"
            continue
        }
        if ((saldo != null && saldo < 0.0) || (requerido != null && requerido < 0.0)) {
            advertencias += "$nombre: saldo/requerido negativo; se omitió"
            continue
        }

        if (cu == null) advertencias += "$nombre: CU no leída"
        if (dias == null) advertencias += "$nombre: días de atraso no leídos"
        if (saldo == null && requerido == null) advertencias += "$nombre: saldo/requerido no leído"

        if (saldo != null && requerido != null && kotlin.math.abs(saldo - requerido) > 0.01) {
            advertencias += "$nombre: saldo y requerido son diferentes (se conservan ambos conceptos en el JSON)"
        }

        val claveCU = normalizarClaveDuplicado(cu)
        if (claveCU != null && !clavesCU.add(claveCU)) {
            advertencias += "$nombre: CU duplicada; se omitió el registro repetido"
            continue
        }

        // Si Gemini no pudo leer CU, evitamos que dos apariciones del mismo cliente
        // entren como registros distintos por diferencias de acentos/espacios.
        if (claveCU == null) {
            val claveNombre = normalizarNombreDuplicado(nombre)
            if (!nombresSinCU.add(claveNombre)) {
                advertencias += "$nombre: nombre duplicado sin CU; se omitió el registro repetido"
                continue
            }
        }

        clientes += ClienteRutaIAJson(
            nombre = nombre,
            cu = cu,
            direccion = direccion,
            colonia = o.stringOrNull("colonia", "colony"),
            cp = o.stringOrNull("cp", "codigo_postal", "codigoPostal", "código_postal", "postal_code"),
            diasAtraso = dias,
            saldo = saldo,
            requerido = requerido
        )
    }

    if (clientes.isEmpty()) throw IllegalArgumentException("No hay clientes válidos/visitables en el JSON")
    return ResultadoImportacionRutaIA(clientes, advertencias)
}

const val RUTA_IA_JSON_INSTRUCCION_GEMINI = """
Devuelve únicamente JSON válido, sin Markdown ni texto adicional, con esta estructura:
{"clientes":[{"nombre":"JUAN PEREZ","cu":"12-34-56789-123","direccion":"AV. PRINCIPAL 123","colonia":"CENTRO","cp":"68000","dias_atraso":95,"saldo":12500,"requerido":12500}]}
Conserva cada cliente visible. No inventes datos. Si un campo no se puede leer, usa null.
No dupliques clientes: cada CU debe aparecer una sola vez.
"""
