package com.example.matrizapp
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FiltrarScreen(viewModel: FiltrarViewModel, searchQuery: String = "") {
    val allItems by viewModel.items.collectAsState()
    val items = remember(allItems, searchQuery) {
        if (searchQuery.isBlank()) allItems else allItems.filter { item ->
            val q = searchQuery.trim()
            coincideBusqueda(item.nombre, q) ||
                coincideBusqueda(item.numTT, q) ||
                coincideBusqueda(item.observaciones, q) ||
                coincideBusqueda(item.estado, q)
        }
    }
    var itemToView by remember { mutableStateOf<FiltrarEntity?>(null) }
    var itemToEdit by remember { mutableStateOf<FiltrarEntity?>(null) }

    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Sin registros", color = Color.Gray) }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                FiltrarItemCard(item, onCardClick = { itemToView = item }, onEditClick = { itemToEdit = item })
            }
        }
    }
    itemToView?.let { item ->
        FiltrarDetailDialog(
            item = item,
            driveHelper = viewModel.driveHelper,
            onDismiss = { itemToView = null },
            onEditClick = { itemToEdit = item; itemToView = null }
        )
    }
    itemToEdit?.let { item ->
        EditMatrizDialog(
            item = MatrizEntity(
                id = item.id, nombre = item.nombre, semana = item.semana, requisito = item.requerido,
                numTT = item.numTT, ref1 = "", ref2 = "", observaciones = item.observaciones,
                estado = item.estado, ubicacion = item.ubicacion, imagenUrl = item.imagen, imagenUrl2 = null,
                fecha = item.fecha, hora = item.hora, ruta = null, folioP = null
            ),
            onDismiss = { itemToEdit = null },
            onConfirm = { id, estado, obs -> viewModel.guardarGestion(id, estado, obs); itemToEdit = null }
        )
    }
}

/** Vista rápida de detalle, igual patrón que Matriz y Filtro Fecha: foto, datos con botón de
 * acción (teléfono/sms) pegado al lado, Colonia/Calle resueltas por GPS, Cercanos por GPS y
 * acceso directo a Editar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltrarDetailDialog(
    item: FiltrarEntity,
    driveHelper: DriveHelper,
    onDismiss: () -> Unit,
    onEditClick: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(item.nombre, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(item.nombre))
                    Toast.makeText(context, "Nombre copiado", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copiar nombre")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PortadaThumbnail(rawImageUrl = item.imagen, driveHelper = driveHelper, size = 160.dp)
                }
                Text("ID: ${item.id}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                StatusBadge(estado = item.estado)
                Spacer(modifier = Modifier.height(2.dp))
                Text("Sem: ${item.semana}  ·  Req: ${formatearMontoMatriz(item.requerido)}")
                ContactFieldRow("Num TT", item.numTT)
                item.fecha?.let {
                    Text("Fecha: ${SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(it))}")
                }
                if (!item.hora.isNullOrBlank()) Text("Hora: ${item.hora}")
                ColoniaLabel(ubicacion = item.ubicacion, style = MaterialTheme.typography.bodyMedium)
                CalleLabel(ubicacion = item.ubicacion, style = MaterialTheme.typography.bodyMedium)
                if (!item.referencias.isNullOrBlank()) {
                    Divider()
                    Text("Cercanos por GPS (10 m):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(item.referencias, style = MaterialTheme.typography.bodySmall)
                }
                if (!item.observaciones.isNullOrBlank()) {
                    Divider()
                    Text("Observaciones: ${item.observaciones}")
                }
                if (!item.ubicacion.isNullOrBlank() && item.ubicacion != "N/A") {
                    Spacer(modifier = Modifier.height(4.dp))
                    ContactActionsRow(numTT = null, ubicacion = item.ubicacion)
                }
            }
        },
        confirmButton = {
            Button(onClick = onEditClick) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Editar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltrarItemCard(item: FiltrarEntity, onCardClick: () -> Unit, onEditClick: () -> Unit) {
    Card(onClick = onCardClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.nombre, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                StatusBadge(item.estado)
            }
            Text("Sem ${item.semana} · Req ${item.requerido} · TT: ${item.numTT}", style = MaterialTheme.typography.bodySmall)
            item.ubicacion?.let { Text("Ubicación: $it", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
            item.referencias?.let { refs ->
                Spacer(Modifier.height(4.dp))
                Text("Cercanos por GPS:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text(refs, style = MaterialTheme.typography.bodySmall)
            }
            item.observaciones?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onEditClick) {
                    Icon(Icons.Default.Edit, contentDescription = "Editar")
                }
            }
        }
    }
}
