package com.example.matrizapp

enum class EstrategiaRutaIA(val etiqueta: String) {
    INTELIGENTE("Ruta inteligente"),
    MAYOR_ATRASO("Mayor atraso"),
    MAYOR_REQUERIDO("Mayor requerido / saldo"),
    PRIORIDAD_COBRANZA("Prioridad de cobranza")
}

/**
 * Construye el orden final de la ruta.
 *
 * Regla de distancia: el GPS solo determina la primera parada. A partir de ahí, cada siguiente
 * parada se calcula contra la parada inmediatamente anterior, nunca otra vez contra el GPS.
 * Esto evita el error de ordenar toda la cartera únicamente por distancia al punto inicial.
 *
 * INTELIGENTE usa exactamente esa regla de vecino más cercano. Las otras estrategias cambian
 * deliberadamente la prioridad principal y usan la cercanía solo para desempatar.
 */
fun construirRutaIAInteligente(
    items: List<RutaIAEntity>,
    inicio: Pair<Double, Double>?,
    estrategia: EstrategiaRutaIA
): List<RutaIAEntity> {
    val pendientes = items.filter { it.lat != null && it.lng != null }.toMutableList()
    val sinUbicar = items.filter { it.lat == null || it.lng == null }
    if (pendientes.isEmpty()) return sinUbicar

    fun distanciaDesde(punto: Pair<Double, Double>, item: RutaIAEntity): Double =
        distanciaKm(punto, item.lat!! to item.lng!!)

    val resultado = mutableListOf<RutaIAEntity>()
    var punto: Pair<Double, Double>? = inicio

    while (pendientes.isNotEmpty()) {
        val siguiente = when (estrategia) {
            EstrategiaRutaIA.INTELIGENTE -> {
                if (punto == null) pendientes.first()
                else pendientes.minWithOrNull(
                    compareBy<RutaIAEntity> { distanciaDesde(punto!!, it) }
                        .thenByDescending { it.diasAtraso ?: 0 }
                        .thenByDescending { it.pagoRequerido ?: 0.0 }
                )!!
            }
            EstrategiaRutaIA.MAYOR_ATRASO -> {
                pendientes.maxWithOrNull(
                    compareBy<RutaIAEntity> { it.diasAtraso ?: 0 }
                        .thenBy { -(if (punto == null) 0.0 else distanciaDesde(punto!!, it)) }
                )!!
            }
            EstrategiaRutaIA.MAYOR_REQUERIDO -> {
                pendientes.maxWithOrNull(
                    compareBy<RutaIAEntity> { it.pagoRequerido ?: 0.0 }
                        .thenBy { -(if (punto == null) 0.0 else distanciaDesde(punto!!, it)) }
                )!!
            }
            EstrategiaRutaIA.PRIORIDAD_COBRANZA -> {
                pendientes.maxWithOrNull(
                    compareBy<RutaIAEntity> {
                        (it.diasAtraso ?: 0) * 100000.0 + (it.pagoRequerido ?: 0.0)
                    }.thenBy { -(if (punto == null) 0.0 else distanciaDesde(punto!!, it)) }
                )!!
            }
        }

        resultado += siguiente
        pendientes.remove(siguiente)
        punto = siguiente.lat!! to siguiente.lng!!
    }

    return resultado + sinUbicar
}
