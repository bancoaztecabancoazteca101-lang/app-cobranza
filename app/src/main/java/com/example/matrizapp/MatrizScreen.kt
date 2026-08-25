package com.example.matrizapp
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MatrizScreen(viewModel: MatrizViewModel, searchQuery: String = "") {
    val context = LocalContext.current
    val allItems by viewModel.matrizList.collectAsState()
    val deleteInProgress by viewModel.deleteInProgress.collectAsState()
    var itemToEdit by remember { mutableStateOf<MatrizEntity?>(null) }
    var itemToView by remember { mutableStateOf<MatrizEntity?>(null) }
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
                        onCardClick = { itemToView = item },
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

    // Al tocar un registro se abre primero la vista rápida (como en AppSheet): datos +
    // botones de llamar/SMS/Maps, con el botón de editar hasta abajo. Editar de verdad
    // (todos los campos) sigue viviendo en MatrizFullFormDialog, que se abre desde ahí.
    itemToView?.let { item ->
        MatrizDetailDialog(
            item = item,
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

/** Formatea el Req como precio: "199" -> "$199". Si no es numérico, regresa el valor tal cual. */
private fun formatearMontoMatriz(req: String): String {
    val limpio = req.replace("[^0-9.]".toRegex(), "")
    val numero = limpio.toDoubleOrNull() ?: return req
    val formateador = java.text.NumberFormat.getNumberInstance(Locale("es", "MX")).apply {
        maximumFractionDigits = 0
    }
    return "$" + formateador.format(numero)
}

/**
 * Vista rápida de un registro de Matriz (igual que la ventana de acción rápida de AppSheet):
 * muestra toda la info de un vistazo con los botones de llamar/SMS/Maps arriba, y el botón
 * de "Editar" hasta abajo para pasar al formulario completo solo cuando de verdad hace falta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatrizDetailDialog(
    item: MatrizEntity,
    driveHelper: DriveHelper,
    onDismiss: () -> Unit,
    onEditClick: () -> Unit
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(item.nombre, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(item.nombre))
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
                    PortadaThumbnail(rawImageUrl = item.imagenUrl, driveHelper = driveHelper, size = 160.dp)
                }
                Text("ID: ${item.id}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                StatusBadge(estado = item.estado)
                Spacer(modifier = Modifier.height(2.dp))
                Text("Sem: ${item.semana}  ·  Req: ${formatearMontoMatriz(item.requisito)}")
                ContactFieldRow("Num TT", item.numTT)
                ContactFieldRow("Ref 1", item.ref1)
                ContactFieldRow("Ref 2", item.ref2)
                item.fecha?.let {
                    Text("Fecha: ${SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(it))}")
                }
                if (!item.hora.isNullOrBlank()) Text("Hora: ${item.hora}")
                if (!item.folioP.isNullOrBlank()) Text("CU: ${item.folioP}")
                if (!item.ruta.isNullOrBlank()) Text("Ruta: ${item.ruta}")
                ColoniaLabel(ubicacion = item.ubicacion, style = MaterialTheme.typography.bodyMedium)
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