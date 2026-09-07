package com.example.matrizapp

enum class EstrategiaRutaIA(val etiqueta: String) {
    INTELIGENTE("Ruta inteligente"),
    MAYOR_ATRASO("Mayor atraso"),
    MAYOR_REQUERIDO("Mayor requerido / saldo"),
    PRIORIDAD_COBRANZA("Prioridad de cobranza")
}

enum class ModoRutaIA(val etiqueta: String) {
    AUTOMATICA("Ruta automática"),
    MANUAL("Ruta manual")
}

data class FiltrosRutaIA(
    val usarDiasAtraso: Boolean = false,
    val minimoDiasAtraso: Int? = null,
    val direccionDiasAtraso: DireccionOrdenRutaIA = DireccionOrdenRutaIA.DESC,
    val usarSaldoAtraso: Boolean = false,
    val minimoSaldoAtraso: Double? = null,
    val direccionSaldoAtraso: DireccionOrdenRutaIA = DireccionOrdenRutaIA.DESC,
    val excluirNoVisitables: Boolean = true,
    val modoRuta: ModoRutaIA = ModoRutaIA.AUTOMATICA,
    val usarGpsInicio: Boolean = true,
    val usarCercaniaEncadenada: Boolean = true,
    val direccionCercania: DireccionOrdenRutaIA = DireccionOrdenRutaIA.ASC
)

private const val PREFIJO_CONFIG = "CONFIG2"

fun serializarConfiguracionRutaIA(config: FiltrosRutaIA): String = listOf(
    PREFIJO_CONFIG,
    config.usarDiasAtraso,
    config.minimoDiasAtraso ?: "",
    config.direccionDiasAtraso.name,
    config.usarSaldoAtraso,
    config.minimoSaldoAtraso ?: "",
    config.direccionSaldoAtraso.name,
    config.excluirNoVisitables,
    config.modoRuta.name,
    config.usarGpsInicio,
    config.usarCercaniaEncadenada,
    config.direccionCercania.name
).joinToString("|")

fun parsearConfiguracionRutaIA(texto: String?): FiltrosRutaIA {
    if (texto.isNullOrBlank() || !texto.startsWith("$PREFIJO_CONFIG|")) {
        val criterios = parsearCriteriosRutaIA(texto)
        val distancia = criterios.firstOrNull { it.campo == CampoOrdenRutaIA.DISTANCIA }
        val dias = criterios.firstOrNull { it.campo == CampoOrdenRutaIA.DIAS_ATRASO }
        val requerido = criterios.firstOrNull { it.campo == CampoOrdenRutaIA.PAGO_REQUERIDO }
        val manual = criterios.any { it.campo == CampoOrdenRutaIA.PERSONALIZADO }
        return FiltrosRutaIA(
            usarDiasAtraso = dias != null,
            direccionDiasAtraso = dias?.direccion ?: DireccionOrdenRutaIA.DESC,
            usarSaldoAtraso = requerido != null,
            direccionSaldoAtraso = requerido?.direccion ?: DireccionOrdenRutaIA.DESC,
            modoRuta = if (manual) ModoRutaIA.MANUAL else ModoRutaIA.AUTOMATICA,
            usarGpsInicio = distancia != null,
            usarCercaniaEncadenada = distancia != null,
            direccionCercania = distancia?.direccion ?: DireccionOrdenRutaIA.ASC
        )
    }
    val p = texto.split("|")
    fun bool(i: Int, def: Boolean) = p.getOrNull(i)?.toBooleanStrictOrNull() ?: def
    fun dir(i: Int, def: DireccionOrdenRutaIA) = p.getOrNull(i)?.let { DireccionOrdenRutaIA.values().find { d -> d.name == it } } ?: def
    return FiltrosRutaIA(
        usarDiasAtraso = bool(1, false),
        minimoDiasAtraso = p.getOrNull(2)?.toIntOrNull(),
        direccionDiasAtraso = dir(3, DireccionOrdenRutaIA.DESC),
        usarSaldoAtraso = bool(4, false),
        minimoSaldoAtraso = p.getOrNull(5)?.toDoubleOrNull(),
        direccionSaldoAtraso = dir(6, DireccionOrdenRutaIA.DESC),
        excluirNoVisitables = bool(7, true),
        modoRuta = p.getOrNull(8)?.let { ModoRutaIA.values().find { m -> m.name == it } } ?: ModoRutaIA.AUTOMATICA,
        usarGpsInicio = bool(9, true),
        usarCercaniaEncadenada = bool(10, true),
        direccionCercania = dir(11, DireccionOrdenRutaIA.ASC)
    )
}

fun aplicarFiltrosRutaIA(items: List<RutaIAEntity>, filtros: FiltrosRutaIA = FiltrosRutaIA()): List<RutaIAEntity> = items.filter { item ->
    val cumpleDias = !filtros.usarDiasAtraso || filtros.minimoDiasAtraso == null || (item.diasAtraso != null && item.diasAtraso >= filtros.minimoDiasAtraso)
    val cumpleSaldo = !filtros.usarSaldoAtraso || filtros.minimoSaldoAtraso == null || (item.saldoAtraso != null && item.saldoAtraso >= filtros.minimoSaldoAtraso)
    val cumpleVisitabilidad = !filtros.excluirNoVisitables || (item.direccion.isNotBlank() && item.lat != null && item.lng != null)
    cumpleDias && cumpleSaldo && cumpleVisitabilidad
}

