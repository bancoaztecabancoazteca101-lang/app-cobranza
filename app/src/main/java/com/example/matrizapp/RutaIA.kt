package com.example.matrizapp
import android.content.Context
import android.net.Uri
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.coroutines.suspendCancellableCoroutine

/** Un cliente extraído de una foto de la app de trabajo (Clientes de cobranza) y ubicado
 * en el mapa para armar la ruta del día. Tabla 100% independiente de Matriz: `cuMatrizMatch`
 * solo guarda la referencia si hubo cruce por CU, nunca sincroniza cambios de vuelta a Matriz.
 * `fechaDia` es la medianoche del día en que se generó, para poder limpiar por día si algún
 * día se necesita conservar más de uno. */
@Entity(tableName = "ruta_ia_table")
data class RutaIAEntity(
    @PrimaryKey val id: String,
    val nombre: String,
    val cu: String?,
    val direccion: String,
    val coloniaCp: String? = null,
    val diasAtraso: Int? = null,
    val pagoRequerido: Double? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val orden: Int = 0,
    val esNuevo: Boolean = true,
    val cuMatrizMatch: String? = null,
    val fechaDia: Long,
    var estado: String = "Pendiente",
    val fotoOrigenUrl: String? = null,
    val isDirty: Boolean = false,
    val lastSync: Long = System.currentTimeMillis()
)

/** Config de orden/filtro de Ruta IA -- una sola fila local (id fijo = 1), nunca se sube a
 * Sheets. `criteriosOrden` guarda la lista de criterios activos, en orden de prioridad,
 * serializada como "CAMPO:DIRECCION,CAMPO:DIRECCION,...". */
@Entity(tableName = "ruta_ia_filtro_table")
data class RutaIAFiltroEntity(
    @PrimaryKey val id: Int = 1,
    val criteriosOrden: String = "DISTANCIA:ASC"
)

enum class CampoOrdenRutaIA(val etiqueta: String) {
    DISTANCIA("Distancia/Cercanía"),
    DIAS_ATRASO("Días de atraso"),
    PAGO_REQUERIDO("Pago requerido"),
    PERSONALIZADO("Personalizado (orden manual)")
}

enum class DireccionOrdenRutaIA(val etiqueta: String) {
    ASC("➖ Menor a mayor / más cercano primero"),
    DESC("➕ Mayor a menor / más lejano primero")
}

data class CriterioOrdenRutaIA(val campo: CampoOrdenRutaIA, val direccion: DireccionOrdenRutaIA)

fun serializarCriteriosRutaIA(lista: List<CriterioOrdenRutaIA>): String =
    if (lista.isEmpty()) "DISTANCIA:ASC"
    else lista.joinToString(",") { "${it.campo.name}:${it.direccion.name}" }

fun parsearCriteriosRutaIA(texto: String?): List<CriterioOrdenRutaIA> {
    if (texto.isNullOrBlank()) return listOf(CriterioOrdenRutaIA(CampoOrdenRutaIA.DISTANCIA, DireccionOrdenRutaIA.ASC))
    val resultado = texto.split(",").mapNotNull { par ->
        val partes = par.split(":")
        if (partes.size != 2) return@mapNotNull null
        val campo = CampoOrdenRutaIA.values().find { it.name == partes[0] } ?: return@mapNotNull null
        val dir = DireccionOrdenRutaIA.values().find { it.name == partes[1] } ?: DireccionOrdenRutaIA.ASC
        CriterioOrdenRutaIA(campo, dir)
    }
    return resultado.ifEmpty { listOf(CriterioOrdenRutaIA(CampoOrdenRutaIA.DISTANCIA, DireccionOrdenRutaIA.ASC)) }
}

/** Arma la ruta real con vecino más cercano. */
fun construirRutaVecinoMasCercano(
    items: List<RutaIAEntity>,
    inicio: Pair<Double, Double>?
): List<RutaIAEntity> {
    val pendientes = items.filter { it.lat != null && it.lng != null }.toMutableList()
    val sinUbicar = items.filter { it.lat == null || it.lng == null }
    if (pendientes.isEmpty()) return sinUbicar

    val resultado = mutableListOf<RutaIAEntity>()
    var puntoActual = inicio ?: (pendientes.first().lat!! to pendientes.first().lng!!)
    while (pendientes.isNotEmpty()) {
        val siguiente = pendientes.minByOrNull { distanciaKm(puntoActual, it.lat!! to it.lng!!) }!!
        resultado.add(siguiente)
        pendientes.remove(siguiente)
        puntoActual = siguiente.lat!! to siguiente.lng!!
    }
    return resultado + sinUbicar
}

