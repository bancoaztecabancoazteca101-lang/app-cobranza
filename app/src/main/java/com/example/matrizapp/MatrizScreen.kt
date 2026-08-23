package com.example.matrizapp
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun MatrizScreen(viewModel: MatrizViewModel, searchQuery: String = "") {
    val context = LocalContext.current
    val allItems by viewModel.matrizList.collectAsState()
    val deleteInProgress by viewModel.deleteInProgress.collectAsState()
    var itemToEdit by remember { mutableStateOf<MatrizEntity?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<MatrizEntity?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val items = remember(allItems, searchQuery) {
        if (searchQuery.isBlank()) allItems else allItems.filter { item ->
            val q = searchQuery.trim()
            coincideBusqueda(item.nombre, q) ||
                coincideBusqueda(item.numTT, q) ||
                coincideBusqueda(item.ref1, q) ||
                coincideBusqueda(item.ref2, q) ||
                coincideBusqueda(item.observaciones, q) ||
                coincideBusqueda(item.estado, q)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    MatrizItemCard(
                        item = item,
                        driveHelper = viewModel.driveHelper,
                        onCardClick = { itemToEdit = item },
                        onDeleteClick = { itemToDelete = item }
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { showCreateDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Default.Add, contentDescription = "Nuevo registro") }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    itemToEdit?.let { item ->
        MatrizFullFormDialog(
            item = item,
            viewModel = viewModel,
            onDismiss = { itemToEdit = null },
            onSave = { idEditado, nombre, semana, requisito, numTT, ref1, ref2, observaciones, estado, ubicacion, fecha, hora, ruta, folioP ->
                viewModel.cambiarIdYGuardar(
                    item.id, idEditado, nombre, semana, requisito, numTT, ref1, ref2,
                    observaciones, estado, ubicacion, fecha, hora, ruta, folioP
                ) { exito, error ->
                    if (!exito) {
                        Toast.makeText(context, error ?: "No se pudo guardar", Toast.LENGTH_LONG).show()
                    }
                }
                itemToEdit = null
            }
        )
    }

    if (showCreateDialog) {
        MatrizFullFormDialog(
            item = null,
            viewModel = viewModel,
            onDismiss = { showCreateDialog = false },
            onSave = { idEditado, nombre, semana, requisito, numTT, ref1, ref2, observaciones, estado, ubicacion, fecha, hora, ruta, folioP ->
                viewModel.crearRegistro(idEditado, nombre, semana, requisito, numTT, ref1, ref2, observaciones, estado, ubicacion, fecha, hora, ruta, folioP)
                showCreateDialog = false
            }
        )
    }

    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { if (!deleteInProgress) itemToDelete = null },
            title = { Text("Eliminar registro") },
            text = { Text("¿Seguro que quieres eliminar a \"${item.nombre}\"? Esta acción borra el registro tanto en la app como en la hoja de Google Sheets y no se puede deshacer.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.eliminarRegistro(item.id) { exito, error ->
                            itemToDelete = null
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (exito) "Registro eliminado" else "No se pudo eliminar: ${error ?: "sin conexión"}"
                                )
                            }
                        }
                    },
                    enabled = !deleteInProgress,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(if (deleteInProgress) "Eliminando…" else "Eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }, enabled = !deleteInProgress) { Text("Cancelar") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatrizItemCard(
    item: MatrizEntity,
    driveHelper: DriveHelper,
    onCardClick: () -> Unit,
    onDeleteClick: () -> Unit = {}
) {
    Card(onClick = onCardClick, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PortadaThumbnail(rawImageUrl = item.imagenUrl, driveHelper = driveHelper)
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = item.nombre, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    StatusBadge(estado = item.estado)
                    IconButton(onClick = onDeleteClick, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Eliminar registro", tint = Color.Gray)
                    }
                }
                if (!item.folioP.isNullOrBlank()) {
                    Text(text = "CU: ${item.folioP}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                ColoniaLabel(ubicacion = item.ubicacion)

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
}