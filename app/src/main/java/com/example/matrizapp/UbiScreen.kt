package com.example.matrizapp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

/** Punto GPS con su cliente asociado, parseado desde "lat, lng". */
private data class MatrizPunto(val item: MatrizEntity, val latLng: LatLng)

private fun parseLatLng(raw: String?): LatLng? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split(",").map { it.trim() }
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lng = parts[1].toDoubleOrNull() ?: return null
    return LatLng(lat, lng)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UbiScreen(matrizViewModel: MatrizViewModel) {
    val items by matrizViewModel.matrizList.collectAsState()
    val puntos = remember(items) {
        items.mapNotNull { item -> parseLatLng(item.ubicacion)?.let { MatrizPunto(item, it) } }
    }
    var selected by remember { mutableStateOf<MatrizEntity?>(null) }

    val cdmx = LatLng(19.36, -99.13)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(puntos.firstOrNull()?.latLng ?: cdmx, 13f)
    }

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState
    ) {
        puntos.forEach { punto ->
            Marker(
                state = MarkerState(position = punto.latLng),
                title = punto.item.nombre,
                snippet = "TT: ${punto.item.numTT} · ${punto.item.estado}",
                onClick = { selected = punto.item; false }
            )
        }
    }

    selected?.let { item ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            MatrizItemCard(
                item = item,
                driveHelper = matrizViewModel.driveHelper,
                onCardClick = { }
            )
        }
    }
}
