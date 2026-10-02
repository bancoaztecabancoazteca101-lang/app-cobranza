package com.example.matrizapp

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Mapa semanal de visitas: puntos en las coordenadas de los clientes visitados, un color por día de la
// semana, para ver qué zonas se trabajaron cada día. Una "visita" = cambio de status/observaciones o pago
// (ver VisitaMapaDao.registrarVisitaHoy). Usa Calendar y no java.time: la app soporta Android 7 (minSdk 24).

private val HUES_DIA = floatArrayOf(210f, 120f, 60f, 30f, 0f, 270f, 300f) // lun..dom (tonos de pin de Google Maps)
private fun colorDia(i: Int) = Color(android.graphics.Color.HSVToColor(floatArrayOf(HUES_DIA[i], 0.9f, 0.85f)))

private fun inicioSemanaMapa(offsetSemanas: Int): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = inicioDelDia()
    val atrasLunes = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // lunes=0 ... domingo=6
    c.add(Calendar.DAY_OF_YEAR, -atrasLunes + 7 * offsetSemanas)
    return c.timeInMillis
}

private fun sumarDiasMapa(base: Long, dias: Int): Long =
    Calendar.getInstance().apply { timeInMillis = base; add(Calendar.DAY_OF_YEAR, dias) }.timeInMillis

private fun latLngMapa(raw: String): LatLng? {
    val p = raw.split(",").map { it.trim() }
    if (p.size != 2) return null
    return LatLng(p[0].toDoubleOrNull() ?: return null, p[1].toDoubleOrNull() ?: return null)
}

