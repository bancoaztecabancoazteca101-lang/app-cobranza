package com.example.matrizapp

enum class EstrategiaRutaIA(val etiqueta: String) {
    INTELIGENTE("Ruta inteligente"),
    MAYOR_ATRASO("Mayor atraso"),
    MAYOR_REQUERIDO("Mayor requerido / saldo"),
    PRIORIDAD_COBRANZA("Prioridad de cobranza")
}

data class FiltrosRutaIA(
    val minimoDiasAtraso: Int? = null,
    val minimoRequerido: Double? = null,
    val exigirDireccion: Boolean = true
)

fun aplicarFiltrosRutaIA(
    items: List<RutaIAEntity>,
    filtros: FiltrosRutaIA = FiltrosRutaIA()
): List<RutaIAEntity> = items.filter { item ->
    val cumpleDias = filtros.minimoDiasAtraso == null ||
        (item.diasAtraso != null && item.diasAtraso >= filtros.minimoDiasAtraso)
    val cumpleRequerido = filtros.minimoRequerido == null ||
        (item.pagoRequerido != null && item.pagoRequerido >= filtros.minimoRequerido)
    val cumpleDireccion = !filtros.exigirDireccion || item.direccion.isNotBlank()
    cumpleDias && cumpleRequerido && cumpleDireccion
}

/**
 * Construye el único orden de Ruta IA.
 *
 * INTELIGENTE + ASC:
 *   GPS determina la primera parada; después se busca siempre la siguiente más cercana
 *   desde la parada anterior.
 *
 * INTELIGENTE + DESC:
 *   se calcula exactamente la misma cadena ASC y después se invierte únicamente el bloque
 *   geocodificado. Los registros sin coordenadas permanecen siempre al final.
 *
 * Las estrategias de negocio priorizan su criterio principal y usan cercanía como desempate.
 * Si no hay GPS, se utiliza la prioridad de negocio para elegir la primera parada.
 */
fun construirRutaIAInteligente(
    items: List<RutaIAEntity>,
    inicio: Pair<Double, Double>?,
    estrategia: EstrategiaRutaIA,
    filtros: FiltrosRutaIA = FiltrosRutaIA(),
    direccion: DireccionOrdenRutaIA = DireccionOrdenRutaIA.ASC
): List<RutaIAEntity> {
    val filtrados = aplicarFiltrosRutaIA(items, filtros)
    val pendientes = filtrados.filter { it.lat != null && it.lng != null }.toMutableList()
    val sinUbicar = filtrados.filter { it.lat == null || it.lng == null }

    if (pendientes.isEmpty()) return sinUbicar

    fun distanciaDesde(punto: Pair<Double, Double>, item: RutaIAEntity): Double {
        val destino = item.lat!! to item.lng!!
        val radio = 6371.0
        val dLat = Math.toRadians(destino.first - punto.first)
        val dLon = Math.toRadians(destino.second - punto.second)
        val lat1 = Math.toRadians(punto.first)
        val lat2 = Math.toRadians(destino.first)
        val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2) *
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2)
        return 2 * radio * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun prioridadSinGps(): RutaIAEntity = when (estrategia) {
        EstrategiaRutaIA.MAYOR_REQUERIDO -> pendientes.maxWithOrNull(
            compareBy<RutaIAEntity> { it.pagoRequerido ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.diasAtraso ?: Int.MIN_VALUE }
        )!!
        EstrategiaRutaIA.INTELIGENTE,
        EstrategiaRutaIA.MAYOR_ATRASO,
        EstrategiaRutaIA.PRIORIDAD_COBRANZA -> pendientes.maxWithOrNull(
            compareBy<RutaIAEntity> { it.diasAtraso ?: Int.MIN_VALUE }
                .thenBy { it.pagoRequerido ?: Double.NEGATIVE_INFINITY }
        )!!
    }

    fun construirCadenaAsc(): MutableList<RutaIAEntity> {
        val restantes = pendientes.toMutableList()
        val cadena = mutableListOf<RutaIAEntity>()
        var puntoActual = inicio

        while (restantes.isNotEmpty()) {
            val siguiente = if (puntoActual != null) {
                when (estrategia) {
                    EstrategiaRutaIA.INTELIGENTE -> restantes.minWithOrNull(
                        compareBy<RutaIAEntity> { distanciaDesde(puntoActual!!, it) }
                            .thenByDescending { it.diasAtraso ?: Int.MIN_VALUE }
                            .thenByDescending { it.pagoRequerido ?: Double.NEGATIVE_INFINITY }
                    )!!
                    EstrategiaRutaIA.MAYOR_ATRASO -> restantes.maxWithOrNull(
                        compareBy<RutaIAEntity> { it.diasAtraso ?: Int.MIN_VALUE }
                            .thenBy { distanciaDesde(puntoActual!!, it) }
                            .thenByDescending { it.pagoRequerido ?: Double.NEGATIVE_INFINITY }
                    )!!
                    EstrategiaRutaIA.MAYOR_REQUERIDO -> restantes.maxWithOrNull(
                        compareBy<RutaIAEntity> { it.pagoRequerido ?: Double.NEGATIVE_INFINITY }
                            .thenBy { distanciaDesde(puntoActual!!, it) }
                            .thenByDescending { it.diasAtraso ?: Int.MIN_VALUE }
                    )!!
                    EstrategiaRutaIA.PRIORIDAD_COBRANZA -> restantes.maxWithOrNull(
                        compareBy<RutaIAEntity> { it.diasAtraso ?: Int.MIN_VALUE }
                            .thenBy { it.pagoRequerido ?: Double.NEGATIVE_INFINITY }
                            .thenBy { -distanciaDesde(puntoActual!!, it) }
                    )!!
                }
            } else {
                prioridadSinGps()
            }
            cadena += siguiente
            restantes.remove(siguiente)
            puntoActual = siguiente.lat!! to siguiente.lng!!
        }
        return cadena
    }

    val cadena = construirCadenaAsc()
    val orientada = if (estrategia == EstrategiaRutaIA.INTELIGENTE && direccion == DireccionOrdenRutaIA.DESC) {
        cadena.reversed()
    } else {
        cadena
    }

    return orientada + sinUbicar
}
