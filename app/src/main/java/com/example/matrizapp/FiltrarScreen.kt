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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun FiltrarScreen(viewModel: FiltrarViewModel, searchQuery: String = "") {
    val allItems by viewModel.items.collectAsState()
    val items = remember(allItems, searchQuery) {
        if (searchQuery.isBlank()) allItems else allItems.filter { item ->
            val q = searchQuery.trim()
            coincideBusqueda(item.nombre, q) ||
                item.cercanos.any { coincideBusqueda(it.nombre, q) || coincideBusqueda(it.numTT, q) }
        }
    }
    var itemToView by remember { mutableStateOf<FiltrarItem?>(null) }
    var itemToEdit by remember { mutableStateOf<FiltrarItem?>(null) }

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
            item = item.original,
            onDismiss = { itemToEdit = null },
            onConfirm = { id, estado, obs -> viewModel.guardarGestion(id, estado, obs); itemToEdit = null }
        )
    }
}

/** Vista rápida: del titular (Status = Filtrar) solo se muestra nombre, foto y dirección — es
 * solo para identificarlo. Lo que de verdad importa aquí son los registros de Matriz encontrados
 * a 10 m o menos, con sus datos de contacto completos (Num TT, Ref1, Ref2, dirección) y sus
 * botones de acción, porque la idea de Filtrar es dar el contacto de alguien cercano al titular. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltrarDetailDialog(
    item: FiltrarItem,
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
                ColoniaLabel(ubicacion = item.ubicacion, style = MaterialTheme.typography.bodyMedium)
                CalleLabel(ubicacion = item.ubicacion, style = MaterialTheme.typography.bodyMedium)

                Divider(modifier = Modifier.padding(top = 4.dp))
                Text(
                    if (item.cercanos.isEmpty()) "Registros cercanos (10 m): ninguno"
                    else "Registros cercanos (10 m):",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold
                )
                item.cercanos.forEachIndexed { index, cercano ->
                    if (index > 0) Divider()
                    Text(cercano.nombre, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text("${cercano.distanciaM} m de distancia", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    ContactFieldRow("Num TT", cercano.numTT)
                    ContactFieldRow("Ref 1", cercano.ref1)
                    ContactFieldRow("Ref 2", cercano.ref2)
                    ColoniaLabel(ubicacion = cercano.ubicacion)
                    CalleLabel(ubicacion = cercano.ubicacion)
                    if (!cercano.ubicacion.isNullOrBlank() && cercano.ubicacion != "N/A") {
                        ContactActionsRow(numTT = null, ubicacion = cercano.ubicacion)
                    }
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
fun FiltrarItemCard(item: FiltrarItem, onCardClick: () -> Unit, onEditClick: () -> Unit) {
    Card(onClick = onCardClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.nombre, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                StatusBadge(item.estado)
            }
            ColoniaLabel(ubicacion = item.ubicacion)
            CalleLabel(ubicacion = item.ubicacion)
            if (item.cercanos.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Cercanos por GPS:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                item.cercanos.forEach { c ->
                    Text("${c.nombre} (${c.distanciaM} m)", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onEditClick) {
                    Icon(Icons.Default.Edit, contentDescription = "Editar")
                }
            }
        }
    }
}
