package com.example.matrizapp

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Mapa semanal de visitas: puntos en las coordenadas de los clientes visitados, un color por día de la
// semana, para ver qué zonas se trabajaron cada día. Una "visita" = cambio de status/observaciones o pago
// (ver VisitaMapaDao.registrarVisitaHoy). Usa Calendar y no java.time: la app soporta Android 7 (minSdk 24).

private val HUES_DIA = floatArrayOf(210f, 120f, 60f, 30f, 0f, 270f, 300f) // lun..dom
private val HUES_CARTUCHO = floatArrayOf(285f, 120f, 30f, 200f, 60f, 330f)
private fun colorDia(i: Int) = Color(android.graphics.Color.HSVToColor(floatArrayOf(HUES_DIA[i], 0.9f, 0.85f)))
private fun hueCartucho(cartucho: Int) = HUES_CARTUCHO[(cartucho - 1).coerceAtLeast(0) % HUES_CARTUCHO.size]
private fun colorCartucho(cartucho: Int) = Color(android.graphics.Color.HSVToColor(floatArrayOf(hueCartucho(cartucho), 0.9f, 0.85f)))

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapaScreen(matrizDao: MatrizDao, visitaDao: VisitaMapaDao, onOpenMatriz: (String) -> Unit) {
    // Solo interesa la SEMANA ACTUAL (lunes a domingo).
    val inicio = remember { inicioSemanaMapa(0) }
    val dias = remember(inicio) { (0..6).map { sumarDiasMapa(inicio, it) } }
    val fin = remember(inicio) { sumarDiasMapa(inicio, 7) }
    val historial by remember(inicio) { visitaDao.getVisitasSemana(inicio, fin) }.collectAsState(initial = emptyList())
    val cartuchosDia by remember(inicio) { visitaDao.observarCartuchosSemana(inicio, fin) }.collectAsState(initial = emptyList())
    // Consulta liviana: solo los registros de esta semana con coordenadas (no la lista completa de Matriz).
    val registros by remember(inicio) { matrizDao.observarMatrizEnRango(inicio, fin) }.collectAsState(initial = emptyList())
    // Visitas de la semana = TODOS los registros de cada día tal como los muestra Filtro Fecha (fecha dentro
    // de ese día, status distinto de PASE) + el historial guardado. Si un registro ya está en el historial se
    // usa esa copia (conserva su cartucho); si no, sale con cartucho 1.
    val visitas = remember(registros, historial, inicio) {
        val delDia = registros.mapNotNull { m ->
            val f = m.fecha ?: return@mapNotNull null
            if (f < inicio || f >= fin || m.estado.equals("PASE", ignoreCase = true)) return@mapNotNull null
            val u = m.ubicacion ?: return@mapNotNull null
            VisitaMapaEntity("M:${m.id}", inicioDelDia(f), m.nombre, u, m.id, cartucho = 1, timestamp = f)
        }
        (historial + delDia).distinctBy { it.clave to it.fechaDia }
    }
    // Por defecto se ve el DÍA DE HOY; "Toda la semana" junta todos los días con su color.
    var diaSel by remember(inicio) { mutableStateOf<Int?>(dias.indexOf(inicioDelDia()).takeIf { it >= 0 }) }
    // Estado en un holder: solo la tarjeta lo LEE, así tocar un punto no recompone el mapa ni los demás puntos.
    val seleccionadaState = remember(inicio, diaSel) { mutableStateOf<VisitaMapaEntity?>(null) }

    // Un cliente cuenta una vez por día; si dos registros caen en el mismo punto el mismo día, un solo punto.
    val puntos = remember(visitas) {
        visitas.mapNotNull { v -> latLngMapa(v.ubicacion)?.let { v to it } }
            .distinctBy { (v, ll) -> Triple(v.fechaDia, Math.round(ll.latitude * 1e5), Math.round(ll.longitude * 1e5)) }
    }
    val cuentaPorDia = remember(puntos, dias) { dias.map { d -> puntos.count { it.first.fechaDia == d } } }
    val diaSeleccionado = diaSel?.let { dias[it] }
    val cartuchoActivo = diaSeleccionado?.let { d -> cartuchosDia.firstOrNull { it.fechaDia == d }?.cartuchoActivo } ?: 1
    val conteoCartuchos = remember(puntos, diaSeleccionado) {
        if (diaSeleccionado == null) emptyMap()
        else puntos.filter { it.first.fechaDia == diaSeleccionado }.groupingBy { it.first.cartucho }.eachCount()
    }
    val visitasActivo = conteoCartuchos[cartuchoActivo] ?: 0
    val scope = rememberCoroutineScope()
    var confirmarCambioCartucho by remember { mutableStateOf(false) }
    val visibles = remember(puntos, diaSel, dias) { if (diaSel == null) puntos else puntos.filter { it.first.fechaDia == dias[diaSel!!] } }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(19.354, -99.125), 12.5f)
    }
    var mapaAjustado by remember { mutableStateOf(false) }
    val fmtDia = remember { SimpleDateFormat("dd/MM", Locale("es", "MX")) }
    val fmtNombre = remember { SimpleDateFormat("EEE", Locale("es", "MX")) }
    val nombresDia = remember(dias) {
        dias.map { d ->
            "${fmtNombre.format(Date(d)).replace(".", "").replaceFirstChar { it.uppercase() }} ${Calendar.getInstance().apply { timeInMillis = d }.get(Calendar.DAY_OF_MONTH)}"
        }
    }
    val etiquetaSemana = "Esta semana: ${fmtDia.format(Date(inicio))} al ${fmtDia.format(Date(sumarDiasMapa(inicio, 6)))}"

    Column(Modifier.fillMaxSize()) {
        Text(etiquetaSemana, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(selected = diaSel == null, onClick = { diaSel = null }, label = { Text("Toda la semana · ${puntos.size}") })
            dias.forEachIndexed { i, d ->
                FilterChip(
                    selected = diaSel == i,
                    onClick = { diaSel = if (diaSel == i) null else i },
                    leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(colorDia(i))) },
                    label = { Text("${nombresDia[i]} · ${cuentaPorDia[i]}") }
                )
            }
        }
        if (diaSeleccionado != null) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                conteoCartuchos.toSortedMap().forEach { (cartucho, cantidad) ->
                    AssistChip(
                        onClick = {},
                        leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(colorCartucho(cartucho))) },
                        label = { Text("Cartucho $cartucho · $cantidad") }
                    )
                }
                val esHoy = diaSeleccionado == inicioDelDia()
                Button(onClick = { confirmarCambioCartucho = true }, enabled = esHoy) {
                    Text("Cambiar a ${cartuchoActivo + 1}")
                }
            }
            Text(
                when {
                    visitasActivo >= 25 -> "Cartucho $cartuchoActivo: $visitasActivo visitas. Llegó al máximo (25): cambia de cartucho."
                    visitasActivo >= 21 -> "Cartucho $cartuchoActivo: $visitasActivo visitas. Ya puedes cambiar de cartucho (se cambia entre 21 y 25)."
                    else -> "Cartucho activo: $cartuchoActivo · $visitasActivo visitas. El cambio es manual, entre 21 y 25 visitas."
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    visitasActivo >= 25 -> MaterialTheme.colorScheme.error
                    visitasActivo >= 21 -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        } else {
            Text(
                "Selecciona un día para ver sus cartuchos. El número de cartucho se reinicia cada día.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        if (puntos.isEmpty()) {
            Text("Sin visitas en este día.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        Box(Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                // Sin edificios 3D, interiores ni tráfico, y sin inclinar/rotar: el mapa dibuja bastante menos (equipos modestos).
                properties = MapProperties(isBuildingEnabled = false, isIndoorEnabled = false, isTrafficEnabled = false),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = true, mapToolbarEnabled = false, tiltGesturesEnabled = false,
                    rotationGesturesEnabled = false, indoorLevelPickerEnabled = false, myLocationButtonEnabled = false
                ),
                onMapClick = { seleccionadaState.value = null },
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
                // 7 íconos creados UNA vez (antes se creaba uno nuevo por punto en cada recomposición).
                val iconos = remember { HUES_DIA.map { BitmapDescriptorFactory.defaultMarker(it) } }
                // Íconos creados UNA vez (uno por cartucho), no uno nuevo por punto en cada recomposición.
                val iconosCartucho = remember { HUES_CARTUCHO.map { BitmapDescriptorFactory.defaultMarker(it) } }
                visibles.forEach { (v, ll) ->
                    val diaIdx = dias.indexOf(v.fechaDia).coerceAtLeast(0)
                    key(v.clave, v.fechaDia) {
                        Marker(
                            state = rememberMarkerState(position = ll),
                            icon = iconosCartucho[(v.cartucho - 1).coerceAtLeast(0) % iconosCartucho.size],
                            onClick = { seleccionadaState.value = v; true }
                        )
                    }
                }
            }
            TarjetaVisitaMapa(seleccionadaState, dias, onOpenMatriz, Modifier.align(Alignment.BottomCenter))
        }
    }

    if (confirmarCambioCartucho && diaSeleccionado != null) {
        AlertDialog(
            onDismissRequest = { confirmarCambioCartucho = false },
            title = { Text("Cambiar de cartucho") },
            text = {
                Text("Cartucho $cartuchoActivo lleva ${conteoCartuchos[cartuchoActivo] ?: 0} visitas. ¿Confirmas que ya cambiaste al cartucho ${cartuchoActivo + 1}? El nuevo cartucho empezará a contar desde la siguiente visita.")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        visitaDao.asegurarCartuchoDia(diaSeleccionado)
                        visitaDao.setCartuchoActivo(diaSeleccionado, cartuchoActivo + 1)
                    }
                    confirmarCambioCartucho = false
                }) { Text("Sí, cambiar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmarCambioCartucho = false }) { Text("Cancelar") }
            }
        )
    }
}

