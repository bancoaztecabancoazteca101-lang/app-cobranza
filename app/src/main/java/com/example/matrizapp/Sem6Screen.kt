package com.example.matrizapp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sem6Screen(viewModel: Sem6ViewModel, searchQuery: String = "") {
    val allItems by viewModel.items.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val lastUpdated by viewModel.lastUpdated.collectAsState()
    val isFromCache by viewModel.isFromCache.collectAsState()
    var itemToView by remember { mutableStateOf<Sem6Item?>(null) }

    val items = remember(allItems, searchQuery) {
        if (searchQuery.isBlank()) allItems else allItems.filter { item ->
            val q = searchQuery.trim()
            item.nombre.contains(q, ignoreCase = true) ||
                item.cu.contains(q, ignoreCase = true) ||
                item.id.contains(q, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(ClayBackground)) {
        // Barra de estado: última actualización + botón de refrescar
        Surface(color = ClayPrimaryContainer, tonalElevation = 0.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentSem6SheetName().replace("Cont-Sem-", "Semana "),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = ClayPrimary
                    )
                    Text(
                        text = when {
                            isLoading -> "Actualizando…"
                            lastUpdated != null -> {
                                val df = SimpleDateFormat("HH:mm", Locale.getDefault())
                                val prefijo = if (isFromCache) "Último dato guardado: " else "Actualizado: "
                                prefijo + df.format(Date(lastUpdated!!))
                            }
                            else -> "Sin datos aún"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ClayOnSurface
                    )
                }
                IconButton(onClick = { viewModel.cargar() }, enabled = !isLoading) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar", tint = ClayPrimary)
                    }
                }
            }
        }

        error?.let { msg ->
            Surface(color = Color(0xFFFFEBEE)) {
                Text(
                    text = "No se pudo actualizar: $msg. Mostrando el último dato disponible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC62828),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        if (items.isEmpty() && !isLoading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Sin cuentas registradas esta semana", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(items, key = { it.id }) { item -> Sem6ItemCard(item, driveHelper = viewModel.driveHelper, onClick = { itemToView = item }) }
            }
        }
    }

    itemToView?.let { item ->
        Sem6DetailDialog(item = item, driveHelper = viewModel.driveHelper, viewModel = viewModel, onDismiss = { itemToView = null })
    }
}

/** Formatea el Req como precio: "5979" -> "$5,979". Si no es numérico, regresa el valor tal cual. */
private fun formatearReq(req: String): String {
    val limpio = req.replace("[^0-9.]".toRegex(), "")
    val numero = limpio.toDoubleOrNull() ?: return req
    val formateador = java.text.NumberFormat.getNumberInstance(Locale("es", "MX")).apply {
        maximumFractionDigits = 0
    }
    return "$" + formateador.format(numero)
}

