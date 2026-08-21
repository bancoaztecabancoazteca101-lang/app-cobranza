package com.example.matrizapp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaseCarteraScreen(viewModel: PaseCarteraViewModel, searchQuery: String = "") {
    val allItems by viewModel.paseList.collectAsState()
    val items = remember(allItems, searchQuery) {
        if (searchQuery.isBlank()) allItems else allItems.filter { item ->
            val q = searchQuery.trim()
            item.nombre.contains(q, ignoreCase = true) ||
                item.numTT.contains(q, ignoreCase = true) ||
                item.ref1.contains(q, ignoreCase = true) ||
                item.ref2.contains(q, ignoreCase = true) ||
                item.estado.contains(q, ignoreCase = true)
        }
    }
    var itemToEdit by remember { mutableStateOf<PaseEntity?>(null) }

    Column {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { item ->
                PaseItemCard(item, onCardClick = { itemToEdit = item })
            }
        }
    }

    itemToEdit?.let { item ->
        PaseEstadoDialog(item = item, onDismiss = { itemToEdit = null }, onSave = { nuevoEstado ->
            viewModel.guardarEstado(item.id, nuevoEstado)
            itemToEdit = null
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaseItemCard(item: PaseEntity, onCardClick: () -> Unit) {
    Card(onClick = onCardClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = item.nombre, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusBadge(item.estado)
            }
            Text(text = "Ref: ${item.ref1} / ${item.ref2}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            ColoniaLabel(ubicacion = item.ubicacion)

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ContactActionsRow(numTT = item.numTT, ref1 = item.ref1, ubicacion = item.ubicacion)
                Spacer(modifier = Modifier.weight(1f))
                if (item.isDirty) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color.Red, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaseEstadoDialog(item: PaseEntity, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var estado by remember { mutableStateOf(item.estado) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.nombre) },
        text = {
            Column {
                Text("TT: ${item.numTT}", style = MaterialTheme.typography.bodyMedium)
                Text("Ref: ${item.ref1} / ${item.ref2}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(12.dp))
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = estado, onValueChange = {}, readOnly = true, label = { Text("Status") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("(Sin status)") }, onClick = { estado = ""; expanded = false })
                        listOf("PENDIENTE", "GESTIONADO", "ENTREGADO", "VISITADO").forEach {
                            DropdownMenuItem(text = { Text(it) }, onClick = { estado = it; expanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(estado) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
