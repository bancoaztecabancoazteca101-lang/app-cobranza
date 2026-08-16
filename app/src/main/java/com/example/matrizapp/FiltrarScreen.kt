package com.example.matrizapp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun FiltrarScreen(viewModel: FiltrarViewModel, searchQuery: String = "") {
    val allItems by viewModel.items.collectAsState()
    val items = remember(allItems, searchQuery) {
        if (searchQuery.isBlank()) allItems else allItems.filter { item ->
            val q = searchQuery.trim()
            item.nombre.contains(q, ignoreCase = true) ||
                item.numTT.contains(q, ignoreCase = true) ||
                item.observaciones?.contains(q, ignoreCase = true) == true ||
                item.estado.contains(q, ignoreCase = true)
        }
    }
    var itemToEdit by remember { mutableStateOf<FiltrarEntity?>(null) }

    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Sin registros", color = Color.Gray) }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item -> FiltrarItemCard(item, onEditClick = { itemToEdit = item }) }
        }
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

@Composable
fun FiltrarItemCard(item: FiltrarEntity, onEditClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
