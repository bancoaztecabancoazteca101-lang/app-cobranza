package com.example.matrizapp
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

/** Dibuja el mismo estilo de "chichón" numerado que usa la navegación de Google Maps para
 * marcar paradas (círculo de color con borde blanco y el número al centro) -- referencia que
 * mandó Diego -- en vez del pin clásico en forma de gota. Naranja = pendiente, verde =
 * visitado, para que coincida con el color que ya usa la tarjeta de la lista. */
private fun crearIconoNumerado(context: Context, numero: Int, visitado: Boolean): BitmapDescriptor {
    val densidad = context.resources.displayMetrics.density
    val diametro = (40 * densidad).toInt()
    val bitmap = Bitmap.createBitmap(diametro, diametro, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val centro = diametro / 2f
    val colorRelleno = if (visitado) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#FF6B00")

    val paintBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }
    canvas.drawCircle(centro, centro, centro, paintBorde)
    val paintRelleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorRelleno; style = Paint.Style.FILL }
    canvas.drawCircle(centro, centro, centro - (3 * densidad), paintRelleno)

    val paintTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 16 * densidad
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }
    val yTexto = centro - (paintTexto.descent() + paintTexto.ascent()) / 2
    canvas.drawText("$numero", centro, yTexto, paintTexto)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/** Mapa a pantalla completa con una parada por cada cliente de la ruta que sí quedó
 * geocodificado -- se abre con el botón redondo "Ver mapa" del encabezado, igual que el botón
 * "Ver mapa" de la app de trabajo pero sin ocupar toda la fila. Tocar un marcador dispara la
 * misma acción que tocar la tarjeta en la lista (abre Matriz si hay match, avisa si es nuevo). */
@Composable
fun RutaIAMapaFullScreen(items: List<RutaIAEntity>, onCerrar: () -> Unit, onMarcadorClick: (RutaIAEntity) -> Unit) {
    val context = LocalContext.current
    val puntos = remember(items) { items.filter { it.lat != null && it.lng != null } }
    val cdmx = LatLng(19.36, -99.13)
    val cameraPositionState = rememberCameraPositionState {
        val primero = puntos.firstOrNull()
        position = CameraPosition.fromLatLngZoom(
            if (primero != null) LatLng(primero.lat!!, primero.lng!!) else cdmx, 14f
        )
    }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(modifier = Modifier.fillMaxSize(), cameraPositionState = cameraPositionState) {
            puntos.forEachIndexed { idx, item ->
                val posicion = items.indexOf(item) + 1
                val visitado = item.estado.equals("Visitado", ignoreCase = true)
                Marker(
                    state = MarkerState(position = LatLng(item.lat!!, item.lng!!)),
                    title = "$posicion. ${item.nombre}",
                    snippet = if (item.esNuevo) "Nuevo · sin registro en Matriz" else "Toca para abrir en Matriz",
                    icon = remember(posicion, visitado) { crearIconoNumerado(context, posicion, visitado) },
                    onClick = { onMarcadorClick(item); false }
                )
            }
        }
        FilledIconButton(
            onClick = onCerrar,
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
        ) { Icon(Icons.Default.ArrowBack, contentDescription = "Cerrar mapa") }
    }
}
