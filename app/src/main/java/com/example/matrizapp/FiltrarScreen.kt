package com.example.matrizapp
import android.widget.Toast
import androidx.compose.foundation.clickable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltrarScreen(viewModel: FiltrarViewModel, searchQuery: String = "") {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("filtrar_contactos", android.content.Context.MODE_PRIVATE) }
    var agregarAutomaticamente by remember {
        mutableStateOf(prefs.getBoolean("agregar_automaticamente", false))
    }
    val allItems by viewModel.items.collectAsState()
    var soloSolicitud by remember { mutableStateOf(false) }
    val items = remember(allItems, searchQuery, soloSolicitud) {
        val base = if (soloSolicitud) allItems.filter { it.solicitudes.isNotEmpty() } else allItems
        if (searchQuery.isBlank()) base else base.filter { item ->
            val q = searchQuery.trim()
            coincideBusqueda(item.nombre, q) ||
                item.cercanos.any { coincideBusqueda(it.nombre, q) || coincideBusqueda(it.numTT, q) } ||
                item.solicitudes.any { coincideBusqueda(it.nombre, q) }
        }
    }
    LaunchedEffect(agregarAutomaticamente, allItems) {
        if (agregarAutomaticamente && allItems.isNotEmpty()) {
            viewModel.agregarContactosAutomaticamente(allItems)
        }
    }

    var itemToView by remember { mutableStateOf<FiltrarItem?>(null) }
    var itemToEdit by remember { mutableStateOf<FiltrarItem?>(null) }
    var itemToFullEdit by remember { mutableStateOf<FiltrarItem?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Agregar contactos automáticamente",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (agregarAutomaticamente) "Activado: los cercanos se agregan solos"
                        else "Desactivado: usa el botón para agregar manualmente",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
                Switch(
                    checked = agregarAutomaticamente,
                    onCheckedChange = { nuevoValor ->
                        agregarAutomaticamente = nuevoValor
                        prefs.edit().putBoolean("agregar_automaticamente", nuevoValor).apply()
                        if (nuevoValor) {
                            viewModel.agregarContactosAutomaticamente(allItems)
                        }
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = soloSolicitud,
                onClick = { soloSolicitud = !soloSolicitud },
                label = { Text("Solo de solicitud (${allItems.count { it.solicitudes.isNotEmpty() }})") }
            )
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Sin registros", color = Color.Gray) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                FiltrarItemCard(item, onCardClick = { itemToView = item }, onEditClick = { itemToEdit = item })
            }
            }
        }
    }
    itemToView?.let { snapshot ->
        val item = items.find { it.id == snapshot.id } ?: snapshot
        FiltrarDetailDialog(
            item = item,
            driveHelper = viewModel.driveHelper,
            onDismiss = { itemToView = null },
            onEditClick = { itemToEdit = item; itemToView = null },
            onNombreClick = { itemToFullEdit = item; itemToView = null },
            onAgregarContacto = { cercano -> viewModel.agregarContactoExtra(item.id, cercano) }
        )
    }
    itemToEdit?.let { item ->
        EditMatrizDialog(
            item = item.original,
            onDismiss = { itemToEdit = null },
            onConfirm = { id, estado, obs -> viewModel.guardarGestion(id, estado, obs); itemToEdit = null }
        )
    }
    itemToFullEdit?.let { item ->
        MatrizFullFormDialog(
            item = item.original,
            viewModel = null,
            onDismiss = { itemToFullEdit = null },
            onSave = { idEditado, nombre, semana, requisito, numTT, ref1, ref2, observaciones, estado, ubicacion, fecha, hora, ruta, folioP, descuentoPago, descuentoAhorro, ref3, ref4, diaPago, domicilioLaboral, diasAtraso, diasApertura ->
                viewModel.guardarRegistroCompleto(item.original.id, idEditado, nombre, semana, requisito, numTT, ref1, ref2, observaciones, estado, ubicacion, fecha, hora, ruta, folioP, descuentoPago, descuentoAhorro, ref3, ref4, diaPago, domicilioLaboral, diasAtraso, diasApertura) { exito, error ->
                    if (!exito) android.widget.Toast.makeText(context, error ?: "No se pudo guardar", Toast.LENGTH_LONG).show()
                }
                itemToFullEdit = null
            }
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
    onEditClick: () -> Unit,
    onNombreClick: () -> Unit,
    onAgregarContacto: (CercanoDetalle) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(item.nombre, modifier = Modifier.weight(1f).clickable(onClick = onNombreClick))
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

                if (item.solicitudes.isNotEmpty()) {
                    Divider(modifier = Modifier.padding(top = 4.dp))
                    Text("Viene de solicitud (10 m):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    item.solicitudes.forEach { sol ->
                        Text("${sol.nombre} (${sol.distanciaM} m)", style = MaterialTheme.typography.bodyMedium)
                    }
                }

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
                    ContactFieldRow("Ref 3", cercano.ref3)
                    ContactFieldRow("Ref 4", cercano.ref4)
                    ColoniaLabel(ubicacion = cercano.ubicacion)
                    if (!cercano.ubicacion.isNullOrBlank() && cercano.ubicacion != "N/A") {
                        ContactActionsRow(numTT = null, ubicacion = cercano.ubicacion)
                    }
                    // Confirmación manual (pedida por Diego): suma Ref1/Ref2 de este cercano
                    // como contacto extra del titular -- desde ahí entran al mismo ciclo
                    // automático de Bloques (misma plantilla de referencia, mismo %nombre% del
                    // titular). Solo se muestra si hay al menos un teléfono y no se agregó ya.
                    val hayTelefono = cercano.ref1.isNotBlank() || cercano.ref2.isNotBlank()
                    if (hayTelefono) {
                        if (cercano.yaAgregado) {
                            Text("✓ Agregado como contacto de ${item.nombre}", style = MaterialTheme.typography.labelSmall, color = ClayGreenSuccess)
                        } else {
                            OutlinedButton(onClick = { onAgregarContacto(cercano) }, modifier = Modifier.padding(top = 2.dp)) {
                                Text("Agregar como contacto de ${item.nombre}")
                            }
                        }
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
            if (item.solicitudes.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("SOLICITUD", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        item.solicitudes.forEach { sol ->
                            Text("${sol.nombre} (${sol.distanciaM} m)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (item.cercanos.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Cercanos por GPS:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                item.cercanos.forEach { c ->
                    Surface(
                        color = if (c.yaAgregado) ClayGreenContainer else Color.Transparent,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${c.nombre} (${c.distanciaM} m)",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            if (c.yaAgregado) {
                                Text(
                                    "✓ CONTACTO AGREGADO",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ClayGreenSuccess,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
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