/** Filtros duros de negocio. */
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

/** Construye el orden final de la ruta aplicando primero todos los filtros activos. */
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

    fun prioridadSinGps(): RutaIAEntity = when (estrategia) {
        EstrategiaRutaIA.INTELIGENTE,
        EstrategiaRutaIA.MAYOR_ATRASO,
        EstrategiaRutaIA.PRIORIDAD_COBRANZA -> pendientes.maxWithOrNull(
            compareBy<RutaIAEntity> { it.diasAtraso ?: 0 }
                .thenBy { it.pagoRequerido ?: 0.0 }
        )!!
        EstrategiaRutaIA.MAYOR_REQUERIDO -> pendientes.maxWithOrNull(
            compareBy<RutaIAEntity> { it.pagoRequerido ?: 0.0 }
                .thenBy { it.diasAtraso ?: 0 }
        )!!
    }

    val resultado = mutableListOf<RutaIAEntity>()
    var punto: Pair<Double, Double>? = inicio
    while (pendientes.isNotEmpty()) {
        val siguiente = when {
            punto == null -> prioridadSinGps()
            estrategia == EstrategiaRutaIA.INTELIGENTE -> pendientes.minWithOrNull(
                compareBy<RutaIAEntity> { distanciaDesde(punto!!, it) }
                    .thenByDescending { it.diasAtraso ?: 0 }
                    .thenByDescending { it.pagoRequerido ?: 0.0 }
            )!!
            estrategia == EstrategiaRutaIA.MAYOR_ATRASO -> pendientes.maxWithOrNull(
                compareBy<RutaIAEntity> { it.diasAtraso ?: 0 }
                    .thenBy { -distanciaDesde(punto!!, it) }
                    .thenBy { it.pagoRequerido ?: 0.0 }
            )!!
            estrategia == EstrategiaRutaIA.MAYOR_REQUERIDO -> pendientes.maxWithOrNull(
                compareBy<RutaIAEntity> { it.pagoRequerido ?: 0.0 }
                    .thenBy { -distanciaDesde(punto!!, it) }
                    .thenBy { it.diasAtraso ?: 0 }
            )!!
            else -> pendientes.maxWithOrNull(
                compareBy<RutaIAEntity> { it.diasAtraso ?: 0 }
                    .thenBy { it.pagoRequerido ?: 0.0 }
                    .thenBy { -distanciaDesde(punto!!, it) }
            )!!
        }
        resultado += siguiente
        pendientes.remove(siguiente)
        punto = siguiente.lat!! to siguiente.lng!!
    }
    return resultado + sinUbicar
}

/** Compatibilidad con el flujo OCR antiguo; Ruta IA nueva usa JSON. */
data class ClienteRutaIAExtraido(
    val nombre: String,
    val cu: String?,
    val direccion: String,
    val diasAtraso: Int?,
    val pagoRequerido: Double?
)

private val REGEX_CU_RUTA = Regex("""\d{2}-\d{2,}-\d{5}-\d+""")
private val REGEX_DIAS_RUTA = Regex("""D[ií]as?\s+atraso\D{0,25}?(\d+)""", RegexOption.IGNORE_CASE)
private val REGEX_PAGO_RUTA = Regex("""Pago\s+requerido\D{0,25}?\$?\s*([\d,]+)""", RegexOption.IGNORE_CASE)
private val REGEX_RUIDO_RED = Regex("""\b\d{1,2}[Gg]\w{0,3}\b""")
private val REGEX_RUIDO_BATERIA = Regex("""\b\d{1,3}\s?%""")
private val REGEX_RUIDO_HORA = Regex("""\b\d{1,2}:\d{2}\b""")

