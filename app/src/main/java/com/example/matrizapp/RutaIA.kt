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
    PAGO_REQUERIDO("Pago requerido")
}

enum class DireccionOrdenRutaIA(val etiqueta: String) {
    ASC("Menor a mayor / más cercano primero"),
    DESC("Mayor a menor / más lejano primero")
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

/** Orden compuesto: aplica los criterios en el orden de prioridad dado (el primero manda,
 * los siguientes solo desempatan). Los registros sin coordenada quedan al final cuando el
 * criterio activo es Distancia (no hay forma de saber qué tan lejos están). */
fun ordenarRutaIA(
    items: List<RutaIAEntity>,
    criterios: List<CriterioOrdenRutaIA>,
    ubicacionActual: Pair<Double, Double>?
): List<RutaIAEntity> {
    var comparator: Comparator<RutaIAEntity>? = null
    for (c in criterios) {
        val base: Comparator<RutaIAEntity> = when (c.campo) {
            CampoOrdenRutaIA.DISTANCIA -> compareBy { item ->
                val ll = if (item.lat != null && item.lng != null) item.lat to item.lng else null
                if (ll != null && ubicacionActual != null) distanciaKm(ubicacionActual, ll) else Double.MAX_VALUE
            }
            CampoOrdenRutaIA.DIAS_ATRASO -> compareBy { it.diasAtraso ?: 0 }
            CampoOrdenRutaIA.PAGO_REQUERIDO -> compareBy { it.pagoRequerido ?: 0.0 }
        }
        val orientado = if (c.direccion == DireccionOrdenRutaIA.DESC) base.reversed() else base
        comparator = comparator?.then(orientado) ?: orientado
    }
    return items.sortedWith(comparator ?: compareBy { 0 })
}

/** Un cliente tal como se extrajo de la foto, antes de geocodificar ni cruzar con Matriz. */
data class ClienteRutaIAExtraido(
    val nombre: String,
    val cu: String?,
    val direccion: String,
    val diasAtraso: Int?,
    val pagoRequerido: Double?
)

private val REGEX_CU_RUTA = Regex("""\d{2}-\d{2,}-\d{5}-\d+""")
private val REGEX_DIAS_RUTA = Regex("""D[ií]as?\s+atraso\D{0,10}(\d+)""", RegexOption.IGNORE_CASE)
private val REGEX_PAGO_RUTA = Regex("""Pago\s+requerido\D{0,10}\$?\s*([\d,]+)""", RegexOption.IGNORE_CASE)

/** Segmenta el texto crudo del OCR (una foto con 2-3 tarjetas de "Clientes de cobranza") en
 * clientes individuales. Cada tarjeta trae: Nombre, luego el CU (patrón NN-NN-NNNNN-NNNN...),
 * luego la dirección, luego "Dias atraso" y "Pago requerido". Se usa el CU como ancla porque
 * es el único campo con formato 100% fijo y reconocible por regex; el nombre es la línea
 * inmediatamente anterior al CU dentro del mismo bloque. */
fun parsearClientesRutaIA(textoOcr: String): List<ClienteRutaIAExtraido> {
    val lineas = textoOcr.lines().map { it.trim() }.filter { it.isNotBlank() }
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
                !lineas[j].contains("Ver mapa", ignoreCase = true)
            ) {
                bloque.add(lineas[j]); j++
            }
            val bloqueTexto = bloque.joinToString(" ")
            val dias = REGEX_DIAS_RUTA.find(bloqueTexto)?.groupValues?.get(1)?.toIntOrNull()
            val pago = REGEX_PAGO_RUTA.find(bloqueTexto)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            val idxDias = bloque.indexOfFirst { it.contains("Dias atraso", true) || it.contains("Días atraso", true) }
            val direccion = (if (idxDias > 0) bloque.subList(0, idxDias) else bloque).joinToString(" ").trim()
            if (nombre.isNotBlank() && nombre.replace(" ", "").any { it.isLetter() }) {
                resultados.add(ClienteRutaIAExtraido(nombre = nombre, cu = cu, direccion = direccion, diasAtraso = dias, pagoRequerido = pago))
            }
            i = j
        } else {
            i++
        }
    }
    return resultados
}

/** OCR local (ML Kit, on-device) de una foto completa de la lista "Clientes de cobranza",
 * devuelve todos los clientes detectados en esa foto (normalmente 2-3 por foto). */
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
    } catch (e: Exception) {
        if (cont.isActive) cont.resume(emptyList()) {}
    }
}

/** Geocodifica una dirección de texto (calle, colonia, CP) a lat/lng usando el Geocoder del
 * dispositivo (sin costo de Maps API). "Ciudad de México" se agrega si el texto no la trae,
 * para ayudar a desambiguar calles con nombre repetido en otras alcaldías/estados. */
suspend fun geocodificarDireccion(context: Context, direccion: String): Pair<Double, Double>? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (direccion.isBlank()) return@withContext null
        try {
            if (!android.location.Geocoder.isPresent()) return@withContext null
            val geocoder = android.location.Geocoder(context, java.util.Locale("es", "MX"))
            val query = if (direccion.contains("México", true) || direccion.contains("CDMX", true))
                direccion else "$direccion, Ciudad de México"
            @Suppress("DEPRECATION")
            val resultados = geocoder.getFromLocationName(query, 1)
            val r = resultados?.firstOrNull() ?: return@withContext null
            r.latitude to r.longitude
        } catch (e: Exception) {
            null
        }
    }
