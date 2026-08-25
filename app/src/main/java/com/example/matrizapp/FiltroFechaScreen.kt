package com.example.matrizapp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltroFechaScreen(viewModel: FiltroFechaViewModel, searchQuery: String = "") {
    val allItems by viewModel.filteredList.collectAsState()
    val df = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    var itemToView by remember { mutableStateOf<FiltroFechaEntity?>(null) }
    val context = LocalContext.current

    val items = remember(allItems, searchQuery) {
        if (searchQuery.isBlank()) allItems else allItems.filter { item ->
            val q = searchQuery.trim()
            coincideBusqueda(item.nombre, q) ||
                coincideBusqueda(item.numTT, q) ||
                coincideBusqueda(item.observaciones, q) ||
                coincideBusqueda(item.estado, q)
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
        FiltroFechaDetailDialog(
            item = item, df = df, driveHelper = viewModel.driveHelper,
            onDismiss = { itemToView = null },
            onGuardarEstadoYHora = { id, estado, hora ->
                viewModel.guardarEstadoYHora(id, estado, hora) { mensaje ->
                    if (mensaje != null) {
                        android.widget.Toast.makeText(context, mensaje, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
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
                    Text("Req: ${item.req ?: "-"}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                ColoniaLabel(ubicacion = item.ubicacion)
                Spacer(modifier = Modifier.height(4.dp))
                ContactActionsRow(numTT = item.numTT, ref1 = item.ref1, ref2 = item.ref2, ubicacion = item.ubicacion)
            }
        }
    }
}

/**
 * Vista rápida de un registro de Filtro Fecha: info de solo lectura con los botones de
 * llamar/SMS a la derecha de cada campo (NumTT, Ref1, Ref2) igual que AppSheet, más los
 * dos únicos campos editables de esta hoja (Status y Hora) directo aquí mismo, sin un
 * paso extra de "Editar" — son los que el equipo actualiza varias veces al día en campo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltroFechaDetailDialog(
    item: FiltroFechaEntity, df: SimpleDateFormat, driveHelper: DriveHelper,
    onDismiss: () -> Unit, onGuardarEstadoYHora: (id: String, nuevoEstado: String, nuevaHora: String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var estado by remember(item.id) { mutableStateOf(item.estado) }
    var estadoMenuExpanded by remember { mutableStateOf(false) }
    var hora by remember(item.id) { mutableStateOf(item.hora ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(item.nombre, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(item.nombre))
                    android.widget.Toast.makeText(context, "Nombre copiado", android.widget.Toast.LENGTH_SHORT).show()
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
                    PortadaThumbnail(rawImageUrl = item.imagenUrl, driveHelper = driveHelper, size = 140.dp)
                }
                ContactFieldRow("Num TT", item.numTT)
                ContactFieldRow("Ref 1", item.ref1)
                ContactFieldRow("Ref 2", item.ref2)
                if (!item.req.isNullOrBlank()) Text("Req: ${item.req}")
                Text("Fecha: ${df.format(Date(item.fecha))}")
                ColoniaLabel(ubicacion = item.ubicacion, style = MaterialTheme.typography.bodyMedium)
                if (!item.observaciones.isNullOrBlank()) {
                    Divider()
                    Text("Observaciones: ${item.observaciones}")
                }
                if (!item.ubicacion.isNullOrBlank() && item.ubicacion != "N/A") {
                    ContactActionsRow(numTT = null, ubicacion = item.ubicacion)
                }

                Spacer(modifier = Modifier.height(4.dp))
                Divider()
                Spacer(modifier = Modifier.height(4.dp))

                ExposedDropdownMenuBox(expanded = estadoMenuExpanded, onExpandedChange = { estadoMenuExpanded = it }) {
                    OutlinedTextField(
                        value = estado, onValueChange = { estado = it }, label = { Text("Status") },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = estadoMenuExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = estadoMenuExpanded, onDismissRequest = { estadoMenuExpanded = false }) {
                        DropdownMenuItem(text = { Text("(Sin status)") }, onClick = { estado = ""; estadoMenuExpanded = false })
                        ESTADOS_MATRIZ.forEach { opcion ->
                            DropdownMenuItem(text = { Text(opcion) }, onClick = { estado = opcion; estadoMenuExpanded = false })
                        }
                    }
                }

                OutlinedTextField(
                    value = hora, onValueChange = { hora = it }, label = { Text("Hora") },
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            val cal = java.util.Calendar.getInstance()
                            android.app.TimePickerDialog(context, { _, h, min ->
                                cal.set(java.util.Calendar.HOUR_OF_DAY, h)
                                cal.set(java.util.Calendar.MINUTE, min)
                                cal.set(java.util.Calendar.SECOND, 0)
                                hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(cal.time)
                            }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
                        }) { Icon(Icons.Default.AccessTime, contentDescription = "Elegir hora") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onGuardarEstadoYHora(item.id, estado, hora); onDismiss() }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
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