private fun limpiarRuidoStatusBar(linea: String): String {
    var l = linea
    l = REGEX_RUIDO_RED.replace(l, " ")
    l = REGEX_RUIDO_BATERIA.replace(l, " ")
    l = REGEX_RUIDO_HORA.replace(l, " ")
    return l.replace(Regex("""\s{2,}"""), " ").trim()
}

private fun limpiarEtiquetasResiduales(texto: String): String {
    var t = texto
    t = Regex("""D[ií]as?\s+atraso""", RegexOption.IGNORE_CASE).replace(t, " ")
    t = Regex("""Pago\s+requerido""", RegexOption.IGNORE_CASE).replace(t, " ")
    return t.replace(Regex("""\s{2,}"""), " ").trim(' ', ',', '.', '-')
}

fun parsearClientesRutaIA(textoOcr: String): List<ClienteRutaIAExtraido> {
    val lineas = textoOcr.lines().map { limpiarRuidoStatusBar(it) }.filter { it.isNotBlank() }
    val resultados = mutableListOf<ClienteRutaIAExtraido>()
    var i = 0
    while (i < lineas.size) {
        val cuMatch = REGEX_CU_RUTA.find(lineas[i])
        if (cuMatch != null) {
            val cu = cuMatch.value
            val nombre = if (i > 0) lineas[i - 1] else ""
            val bloque = mutableListOf<String>()
            var j = i + 1
            while (j < lineas.size && REGEX_CU_RUTA.find(lineas[j]) == null &&
                !lineas[j].contains("Ver mapa", ignoreCase = true)) {
                bloque.add(lineas[j]); j++
            }
            val bloqueTexto = bloque.joinToString(" ")
            val dias = REGEX_DIAS_RUTA.find(bloqueTexto)?.groupValues?.get(1)?.toIntOrNull()
            val pago = REGEX_PAGO_RUTA.find(bloqueTexto)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            val idxDias = bloque.indexOfFirst { it.contains("Dias atraso", true) || it.contains("Días atraso", true) }
            val idxPago = bloque.indexOfFirst { it.contains("Pago requerido", true) }
            val idxCorte = listOf(idxDias, idxPago).filter { it >= 0 }.minOrNull() ?: -1
            val direccionCruda = (if (idxCorte > 0) bloque.subList(0, idxCorte) else bloque).joinToString(" ")
            val direccion = limpiarEtiquetasResiduales(direccionCruda)
            if (nombre.isNotBlank() && nombre.replace(" ", "").any { it.isLetter() }) {
                resultados.add(ClienteRutaIAExtraido(nombre, cu, direccion, dias, pago))
            }
            i = j
        } else i++
    }
    return resultados
}

suspend fun extraerClientesDeFoto(context: Context, uri: Uri): List<ClienteRutaIAExtraido> = suspendCancellableCoroutine { cont ->
    try {
        val image = com.google.mlkit.vision.common.InputImage.fromFilePath(context, uri)
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
        )
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val texto = java.text.Normalizer.normalize(visionText.text, java.text.Normalizer.Form.NFC)
                if (cont.isActive) cont.resume(parsearClientesRutaIA(texto)) {}
            }
            .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) {} }
    } catch (_: Exception) {
        if (cont.isActive) cont.resume(emptyList()) {}
    }
}

suspend fun geocodificarDireccion(context: Context, direccion: String): Pair<Double, Double>? = suspendCancellableCoroutine { cont ->
    try {
        val geocoder = android.location.Geocoder(context)
        @Suppress("DEPRECATION")
        geocoder.getFromLocationName(direccion, 1) { resultados ->
            if (cont.isActive) cont.resume(resultados.firstOrNull()?.let { it.latitude to it.longitude }) {}
        }
    } catch (_: Exception) {
        if (cont.isActive) cont.resume(null) {}
    }
}

fun distanciaKm(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val radio = 6371.0
    val dLat = Math.toRadians(b.first - a.first)
    val dLon = Math.toRadians(b.second - a.second)
    val lat1 = Math.toRadians(a.first)
    val lat2 = Math.toRadians(b.first)
    val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2) * kotlin.math.cos(lat1) * kotlin.math.cos(lat2)
    return 2 * radio * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
}