/** Tarjeta del punto tocado. Es la única que lee el estado, así que abrirla/cerrarla no recompone el mapa. */
@Composable
private fun TarjetaVisitaMapa(
    seleccionada: MutableState<VisitaMapaEntity?>,
    dias: List<Long>,
    onOpenMatriz: (String) -> Unit,
    modifier: Modifier
) {
    val v = seleccionada.value ?: return
    val fmtDia = remember { SimpleDateFormat("dd/MM", Locale("es", "MX")) }
    val fmtNombre = remember { SimpleDateFormat("EEE", Locale("es", "MX")) }
    val fmtHora = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val diaIdx = dias.indexOf(v.fechaDia).coerceAtLeast(0)
    Card(modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(colorDia(diaIdx)))
                Spacer(Modifier.width(8.dp))
                Text(v.nombre, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(
                "${fmtNombre.format(Date(v.fechaDia)).replace(".", "").replaceFirstChar { it.uppercase() }} ${fmtDia.format(Date(v.fechaDia))} · ${fmtHora.format(Date(v.timestamp))}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { seleccionada.value = null }) { Text("Cerrar") }
                if (v.matrizId != null) Button(onClick = { onOpenMatriz(v.matrizId) }) { Text("Abrir en Matriz") }
            }
        }
    }
}