@Composable
fun Sem6ItemCard(item: Sem6Item, driveHelper: DriveHelper, onClick: () -> Unit) {
    val cardColor = if (item.susceptible.equals("Recuperado", ignoreCase = true)) {
        Color(0xFFDCEDD9)
    } else {
        ClaySurface
    }
    ClayCard(onClick = onClick, containerColor = cardColor) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PortadaThumbnail(rawImageUrl = item.imagenUrl, driveHelper = driveHelper)
                Column {
                    Text(item.nombre, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Sem: ${item.sem}  ·  Req: ${formatearReq(item.req)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text("CU: ${item.cu}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    if (item.colonia.isNotBlank()) {
                        Text("Colonia: ${item.colonia}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    CalleLabel(ubicacion = item.ubicacion)
                    if (item.capital.isNotBlank()) {
                        Text("Capital: ${formatearReq(item.capital)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    if (item.observaciones.isNotBlank()) {
                        Text("Obs: ${item.observaciones}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }
            Surface(
                color = ClayPrimaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, tint = ClayPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${item.visitas}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ClayPrimary)
                }
            }
        }
    }
}

/** Detalle de un registro de Semana 6. Muestra la imagen a tamaño real (tócala para ampliarla
 * más), botones de llamar/GPS si el Apps Script ya manda NumTT/Ubicación, y 3 notas editables
 * que se guardan directo en las columnas M/N/O de "Cont-Sem-NN" (Se Contiene, Susceptible,
 * Observaciones): son pocos registros y se escriben al toque sin necesitar cola local. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sem6DetailDialog(item: Sem6Item, driveHelper: DriveHelper, viewModel: Sem6ViewModel, onDismiss: () -> Unit) {
    var seContiene by remember(item.id) { mutableStateOf(item.seContiene) }
    var susceptible by remember(item.id) { mutableStateOf(item.susceptible) }
    var observaciones by remember(item.id) { mutableStateOf(item.observaciones) }
    var capital by remember(item.id) { mutableStateOf(item.capital) }
    var susceptibleMenuExpanded by remember { mutableStateOf(false) }
    val isSaving by viewModel.isSavingNotas.collectAsState()
    val errorNotas by viewModel.errorNotas.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.nombre) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PortadaThumbnail(rawImageUrl = item.imagenUrl, driveHelper = driveHelper, size = 160.dp)
                }
                Text("Sem: ${item.sem}  ·  Req: ${formatearReq(item.req)}")
                OutlinedTextField(
                    value = capital,
                    onValueChange = { input -> capital = input.filter { it.isDigit() || it == '.' } },
                    label = { Text("Capital") },
                    leadingIcon = { Text("$") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("CU: ${item.cu}")
                if (item.colonia.isNotBlank()) Text("Colonia: ${item.colonia}")
                CalleLabel(ubicacion = item.ubicacion, style = MaterialTheme.typography.bodyMedium)
                if (item.ultimaFechaVisita.isNotBlank()) Text("Última vez: ${item.ultimaFechaVisita}")
                Text("Visitas: ${item.visitas}")
                if (item.observaciones.isNotBlank()) {
                    Text("Observaciones: ${item.observaciones}", style = MaterialTheme.typography.bodyMedium)
                }
                if (item.numTT.isBlank() && item.ubicacion.isBlank()) {
                    Text(
                        "Llamar/GPS aún no disponibles para Semana 6 (falta actualizar el script de Apps Script).",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray
                    )
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                    ContactActionsRow(numTT = item.numTT, ubicacion = item.ubicacion)
                }

                Spacer(modifier = Modifier.height(4.dp))
                Divider()
                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = seContiene,
                    onValueChange = { input -> seContiene = input.filter { it.isDigit() || it == '.' } },
                    label = { Text("Se Contiene") },
                    leadingIcon = { Text("$") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(expanded = susceptibleMenuExpanded, onExpandedChange = { susceptibleMenuExpanded = it }) {
                    OutlinedTextField(
                        value = susceptible, onValueChange = {}, label = { Text("Status") },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = susceptibleMenuExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = susceptibleMenuExpanded, onDismissRequest = { susceptibleMenuExpanded = false }) {
                        DropdownMenuItem(text = { Text("(Sin definir)") }, onClick = { susceptible = ""; susceptibleMenuExpanded = false })
                        listOf("Susceptible", "Si", "No", "Recuperado").forEach { opcion ->
                            DropdownMenuItem(text = { Text(opcion) }, onClick = { susceptible = opcion; susceptibleMenuExpanded = false })
                        }
                    }
                }

                OutlinedTextField(
                    value = observaciones,
                    onValueChange = { observaciones = it },
                    label = { Text("Observaciones") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )

                errorNotas?.let { msg ->
                    Text(msg, color = Color(0xFFC62828), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.guardarNotas(item.id, seContiene, susceptible, observaciones, capital) { ok ->
                        if (ok) onDismiss()
                    }
                },
                enabled = !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text("Guardar")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cerrar") } }
    )
}