private val zonaCafetales = listOf(
    LatLng(19.3772114, -99.1382665),
    LatLng(19.3771912, -99.1385026),
    LatLng(19.3772924, -99.1392107),
    LatLng(19.3773075, -99.1394413),
    LatLng(19.3772721, -99.1396076),
    LatLng(19.3772063, -99.1397149),
    LatLng(19.3771001, -99.1397739),
    LatLng(19.3710678, -99.1406161),
    LatLng(19.3657741, -99.1414798),
    LatLng(19.3632285, -99.141909),
    LatLng(19.3602273, -99.1424347),
    LatLng(19.3578738, -99.1429711),
    LatLng(19.3565478, -99.1433037),
    LatLng(19.3552977, -99.1436739),
    LatLng(19.3529088, -99.1443981),
    LatLng(19.3518965, -99.1446394),
    LatLng(19.3510563, -99.1447521),
    LatLng(19.3501706, -99.1447414),
    LatLng(19.3493962, -99.1446394),
    LatLng(19.3462277, -99.1438562),
    LatLng(19.3439702, -99.143293),
    LatLng(19.3411459, -99.1425956),
    LatLng(19.3387972, -99.141866),
    LatLng(19.3360487, -99.1410238),
    LatLng(19.3351679, -99.1407502),
    LatLng(19.333047, -99.1401226),
    LatLng(19.3329508, -99.1396398),
    LatLng(19.3328647, -99.1393555),
    LatLng(19.3325711, -99.138921),
    LatLng(19.3306122, -99.1361583),
    LatLng(19.3301313, -99.1298712),
    LatLng(19.3301262, -99.1287071),
    LatLng(19.3300806, -99.1277523),
    LatLng(19.3297617, -99.1238255),
    LatLng(19.3324395, -99.1232622),
    LatLng(19.3325256, -99.122522),
    LatLng(19.3328192, -99.1210038),
    LatLng(19.3330318, -99.1200597),
    LatLng(19.3330976, -99.1193623),
    LatLng(19.333295, -99.1187508),
    LatLng(19.3333102, -99.1182358),
    LatLng(19.3331988, -99.1176511),
    LatLng(19.3329407, -99.1166908),
    LatLng(19.3328749, -99.1164655),
    LatLng(19.3328799, -99.1162938),
    LatLng(19.3329812, -99.1161167),
    LatLng(19.3334772, -99.1158753),
    LatLng(19.3342517, -99.1154516),
    LatLng(19.3345605, -99.1152906),
    LatLng(19.3347528, -99.1150975),
    LatLng(19.3348439, -99.1148937),
    LatLng(19.3348541, -99.1146469),
    LatLng(19.3347427, -99.1139227),
    LatLng(19.335507, -99.1131234),
    LatLng(19.3356387, -99.1128337),
    LatLng(19.3356994, -99.1125226),
    LatLng(19.3360993, -99.1124797),
    LatLng(19.3362157, -99.1126084),
    LatLng(19.337552, -99.1133702),
    LatLng(19.3388731, -99.1136116),
    LatLng(19.3409434, -99.1139656),
    LatLng(19.3422442, -99.1145342),
    LatLng(19.3436767, -99.1151619),
    LatLng(19.3445321, -99.1158539),
    LatLng(19.3466225, -99.1169859),
    LatLng(19.3461568, -99.1153657),
    LatLng(19.3458785, -99.115414),
    LatLng(19.3458025, -99.1153711),
    LatLng(19.3454381, -99.1143465),
    LatLng(19.34477, -99.112764),
    LatLng(19.3441322, -99.1117072),
    LatLng(19.3452458, -99.1114551),
    LatLng(19.3477715, -99.1108757),
    LatLng(19.3500643, -99.1103393),
    LatLng(19.3512284, -99.1106289),
    LatLng(19.3527266, -99.1105002),
    LatLng(19.3546903, -99.1103071),
    LatLng(19.3551205, -99.1103929),
    LatLng(19.3557532, -99.1106504),
    LatLng(19.3563049, -99.1108596),
    LatLng(19.3565832, -99.1108274),
    LatLng(19.3584306, -99.1104251),
    LatLng(19.3615128, -99.1097063),
    LatLng(19.3634815, -99.1092449),
    LatLng(19.3659361, -99.1086924),
    LatLng(19.3678643, -99.1082525),
    LatLng(19.3706376, -99.107641),
    LatLng(19.3706781, -99.1067987),
    LatLng(19.3707084, -99.1062033),
    LatLng(19.3707439, -99.1055274),
    LatLng(19.3708046, -99.105125),
    LatLng(19.3709159, -99.1047603),
    LatLng(19.3716599, -99.1036284),
    LatLng(19.3736588, -99.105109),
    LatLng(19.3738157, -99.107641),
    LatLng(19.3739422, -99.1112888),
    LatLng(19.374423, -99.1138583),
    LatLng(19.3751973, -99.1177154),
    LatLng(19.3757742, -99.1207946),
    LatLng(19.3766952, -99.125746),
    LatLng(19.3772367, -99.1287179),
    LatLng(19.3774897, -99.1307778),
    LatLng(19.3777934, -99.1334278),
    LatLng(19.3776365, -99.1345383),
    LatLng(19.3772114, -99.1382665)
)

