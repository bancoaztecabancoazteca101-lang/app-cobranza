package com.example.matrizapp

/**
 * Lógica PURA (sin Android ni Firebase) para fusionar un registro de Matriz entre el teléfono y la nube.
 *
 * Fusión de 3 vías por campo: base (lo último que este teléfono recibió de la nube), local (lo que el
 * usuario tiene ahora) y remoto (lo que hay hoy en la nube).
 *  - Si el usuario cambió un campo (local != base), gana el valor local.
 *  - Si no lo cambió, se conserva el valor remoto (así no se pisan cambios de otros teléfonos).
 *  - Si ambos cambiaron el MISMO campo a valores distintos, gana el local y se informa como conflicto.
 */
object MatrizNubeMerge {
    val CAMPOS = listOf(
        "nombre", "semana", "requisito", "numTT", "ref1", "ref2", "observaciones", "estado", "ubicacion",
        "imagenUrl", "imagenUrl2", "fecha", "hora", "ruta", "folioP", "descuentoPago", "descuentoAhorro",
        "ref3", "ref4", "diaPago", "domicilioLaboral", "diasAtraso", "diasApertura", "montoCobrado"
    )

    data class Resultado(val campos: Map<String, Any?>, val conflictos: List<String>)

    private fun norm(v: Any?): Any? = if (v is String && v.isEmpty()) null else v

    fun iguales(a: Any?, b: Any?): Boolean {
        val x = norm(a); val y = norm(b)
        return when {
            x == null && y == null -> true
            x == null || y == null -> false
            x is Number && y is Number -> x.toDouble() == y.toDouble()
            else -> x == y
        }
    }

    private fun vacio(v: Any?) = norm(v) == null || (v is String && v.isBlank())

    fun fusionar(base: Map<String, Any?>?, local: Map<String, Any?>, remoto: Map<String, Any?>?): Resultado {
        if (remoto == null) return Resultado(CAMPOS.associateWith { local[it] }, emptyList())
        val out = LinkedHashMap<String, Any?>()
        val conflictos = mutableListOf<String>()
        for (k in CAMPOS) {
            val l = local[k]; val r = remoto[k]
            if (base == null) {
                // Registro nuevo en este teléfono pero ya existente en la nube: lo no vacío local gana.
                out[k] = if (vacio(l)) r else l
                if (!vacio(l) && !vacio(r) && !iguales(l, r)) conflictos += k
            } else {
                val b = base[k]
                val localCambio = !iguales(l, b)
                out[k] = if (localCambio) l else r
                if (localCambio && !iguales(r, b) && !iguales(l, r)) conflictos += k
            }
        }
        return Resultado(out, conflictos)
    }

    /** Al vincular un teléfono a una nube que ya tiene datos: la nube manda; lo local solo rellena vacíos. */
    fun vincular(local: Map<String, Any?>, remoto: Map<String, Any?>): Map<String, Any?> =
        CAMPOS.associateWith { k -> if (vacio(remoto[k])) local[k] ?: remoto[k] else remoto[k] }

    fun hayCambios(remoto: Map<String, Any?>?, nuevo: Map<String, Any?>): Boolean =
        remoto == null || CAMPOS.any { !iguales(remoto[it], nuevo[it]) }
}
