package com.example.matrizapp

enum class EstrategiaRutaIA(val etiqueta: String) {
    INTELIGENTE("Ruta inteligente"),
    MAYOR_ATRASO("Mayor atraso"),
    MAYOR_REQUERIDO("Mayor requerido / saldo"),
    PRIORIDAD_COBRANZA("Prioridad de cobranza")
}

/**
 * Filtros duros de negocio. Se aplican antes de ordenar la ruta.
 * null significa que ese filtro está desactivado.
 */
data class FiltrosRutaIA(
    val minimoDiasAtraso: Int? = null,
    val minimoRequerido: Double? = null,
    val exigirDireccion: Boolean = true
)

/**
 * Aplica únicamente filtros de entrada; no decide el orden de visita.
 */
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
 * Construye el orden final de la ruta.
 *
 * Regla geográfica: el GPS solo determina la primera parada. Después, cada parada usa como
 * referencia la parada inmediatamente anterior.
 *
 * Las estrategias de cobranza son prioridades lexicográficas, no fórmulas con pesos arbitrarios:
 * - INTELIGENTE: distancia -> atraso -> requerido.
 * - MAYOR_ATRASO: atraso -> distancia -> requerido.
 * - MAYOR_REQUERIDO: requerido -> distancia -> atraso.
 * - PRIORIDAD_COBRANZA: atraso -> requerido -> distancia.
 *
 * Los clientes sin coordenadas no rompen la ruta: se conservan al final en su orden relativo.
 */
fun construirRutaIAInteligente(
    items: List<RutaIAEntity>,
    inicio: Pair<Double, Double>?,
    estrategia: EstrategiaRutaIA,
    filtros: FiltrosRutaIA = FiltrosRutaIA()
): List<RutaIAEntity> {
    val filtrados = aplicarFiltrosRutaIA(items, filtros)
    val pendientes = filtrados.filter { it.lat != null && it.lng != null }.toMutableList()
    val sinUbicar = filtrados.filter { it.lat == null || it.lng == null }

    if (pendientes.isEmpty()) return sinUbicar

    fun distanciaDesde(punto: Pair<Double, Double>, item: RutaIAEntity): Double =
        distanciaKm(punto, item.lat!! to item.lng!!)

    val resultado = mutableListOf<RutaIAEntity>()
    var punto: Pair<Double, Double>? = inicio

    while (pendientes.isNotEmpty()) {
        val siguiente = when (estrategia) {
            EstrategiaRutaIA.INTELIGENTE -> {
                if (punto == null) {
                    pendientes.minWithOrNull(
                        compareBy<RutaIAEntity> { it.diasAtraso ?: 0 }
                            .thenByDescending { it.pagoRequerido ?: 0.0 }
                    )!!
                } else {
                    pendientes.minWithOrNull(
                        compareBy<RutaIAEntity> { distanciaDesde(punto!!, it) }
                            .thenByDescending { it.diasAtraso ?: 0 }
                            .thenByDescending { it.pagoRequerido ?: 0.0 }
                    )!!
                }
            }
            EstrategiaRutaIA.MAYOR_ATRASO -> {
                pendientes.maxWithOrNull(
                    compareBy<RutaIAEntity> { it.diasAtraso ?: 0 }
                        .thenBy { -(if (punto == null) 0.0 else distanciaDesde(punto!!, it)) }
                        .thenByDescending { it.pagoRequerido ?: 0.0 }
                )!!
            }
            EstrategiaRutaIA.MAYOR_REQUERIDO -> {
                pendientes.maxWithOrNull(
                    compareBy<RutaIAEntity> { it.pagoRequerido ?: 0.0 }
                        .thenBy { -(if (punto == null) 0.0 else distanciaDesde(punto!!, it)) }
                        .thenByDescending { it.diasAtraso ?: 0 }
                )!!
            }
            EstrategiaRutaIA.PRIORIDAD_COBRANZA -> {
                pendientes.maxWithOrNull(
                    compareBy<RutaIAEntity> { it.diasAtraso ?: 0 }
                        .thenBy { it.pagoRequerido ?: 0.0 }
                        .thenBy { -(if (punto == null) 0.0 else distanciaDesde(punto!!, it)) }
                )!!
            }
        }

        resultado += siguiente
        pendientes.remove(siguiente)
        punto = siguiente.lat!! to siguiente.lng!!
    }

    return resultado + sinUbicar
}
