package com.example.matrizapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
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
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapProperties
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

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

private fun abrirEnGoogleMaps(context: Context, item: RutaIAEntity) {
    val lat = item.lat ?: return
    val lng = item.lng ?: return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng&mode=d")).apply {
        setPackage("com.google.android.apps.maps")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(item.nombre)})")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { context.startActivity(fallback) }
        catch (_: Exception) { Toast.makeText(context, "No se encontró una aplicación de mapas", Toast.LENGTH_SHORT).show() }
    }
}

/** Mapa a pantalla completa. Tocar una parada abre directamente Google Maps para navegar hacia ella. */
@Composable
fun RutaIAMapaFullScreen(items: List<RutaIAEntity>, onCerrar: () -> Unit, onMarcadorClick: (RutaIAEntity) -> Unit) {
    val context = LocalContext.current
    val puntos = remember(items) { items.filter { it.lat != null && it.lng != null } }
    val cdmx = LatLng(19.36, -99.13)
    val tienePermisoUbicacion = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val cameraPositionState = rememberCameraPositionState {
        val primero = puntos.firstOrNull()
        position = CameraPosition.fromLatLngZoom(
            if (primero != null) LatLng(primero.lat!!, primero.lng!!) else cdmx, 14f
        )
    }
    val mapProperties = remember(tienePermisoUbicacion) {
        MapProperties(isMyLocationEnabled = tienePermisoUbicacion)
    }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = mapProperties
        ) {
            puntos.forEach { item ->
                val posicion = items.indexOf(item) + 1
                val visitado = item.estado.equals("Visitado", ignoreCase = true)
                Marker(
                    state = MarkerState(position = LatLng(item.lat!!, item.lng!!)),
                    title = "$posicion. ${item.nombre}",
                    snippet = "Toca para abrir en Google Maps",
                    icon = remember(posicion, visitado) { crearIconoNumerado(context, posicion, visitado) },
                    onClick = {
                        onMarcadorClick(item)
                        abrirEnGoogleMaps(context, item)
                        true
                    }
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