@Composable
fun MapaScreen(visitaDao: VisitaMapaDao, onOpenMatriz: (String) -> Unit) {
    var offset by remember { mutableStateOf(0) }
    val inicio = remember(offset) { inicioSemanaMapa(offset) }
    val dias = remember(inicio) { (0..6).map { sumarDiasMapa(inicio, it) } }
    val fin = remember(inicio) { sumarDiasMapa(inicio, 7) }
    val visitasFlow = remember(inicio) { visitaDao.getVisitasSemana(inicio, fin) }
    val visitas by visitasFlow.collectAsState(initial = emptyList())
    var diaSel by remember(inicio) { mutableStateOf<Int?>(null) } // null = toda la semana
    var seleccionada by remember(inicio, diaSel) { mutableStateOf<VisitaMapaEntity?>(null) }

    // Un cliente cuenta una vez por día; si dos registros caen en el mismo punto el mismo día, un solo punto.
    val puntos = remember(visitas) {
        visitas.mapNotNull { v -> latLngMapa(v.ubicacion)?.let { v to it } }
            .distinctBy { (v, ll) -> Triple(v.fechaDia, Math.round(ll.latitude * 1e5), Math.round(ll.longitude * 1e5)) }
    }
    val cuentaPorDia = remember(puntos, dias) { dias.map { d -> puntos.count { it.first.fechaDia == d } } }
    val visibles = remember(puntos, diaSel, dias) { if (diaSel == null) puntos else puntos.filter { it.first.fechaDia == dias[diaSel!!] } }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(19.354, -99.125), 12.5f)
    }
    var mapaAjustado by remember { mutableStateOf(false) }
    val fmtDia = remember { SimpleDateFormat("dd/MM", Locale("es", "MX")) }
    val fmtNombre = remember { SimpleDateFormat("EEE", Locale("es", "MX")) }
    val etiquetaSemana = if (offset == 0) "Esta semana" else "Semana del ${fmtDia.format(Date(inicio))} al ${fmtDia.format(Date(sumarDiasMapa(inicio, 6)))}"

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { offset-- }) { Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Semana anterior") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(etiquetaSemana, style = MaterialTheme.typography.titleMedium)
                if (offset == 0) Text("${fmtDia.format(Date(inicio))} al ${fmtDia.format(Date(sumarDiasMapa(inicio, 6)))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { if (offset < 0) offset++ }, enabled = offset < 0) { Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Semana siguiente") }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(selected = diaSel == null, onClick = { diaSel = null }, label = { Text("Todos · ${puntos.size}") })
            dias.forEachIndexed { i, d ->
                FilterChip(
                    selected = diaSel == i,
                    onClick = { diaSel = if (diaSel == i) null else i },
                    leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(colorDia(i))) },
                    label = { Text("${fmtNombre.format(Date(d)).replace(".", "").replaceFirstChar { it.uppercase() }} ${Calendar.getInstance().apply { timeInMillis = d }.get(Calendar.DAY_OF_MONTH)} · ${cuentaPorDia[i]}") }
                )
            }
        }
        if (puntos.isEmpty()) {
            Text(
                "Sin visitas esta semana. Una visita se registra al cambiar el status u observaciones de un cliente, o al registrar su pago.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        Box(Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isBuildingEnabled = true),
                uiSettings = MapUiSettings(zoomControlsEnabled = true),
                onMapClick = { seleccionada = null },
                onMapLoaded = {
                    if (!mapaAjustado) {
                        mapaAjustado = true
                        val b = LatLngBounds.builder()
                        zonaCafetales.forEach { b.include(it) }
                        cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(b.build(), 48))
                    }
                }
            ) {
                Polygon(points = zonaCafetales, fillColor = Color(0x223F7FC4), strokeColor = Color(0xFF3F7FC4), strokeWidth = 3f)
                visibles.forEach { (v, ll) ->
                    val diaIdx = dias.indexOf(v.fechaDia).coerceAtLeast(0)
                    key(v.clave, v.fechaDia) {
                        Marker(
                            state = rememberMarkerState(position = ll),
                            icon = BitmapDescriptorFactory.defaultMarker(HUES_DIA[diaIdx]),
                            onClick = { seleccionada = v; true }
                        )
                    }
                }
            }
            seleccionada?.let { v ->
                val diaIdx = dias.indexOf(v.fechaDia).coerceAtLeast(0)
                Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(colorDia(diaIdx)))
                            Spacer(Modifier.width(8.dp))
                            Text(v.nombre, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        }
                        Text(
                            "${fmtNombre.format(Date(v.fechaDia)).replace(".", "").replaceFirstChar { it.uppercase() }} ${fmtDia.format(Date(v.fechaDia))} · ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(v.timestamp))}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { seleccionada = null }) { Text("Cerrar") }
                            if (v.matrizId != null) Button(onClick = { onOpenMatriz(v.matrizId) }) { Text("Abrir en Matriz") }
                        }
                    }
                }
            }
        }
    }
}
