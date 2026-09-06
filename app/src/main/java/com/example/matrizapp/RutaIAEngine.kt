package com.example.matrizapp

enum class EstrategiaRutaIA(val etiqueta: String) {
    INTELIGENTE("Ruta inteligente"),
    MAYOR_ATRASO("Mayor atraso"),
    MAYOR_REQUERIDO("Mayor requerido / saldo"),
    PRIORIDAD_COBRANZA("Prioridad de cobranza")
}

fun construirRutaIAInteligente(items: List<RutaIAEntity>, inicio: Pair<Double, Double>?, estrategia: EstrategiaRutaIA): List<RutaIAEntity> {
    val pendientes = items.filter { it.lat != null && it.lng != null }.toMutableList()
    val sinUbicar = items.filter { it.lat == null || it.lng == null }
    if (pendientes.isEmpty()) return sinUbicar
    fun d(p: Pair<Double, Double>, x: RutaIAEntity) = distanciaKm(p, x.lat!! to x.lng!!)
    val resultado = mutableListOf<RutaIAEntity>()
    var punto = inicio
    while (pendientes.isNotEmpty()) {
        val siguiente = when {
            punto == null -> pendientes.first()
            estrategia == EstrategiaRutaIA.INTELIGENTE -> {
                val min = pendientes.minOf { d(punto!!, it) }
                val ventana = maxOf(0.25, min * 1.25)
                pendientes.filter { d(punto!!, it) <= ventana }
                    .maxWithOrNull(compareBy<RutaIAEntity> { it.diasAtraso ?: 0 }.thenBy { it.pagoRequerido ?: 0.0 })
                    ?: pendientes.minByOrNull { d(punto!!, it) }!!
            }
            estrategia == EstrategiaRutaIA.MAYOR_ATRASO -> pendientes.maxWithOrNull(compareBy<RutaIAEntity> { it.diasAtraso ?: 0 }.thenByDescending { -d(punto!!, it) })!!
            estrategia == EstrategiaRutaIA.MAYOR_REQUERIDO -> pendientes.maxWithOrNull(compareBy<RutaIAEntity> { it.pagoRequerido ?: 0.0 }.thenByDescending { -d(punto!!, it) })!!
            else -> pendientes.maxWithOrNull(compareBy<RutaIAEntity> { (it.diasAtraso ?: 0) * 100000.0 + (it.pagoRequerido ?: 0.0) }.thenByDescending { -d(punto!!, it) })!!
        }
        resultado += siguiente
        pendientes.remove(siguiente)
        punto = siguiente.lat!! to siguiente.lng!!
    }
    return resultado + sinUbicar
}
