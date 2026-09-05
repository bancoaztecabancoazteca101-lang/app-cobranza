package com.example.matrizapp

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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
    var itemGcr by remember { mutableStateOf<PaseEntity?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<PaseEntity?>(null) }
    var importando by remember { mutableStateOf(false) }
    var importResumen by remember { mutableStateOf<PaseCarteraViewModel.ImportResumen?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importando = true
        viewModel.importarFotos(context, uris.take(8)) { resumen, error ->
            importando = false
            if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            else importResumen = resumen
        }
    }

    LaunchedEffect(allItems.size) { viewModel.procesarPendientes() }

    val filtered = remember(allItems, searchQuery) {
        if (searchQuery.isBlank()) allItems else allItems.filter { item ->
            coincideBusqueda(item.nombre, searchQuery) || coincideBusqueda(item.numTT, searchQuery) ||
                coincideBusqueda(item.ref1, searchQuery) || coincideBusqueda(item.ref2, searchQuery) ||
                coincideBusqueda(item.observaciones, searchQuery) || coincideBusqueda(item.estado, searchQuery) ||
                coincideBusqueda(item.folioP, searchQuery) || coincideBusqueda(item.contiene, searchQuery) ||
                coincideBusqueda(item.capitales, searchQuery)
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (filtered.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Sin registros en Pase") }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.id }) { item ->
                Box {
                    MatrizItemCard(item.comoMatrizParaUi(), viewModel.driveHelper, { itemToView = item }, { itemToDelete = item })
                    IconButton(onClick = { itemGcr = item }, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Default.Edit, contentDescription = "Editar CONTIENE y CAPITALES")
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallFloatingActionButton(onClick = { if (!importando) picker.launch("image/*") }) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Importar Pase por foto")
            }
            FloatingActionButton(onClick = { showCreateDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Nuevo registro en Pase") }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }

    if (importando) AlertDialog(onDismissRequest = {}, title = { Text("Importando Pase") }, text = { Text("Leyendo fotos con OCR local…") }, confirmButton = {})

    importResumen?.let { resumen ->
        AlertDialog(
            onDismissRequest = { importResumen = null },
            title = { Text("Revisar importación") },
            text = {
                Text(
                    "Filtro 1 — FLORES: ${resumen.detectadosFlores} fila(s) detectada(s)\n\n" +
                        "Filtro 2 — Encontrados en Matriz: ${resumen.coincidenciasMatriz}\n" +
                        "Ya existentes en Pase: ${resumen.yaEnPase}\n" +
                        "Se agregarán a Pase: ${resumen.aAgregarPase}\n" +
                        "Sin coincidencia en Matriz: ${resumen.noEncontradosMatriz}\n\n" +
                        "Se copiarán a Pase únicamente registros que ya existan en Matriz. No se crearán clientes nuevos por OCR."
                )
            },
            confirmButton = { Button(onClick = {
                importResumen = null
                viewModel.aplicarImportacion(resumen) { mensaje -> scope.launch { snackbarHostState.showSnackbar(mensaje) } }
            }) { Text("Aplicar") } },
            dismissButton = { TextButton(onClick = { importResumen = null }) { Text("Cancelar") } }
        )
    }

    itemGcr?.let { item ->
        var contiene by remember(item.id) { mutableStateOf(item.contiene ?: "") }
        var capitales by remember(item.id) { mutableStateOf(item.capitales ?: "") }
        AlertDialog(
            onDismissRequest = { itemGcr = null },
            title = { Text("Importes de Pase — ${item.nombre}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = contiene, onValueChange = { contiene = it }, label = { Text("CONTIENE") }, prefix = { Text("$ ") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(value = capitales, onValueChange = { capitales = it }, label = { Text("CAPITALES") }, prefix = { Text("$ ") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Text("Estos importes son editables y se guardan en Pase aunque la foto no los detecte.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { Button(onClick = {
                viewModel.actualizarCamposGcr(item.id, contiene.ifBlank { null }, capitales.ifBlank { null }) { error ->
                    if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                }
                itemGcr = null
            }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { itemGcr = null }) { Text("Cancelar") } }
        )
    }

    itemToView?.let { item -> MatrizDetailDialog(item.comoMatrizParaUi(), viewModel.driveHelper, { itemToView = null }, { itemToEdit = item; itemToView = null }) }
    itemToEdit?.let { item ->
        MatrizFullFormDialog(item.comoMatrizParaUi(), null, { itemToEdit = null }, { idEditado, nombre, semana, requisito, numTT, ref1, ref2, observaciones, estado, ubicacion, fecha, hora, ruta, folioP ->
            viewModel.cambiarIdYGuardar(item.id, idEditado, nombre, semana, requisito, numTT, ref1, ref2, observaciones, estado, ubicacion, fecha, hora, ruta, folioP) { ok, error ->
                if (!ok) Toast.makeText(context, error ?: "No se pudo guardar", Toast.LENGTH_LONG).show()
            }
            itemToEdit = null
        })
    }
    if (showCreateDialog) {
        MatrizFullFormDialog(null, null, { showCreateDialog = false }, { idEditado, nombre, semana, requisito, numTT, ref1, ref2, observaciones, estado, ubicacion, fecha, hora, ruta, folioP ->
            viewModel.crearRegistro(idEditado, nombre, semana, requisito, numTT, ref1, ref2, observaciones, estado, ubicacion, fecha, hora, ruta, folioP)
            showCreateDialog = false
        })
    }
    itemToDelete?.let { item ->
        AlertDialog(onDismissRequest = { if (!deleteInProgress) itemToDelete = null }, title = { Text("Eliminar de Pase") },
            text = { Text("¿Seguro que quieres eliminar a \"${item.nombre}\" de Pase? Esto NO afecta Matriz.") },
            confirmButton = { Button(onClick = { viewModel.eliminarRegistro(item.id) { ok, error -> itemToDelete = null; scope.launch { snackbarHostState.showSnackbar(if (ok) "Registro eliminado de Pase" else error ?: "Error") } } }, enabled = !deleteInProgress) { Text(if (deleteInProgress) "Eliminando…" else "Eliminar") } },
            dismissButton = { TextButton(onClick = { itemToDelete = null }) { Text("Cancelar") } })
    }
}
