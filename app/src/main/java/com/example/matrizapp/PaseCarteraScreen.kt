package com.example.matrizapp
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Convierte una copia de Pase a MatrizEntity SOLO para reusar los mismos composables de
 * tarjeta/diálogo que Matriz (misma estructura de campos) -- nunca se guarda de vuelta como
 * MatrizEntity, ni se toca matriz_table desde aquí. */
private fun PaseEntity.comoMatrizParaUi(): MatrizEntity = MatrizEntity(
    id = id, nombre = nombre, semana = semana, requisito = requisito, numTT = numTT,
    ref1 = ref1, ref2 = ref2, observaciones = observaciones, estado = estado,
    ubicacion = ubicacion, imagenUrl = imagenUrl, imagenUrl2 = imagenUrl2, fecha = fecha, hora = hora,
    ruta = ruta, folioP = folioP, isDirty = isDirty, lastSync = lastSync
)

@Composable
fun PaseCarteraScreen(viewModel: PaseCarteraViewModel, searchQuery: String = "") {
    val context = LocalContext.current
    val allItems by viewModel.paseList.collectAsState()
    val deleteInProgress by viewModel.deleteInProgress.collectAsState()
    var itemToEdit by remember { mutableStateOf<PaseEntity?>(null) }
    var itemToView by remember { mutableStateOf<PaseEntity?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<PaseEntity?>(null) }
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
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Sin registros en Pase", color = androidx.compose.ui.graphics.Color.Gray) }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    MatrizItemCard(
                        item = item.comoMatrizParaUi(),
                        driveHelper = viewModel.driveHelper,
                        onCardClick = { itemToView = item },
                        onDeleteClick = { itemToDelete = item }
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { showCreateDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Default.Add, contentDescription = "Nuevo registro en Pase") }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    itemToView?.let { item ->
        MatrizDetailDialog(
            item = item.comoMatrizParaUi(),
            driveHelper = viewModel.driveHelper,
            onDismiss = { itemToView = null },
            onEditClick = {
                itemToEdit = item
                itemToView = null
            }
        )
    }

    itemToEdit?.let { item ->
        MatrizFullFormDialog(
            item = item.comoMatrizParaUi(),
            viewModel = null, // Pase no expone retomar-foto rápida desde el form -- edita el resto de campos igual
            onDismiss = { itemToEdit = null },
            onSave = { idEditado, nombre, semana, requisito, numTT, ref1, ref2, observaciones, estado, ubicacion, fecha, hora, ruta, folioP ->
                viewModel.cambiarIdYGuardar(
                    item.id, idEditado, nombre, semana, requisito, numTT, ref1, ref2,
                    observaciones, estado, ubicacion, fecha, hora, ruta, folioP
                ) { exito, error ->
                    if (!exito) Toast.makeText(context, error ?: "No se pudo guardar", Toast.LENGTH_LONG).show()
                }
                itemToEdit = null
            }
        )
    }

    if (showCreateDialog) {
        MatrizFullFormDialog(
            item = null,
            viewModel = null,
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
            title = { Text("Eliminar de Pase") },
            text = { Text("¿Seguro que quieres eliminar a \"${item.nombre}\" de Pase? Esto NO afecta su registro en Matriz -- solo borra esta copia local.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.eliminarRegistro(item.id) { exito, error ->
                            itemToDelete = null
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (exito) "Registro eliminado de Pase" else "No se pudo eliminar: ${error ?: "error"}"
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
