package com.example.matrizapp
import android.content.Context
import android.net.Uri
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.coroutines.suspendCancellableCoroutine

@Entity(tableName = "ruta_ia_table")
data class RutaIAEntity(
    @PrimaryKey val id: String,
    val nombre: String,
    val cu: String?,
    val direccion: String,
    val coloniaCp: String? = null,
    val diasAtraso: Int? = null,
    val pagoRequerido: Double? = null,
    val saldoAtraso: Double? = null,
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
    if (lista.isEmpty()) "DISTANCIA:ASC" else lista.joinToString(",") { "${it.campo.name}:${it.direccion.name}" }

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

fun construirRutaVecinoMasCercano(items: List<RutaIAEntity>, inicio: Pair<Double, Double>?): List<RutaIAEntity> {
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

fun ordenarRutaIA(items: List<RutaIAEntity>, criterios: List<CriterioOrdenRutaIA>, ubicacionActual: Pair<Double, Double>?): List<RutaIAEntity> {
    val principal = criterios.firstOrNull()
    if (principal?.campo == CampoOrdenRutaIA.PERSONALIZADO) return items.sortedBy { it.orden }
    if (principal?.campo == CampoOrdenRutaIA.DISTANCIA) {
        val ruta = construirRutaVecinoMasCercano(items, ubicacionActual)
        return if (principal.direccion == DireccionOrdenRutaIA.DESC) ruta.reversed() else ruta
    }
    var comparator: Comparator<RutaIAEntity>? = null
    for (c in criterios) {
        val base: Comparator<RutaIAEntity> = when (c.campo) {
            CampoOrdenRutaIA.DISTANCIA -> compareBy { item ->
                val ll = if (item.lat != null && item.lng != null) item.lat to item.lng else null
                if (ll != null && ubicacionActual != null) distanciaKm(ubicacionActual, ll) else Double.MAX_VALUE
            }
            CampoOrdenRutaIA.PERSONALIZADO -> compareBy { it.orden }
            CampoOrdenRutaIA.DIAS_ATRASO -> compareBy { it.diasAtraso ?: 0 }
            CampoOrdenRutaIA.PAGO_REQUERIDO -> compareBy { it.pagoRequerido ?: 0.0 }
        }
        val orientado = if (c.direccion == DireccionOrdenRutaIA.DESC) base.reversed() else base
        comparator = comparator?.then(orientado) ?: orientado
    }
    return items.sortedWith(comparator ?: compareBy { 0 })
}

data class ClienteRutaIAExtraido(val nombre: String, val cu: String?, val direccion: String, val diasAtraso: Int?, val pagoRequerido: Double?)

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
            while (j < lineas.size && REGEX_CU_RUTA.find(lineas[j]) == null && !lineas[j].contains("Ver mapa", ignoreCase = true)) {
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
            if (nombre.isNotBlank() && nombre.replace(" ", "").any { it.isLetter() }) resultados.add(ClienteRutaIAExtraido(nombre, cu, direccion, dias, pago))
            i = j
        } else i++
    }
    return resultados
}

suspend fun extraerClientesDeFoto(context: Context, uri: Uri): List<ClienteRutaIAExtraido> = suspendCancellableCoroutine { cont ->
    try {
        val image = com.google.mlkit.vision.common.InputImage.fromFilePath(context, uri)
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val texto = java.text.Normalizer.normalize(visionText.text, java.text.Normalizer.Form.NFC)
                if (cont.isActive) cont.resume(parsearClientesRutaIA(texto)) {}
            }
            .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) {} }
    } catch (e: Exception) { if (cont.isActive) cont.resume(emptyList()) {} }
}

suspend fun geocodificarDireccion(context: Context, direccion: String): Pair<Double, Double>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    if (direccion.isBlank()) return@withContext null
    try {
        if (!android.location.Geocoder.isPresent()) return@withContext null
        val geocoder = android.location.Geocoder(context, java.util.Locale("es", "MX"))
        @Suppress("DEPRECATION")
        val resultados = geocoder.getFromLocationName(direccion, 1)
        val r = resultados?.firstOrNull() ?: return@withContext null
        r.latitude to r.longitude
    } catch (e: Exception) { null }
}
