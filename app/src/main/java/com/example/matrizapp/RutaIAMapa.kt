package com.example.matrizapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
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

private fun crearIconoGps(context: Context): BitmapDescriptor {
    val densidad = context.resources.displayMetrics.density
    val diametro = (34 * densidad).toInt()
    val bitmap = Bitmap.createBitmap(diametro, diametro, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val centro = diametro / 2f
    val blanco = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }
    canvas.drawCircle(centro, centro, centro - densidad, blanco)
    val azul = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(25, 118, 210); style = Paint.Style.FILL }
    canvas.drawCircle(centro, centro, centro - (5 * densidad), azul)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun abrirEnGoogleMaps(context: Context, item: RutaIAEntity) {
    val lat = item.lat ?: return
    val lng = item.lng ?: return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng&mode=d")).apply {
        setPackage("com.google.android.apps.maps")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try { context.startActivity(intent) }
    catch (_: Exception) {
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(item.nombre)})")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try { context.startActivity(fallback) }
        catch (_: Exception) { Toast.makeText(context, "No se encontró una aplicación de mapas", Toast.LENGTH_SHORT).show() }
    }
}

@Composable
fun RutaIAMapaFullScreen(items: List<RutaIAEntity>, onCerrar: () -> Unit, onMarcadorClick: (RutaIAEntity) -> Unit) {
    val context = LocalContext.current
    val puntos = remember(items) { items.filter { it.lat != null && it.lng != null } }
    var ubicacionActual by remember { mutableStateOf<LatLng?>(null) }
    var encuadreInicialRealizado by remember { mutableStateOf(false) }

    val tienePermisoUbicacion = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    DisposableEffect(tienePermisoUbicacion) {
        if (!tienePermisoUbicacion) {
            onDispose { }
        } else {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    ubicacionActual = LatLng(location.latitude, location.longitude)
                }
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
                @Deprecated("Deprecated in API 29")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            try {
                val gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val red = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                val mejor = listOfNotNull(gps, red).maxByOrNull { it.time }
                if (mejor != null) ubicacionActual = LatLng(mejor.latitude, mejor.longitude)
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 5f, listener)
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 10f, listener)
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
            onDispose { try { locationManager.removeUpdates(listener) } catch (_: Exception) { } }
        }
    }

    val cdmx = LatLng(19.36, -99.13)
    val cameraPositionState = rememberCameraPositionState {
        val primero = puntos.firstOrNull()
        position = CameraPosition.fromLatLngZoom(if (primero != null) LatLng(primero.lat!!, primero.lng!!) else cdmx, 14f)
    }

    LaunchedEffect(ubicacionActual, puntos) {
        val gps = ubicacionActual ?: return@LaunchedEffect
        if (encuadreInicialRealizado) return@LaunchedEffect
        val builder = LatLngBounds.Builder()
        builder.include(gps)
        puntos.forEach { builder.include(LatLng(it.lat!!, it.lng!!)) }
        try {
            if (puntos.isNotEmpty()) cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(builder.build(), 120))
            else cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(gps, 16f))
            encuadreInicialRealizado = true
        } catch (_: Exception) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(gps, 15f)
            encuadreInicialRealizado = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = tienePermisoUbicacion),
            uiSettings = MapUiSettings(myLocationButtonEnabled = tienePermisoUbicacion, zoomControlsEnabled = false)
        ) {
            ubicacionActual?.let { gps ->
                Marker(
                    state = MarkerState(position = gps),
                    title = "Mi ubicación GPS",
                    snippet = "Ubicación actual del gestor",
                    icon = remember { crearIconoGps(context) }
                )
            }
            puntos.forEach { item ->
                val posicion = items.indexOf(item) + 1
                val visitado = item.estado.equals("Visitado", ignoreCase = true)
                Marker(
                    state = MarkerState(position = LatLng(item.lat!!, item.lng!!)),
                    title = "$posicion. ${item.nombre}",
                    snippet = "Toca para abrir en Google Maps",
                    icon = remember(posicion, visitado) { crearIconoNumerado(context, posicion, visitado) },
                    onClick = { onMarcadorClick(item); abrirEnGoogleMaps(context, item); true }
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