private fun distanciaRutaIA(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val radio = 6371.0
    val dLat = Math.toRadians(b.first - a.first)
    val dLon = Math.toRadians(b.second - a.second)
    val lat1 = Math.toRadians(a.first)
    val lat2 = Math.toRadians(b.first)
    val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) + kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2) * kotlin.math.cos(lat1) * kotlin.math.cos(lat2)
    return 2 * radio * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
}

private fun compararPrioridades(a: RutaIAEntity, b: RutaIAEntity, filtros: FiltrosRutaIA): Int {
    if (filtros.usarDiasAtraso) {
        val av = a.diasAtraso ?: Int.MIN_VALUE
        val bv = b.diasAtraso ?: Int.MIN_VALUE
        if (av != bv) return if (filtros.direccionDiasAtraso == DireccionOrdenRutaIA.DESC) bv.compareTo(av) else av.compareTo(bv)
    }
    if (filtros.usarSaldoAtraso) {
        val av = a.saldoAtraso ?: Double.NEGATIVE_INFINITY
        val bv = b.saldoAtraso ?: Double.NEGATIVE_INFINITY
        if (av != bv) return if (filtros.direccionSaldoAtraso == DireccionOrdenRutaIA.DESC) bv.compareTo(av) else av.compareTo(bv)
    }
    return 0
}

fun construirRutaIAConfigurada(items: List<RutaIAEntity>, inicio: Pair<Double, Double>?, filtros: FiltrosRutaIA): List<RutaIAEntity> {
    val filtrados = aplicarFiltrosRutaIA(items, filtros)
    if (filtros.modoRuta == ModoRutaIA.MANUAL) return filtrados.sortedBy { it.orden }

    val pendientes = filtrados.filter { it.lat != null && it.lng != null }.toMutableList()
    val sinUbicar = filtrados.filter { it.lat == null || it.lng == null }
    if (pendientes.isEmpty()) return sinUbicar.sortedBy { it.orden }

    if (!filtros.usarCercaniaEncadenada) {
        val base = if (filtros.usarGpsInicio && inicio != null) {
            pendientes.sortedWith(Comparator { a, b ->
                val da = distanciaRutaIA(inicio, a.lat!! to a.lng!!)
                val db = distanciaRutaIA(inicio, b.lat!! to b.lng!!)
                if (da != db) da.compareTo(db) else compararPrioridades(a, b, filtros)
            })
        } else {
            pendientes.sortedWith(Comparator { a, b -> compararPrioridades(a, b, filtros) })
        }
        return base + sinUbicar
    }

    val resultado = mutableListOf<RutaIAEntity>()
    var puntoActual = if (filtros.usarGpsInicio) inicio else null
    while (pendientes.isNotEmpty()) {
        val siguiente = if (puntoActual != null) {
            val distanciaComparator = Comparator<RutaIAEntity> { a, b ->
                val da = distanciaRutaIA(puntoActual!!, a.lat!! to a.lng!!)
                val db = distanciaRutaIA(puntoActual!!, b.lat!! to b.lng!!)
                val cmp = if (filtros.direccionCercania == DireccionOrdenRutaIA.ASC) da.compareTo(db) else db.compareTo(da)
                if (cmp != 0) cmp else compararPrioridades(a, b, filtros)
            }
            pendientes.minWithOrNull(distanciaComparator)!!
        } else {
            pendientes.sortedWith(Comparator { a, b -> compararPrioridades(a, b, filtros) }).first()
        }
        resultado += siguiente
        pendientes.remove(siguiente)
        puntoActual = siguiente.lat!! to siguiente.lng!!
    }
    return resultado + sinUbicar
}

fun construirRutaIAInteligente(items: List<RutaIAEntity>, inicio: Pair<Double, Double>?, estrategia: EstrategiaRutaIA, filtros: FiltrosRutaIA = FiltrosRutaIA(), direccion: DireccionOrdenRutaIA = DireccionOrdenRutaIA.ASC): List<RutaIAEntity> {
    val config = filtros.copy(
        usarDiasAtraso = estrategia == EstrategiaRutaIA.MAYOR_ATRASO || estrategia == EstrategiaRutaIA.PRIORIDAD_COBRANZA,
        usarSaldoAtraso = estrategia == EstrategiaRutaIA.MAYOR_REQUERIDO,
        direccionDiasAtraso = if (estrategia == EstrategiaRutaIA.MAYOR_ATRASO || estrategia == EstrategiaRutaIA.PRIORIDAD_COBRANZA) DireccionOrdenRutaIA.DESC else filtros.direccionDiasAtraso,
        direccionSaldoAtraso = if (estrategia == EstrategiaRutaIA.MAYOR_REQUERIDO) DireccionOrdenRutaIA.DESC else filtros.direccionSaldoAtraso,
        usarCercaniaEncadenada = estrategia == EstrategiaRutaIA.INTELIGENTE,
        direccionCercania = direccion
    )
    return construirRutaIAConfigurada(items, inicio, config)
}
