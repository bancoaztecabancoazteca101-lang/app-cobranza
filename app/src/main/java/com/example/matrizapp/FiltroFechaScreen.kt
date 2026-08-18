package com.example.matrizapp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltroFechaScreen(viewModel: FiltroFechaViewModel, searchQuery: String = "") {
    val allItems by viewModel.filteredList.collectAsState()
    val df = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    var itemToView by remember { mutableStateOf<FiltroFechaEntity?>(null) }

    val items = remember(allItems, searchQuery) {
        if (searchQuery.isBlank()) allItems else allItems.filter { item ->
            val q = searchQuery.trim()
            item.nombre.contains(q, ignoreCase = true) ||
                item.numTT.contains(q, ignoreCase = true) ||
                item.observaciones?.contains(q, ignoreCase = true) == true ||
                item.estado.contains(q, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (items.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Sin registros", color = Color.Gray) }
        else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                FiltroItemCard(item, df, driveHelper = viewModel.driveHelper, onClick = { itemToView = item })
            }
        }
    }

    itemToView?.let { item ->
        FiltroFechaDetailDialog(item = item, df = df, driveHelper = viewModel.driveHelper, onDismiss = { itemToView = null })
    }
}

/** Miniatura ("portada") con la primera imagen del registro, igual que la vista de AppSheet.
 * Resuelve tanto links de Drive (webViewLink) como rutas relativas del pipeline de OCR,
 * descargándolas a caché local la primera vez que la tarjeta se muestra. */
@Composable
fun PortadaThumbnail(rawImageUrl: String?, driveHelper: DriveHelper, size: androidx.compose.ui.unit.Dp = 56.dp) {
    val context = LocalContext.current
    var uriResuelta by remember(rawImageUrl) { mutableStateOf<String?>(null) }
    var fallo by remember(rawImageUrl) { mutableStateOf(false) }

    LaunchedEffect(rawImageUrl) {
        if (!rawImageUrl.isNullOrBlank()) {
            val uri = resolverArchivoComoUri(context, driveHelper, rawImageUrl, "portada_${rawImageUrl.hashCode()}.jpg")
            if (uri != null) uriResuelta = uri.toString() else fallo = true
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFE0E0E0)),
        contentAlignment = Alignment.Center
    ) {
        when {
            uriResuelta != null -> AsyncImage(model = uriResuelta, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            rawImageUrl.isNullOrBlank() || fallo -> Icon(Icons.Default.Image, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(24.dp))
            else -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltroItemCard(item: FiltroFechaEntity, df: SimpleDateFormat, driveHelper: DriveHelper, onClick: () -> Unit) {
    val esRetorno = item.estado.contains("retorno", ignoreCase = true)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (esRetorno) Color(0xFFBDBDBD) else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PortadaThumbnail(rawImageUrl = item.imagenUrl, driveHelper = driveHelper)
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.nombre, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    StatusBadge(item.estado)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("TT: ${item.numTT}", style = MaterialTheme.typography.bodySmall)
                    Text(df.format(Date(item.fecha)), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                ContactActionsRow(numTT = item.numTT)
            }
        }
    }
}

@Composable
fun FiltroFechaDetailDialog(item: FiltroFechaEntity, df: SimpleDateFormat, driveHelper: DriveHelper, onDismiss: () -> Unit) {
    // Filtro Fecha solo permite consultar el registro, no editarlo.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.nombre) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PortadaThumbnail(rawImageUrl = item.imagenUrl, driveHelper = driveHelper, size = 120.dp)
                }
                Text("Num TT: ${item.numTT}")
                Text("Status: ${item.estado}")
                Text("Fecha: ${df.format(Date(item.fecha))}")
                item.hora?.let { Text("Hora: $it") }
                Text("Observaciones: ${item.observaciones ?: "Sin observaciones"}")
                Spacer(modifier = Modifier.height(8.dp))
                ContactActionsRow(numTT = item.numTT)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyDatePickerDialog(onDateSelected: (Long) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState()
    DatePickerDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onDateSelected(it) } }) { Text("OK") } }) {
        DatePicker(state = state)
    }
}
