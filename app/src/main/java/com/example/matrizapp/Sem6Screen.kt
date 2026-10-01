package com.example.matrizapp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sem6Screen(viewModel: Sem6ViewModel, searchQuery: String = "") {
    val allItems by viewModel.items.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val lastUpdated by viewModel.lastUpdated.collectAsState()
    val isFromCache by viewModel.isFromCache.collectAsState()
    val semanaSeleccionada by viewModel.semanaSeleccionada.collectAsState()
    val semanasDisponibles by viewModel.semanasDisponibles.collectAsState()
    var selectorSemanaExpanded by remember { mutableStateOf(false) }
    var itemToView by remember { mutableStateOf<Sem6Item?>(null) }
    var mostrarNuevoRegistro by remember { mutableStateOf(false) }
    var mostrarBuscarMatriz by remember { mutableStateOf(false) }
    var prefillMatriz by remember { mutableStateOf<MatrizEntity?>(null) }
    val matrizList by viewModel.matrizList.collectAsState()
    val contextFoto = androidx.compose.ui.platform.LocalContext.current
    val leyendoFoto by viewModel.leyendoFotoCapital.collectAsState()
    val aplicandoCapital by viewModel.aplicandoCapital.collectAsState()
    var propuestaCapital by remember { mutableStateOf<Pair<List<CambioCapital>, Int>?>(null) }
    val fotoCapitalLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.analizarFotoCapital(contextFoto, uri) { cambios, filas -> propuestaCapital = cambios to filas }
    }

    val items = remember(allItems, searchQuery) {
        if (searchQuery.isBlank()) allItems else allItems.filter { item ->
            val q = searchQuery.trim()
            coincideBusqueda(item.nombre, q) ||
                coincideBusqueda(item.cu, q) ||
                coincideBusqueda(item.id, q)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().background(ClayBackground)) {
        // Barra de estado: última actualización + botón de refrescar
        Surface(color = ClayPrimaryContainer, tonalElevation = 0.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // Selector de semana: toca el nombre para elegir una semana pasada.
                    // Solo se llenan aquí las que ya tienen hoja creada en el Sheet.
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { selectorSemanaExpanded = true }
                        ) {
                            Text(
                                text = semanaSeleccionada.replace("Cont-Sem-", "Semana "),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = ClayPrimary
                            )
                            Icon(
                                Icons.Default.ArrowDropDown, contentDescription = "Elegir semana",
                                tint = ClayPrimary, modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(expanded = selectorSemanaExpanded, onDismissRequest = { selectorSemanaExpanded = false }) {
                            if (semanasDisponibles.isEmpty()) {
                                DropdownMenuItem(text = { Text("Cargando semanas…") }, onClick = {}, enabled = false)
                            }
                            semanasDisponibles.forEach { sheetName ->
                                val esActual = sheetName == currentSem6SheetName()
                                DropdownMenuItem(
                                    text = { Text(sheetName.replace("Cont-Sem-", "Semana ") + if (esActual) " (actual)" else "") },
                                    onClick = {
                                        viewModel.seleccionarSemana(sheetName)
                                        selectorSemanaExpanded = false
                                    }
                                )
                            }
                        }
                    }
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
                    // Total de cuentas y suma de Capital (solo capital, sin Se Contiene ni Req)
                    // de TODAS las cuentas de la semana seleccionada -- no del resultado filtrado
                    // por búsqueda, para que siempre refleje el total real de la semana.
                    if (allItems.isNotEmpty()) {
                        Text(
                            text = "Cuentas: ${allItems.size}  ·  Capital: ${formatCapitalTotal(sumaCapital(allItems))}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = ClayOnSurface
                        )
                    }
                }
                IconButton(onClick = { fotoCapitalLauncher.launch("image/*") }, enabled = !leyendoFoto && !aplicandoCapital) {
                    if (leyendoFoto) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.AddAPhoto, contentDescription = "Actualizar capital con foto", tint = ClayPrimary)
                    }
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
            Surface(color = ClayRedContainer) {
                Text(
                    text = "No se pudo actualizar: $msg. Mostrando el último dato disponible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClayRedText,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        if (items.isEmpty() && !isLoading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                val etiqueta = semanaSeleccionada.replace("Cont-Sem-", "Semana ")
                Text("Sin cuentas registradas en $etiqueta", color = Color.Gray)
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

    FloatingActionButton(onClick = { mostrarBuscarMatriz = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
        Icon(Icons.Default.Add, contentDescription = "Nuevo registro")
    }
    }

    propuestaCapital?.let { (cambios, filasLeidas) ->
        val aCambiar = cambios.filter { it.cambia }
        AlertDialog(
            onDismissRequest = { if (!aplicandoCapital) propuestaCapital = null },
            title = { Text("Capital desde la foto") },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Renglones leídos: $filasLeidas · Coinciden: ${cambios.size} de ${allItems.size} registros · Cambian: ${aCambiar.size}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (filasLeidas == 0) Text("No se pudo leer la tabla. Prueba con una foto más nítida y de frente.", color = ClayRedText, style = MaterialTheme.typography.bodySmall)
                    aCambiar.forEach { c ->
                        Text(
                            "${c.item.nombre}${if (c.porCu) "" else " (por nombre)"}\n${if (c.capitalActual.isBlank()) "sin capital" else formatearReq(c.capitalActual)} → ${formatearReq(c.nuevoCapital)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (cambios.isNotEmpty() && aCambiar.isEmpty()) Text("Todos los capitales ya coinciden con la foto.", style = MaterialTheme.typography.bodySmall)
                    val sinCoincidencia = allItems.filter { i -> cambios.none { it.item.id == i.id } }
                    if (sinCoincidencia.isNotEmpty()) {
                        Text("Sin coincidencia en la foto (no se tocan): ${sinCoincidencia.joinToString { it.nombre }}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.aplicarCapitales(aCambiar) { ok, fallidos ->
                            propuestaCapital = null
                            android.widget.Toast.makeText(contextFoto, if (fallidos == 0) "Capital actualizado en $ok registros" else "Actualizados $ok, fallaron $fallidos", android.widget.Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = aCambiar.isNotEmpty() && !aplicandoCapital
                ) {
                    if (aplicandoCapital) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text("Aplicar (${aCambiar.size})")
                }
            },
            dismissButton = { TextButton(onClick = { propuestaCapital = null }, enabled = !aplicandoCapital) { Text("Cancelar") } }
        )
    }

    itemToView?.let { item ->
        Sem6DetailDialog(item = item, driveHelper = viewModel.driveHelper, viewModel = viewModel, onDismiss = { itemToView = null })
    }
    if (mostrarBuscarMatriz) {
        SolicitudBuscarMatrizDialog(
            matriz = matrizList,
            onDismiss = { mostrarBuscarMatriz = false },
            onElegir = { m -> prefillMatriz = m; mostrarBuscarMatriz = false; mostrarNuevoRegistro = true },
            onDesdeCero = { prefillMatriz = null; mostrarBuscarMatriz = false; mostrarNuevoRegistro = true }
        )
    }
    if (mostrarNuevoRegistro) {
        Sem6NuevoRegistroDialog(
            viewModel = viewModel,
            prefill = prefillMatriz,
            onDismiss = { mostrarNuevoRegistro = false; prefillMatriz = null },
            onCreado = { itemToView = it }
        )
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

/** Suma el campo Capital (columna capturada a mano por Diego, texto libre) de todas las
 * cuentas de la semana; ignora capitales vacíos o no numéricos en vez de tronar. */
private fun sumaCapital(items: List<Sem6Item>): Double =
    items.sumOf { it.capital.replace("[^0-9.]".toRegex(), "").toDoubleOrNull() ?: 0.0 }

private fun formatCapitalTotal(v: Double): String {
    val formateador = java.text.NumberFormat.getNumberInstance(Locale("es", "MX")).apply {
        maximumFractionDigits = 0
    }
    return "$" + formateador.format(v)
}

@Composable
fun Sem6ItemCard(item: Sem6Item, driveHelper: DriveHelper, onClick: () -> Unit) {
    val cardColor = when {
        item.susceptible.equals("Recuperado", ignoreCase = true) -> ClayGreenContainer
        item.susceptible.equals("Susceptible", ignoreCase = true) -> ClayYellowContainer
        else -> ClaySurface
    }
    ClayCard(onClick = onClick, containerColor = cardColor) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(
                modifier = Modifier.weight(1f).padding(end = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PortadaThumbnail(rawImageUrl = item.imagenUrl, driveHelper = driveHelper)
                Column(modifier = Modifier.weight(1f)) {
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
                    if (item.seContiene.isNotBlank()) {
                        Text("Se Contiene: ${formatearReq(item.seContiene)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    if (item.susceptible.isNotBlank()) {
                        Text("Status: ${item.susceptible}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, tint = ClayPrimary, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("${item.visitas}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ClayPrimary)
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
    var mostrarConfirmarEliminar by remember { mutableStateOf(false) }
    val isSaving by viewModel.isSavingNotas.collectAsState()
    val errorNotas by viewModel.errorNotas.collectAsState()
    val isGuardandoRegistro by viewModel.isGuardandoRegistro.collectAsState()
    val errorRegistro by viewModel.errorRegistro.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(item.nombre, modifier = Modifier.weight(1f))
                IconButton(onClick = { mostrarConfirmarEliminar = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Eliminar registro de Semana 6", tint = ClayRedText)
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
                    Text(msg, color = ClayRedText, style = MaterialTheme.typography.bodySmall)
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

    if (mostrarConfirmarEliminar) {
        AlertDialog(
            onDismissRequest = { if (!isGuardandoRegistro) mostrarConfirmarEliminar = false },
            title = { Text("Eliminar registro") },
            text = {
                Column {
                    Text("¿Seguro que quieres eliminar a \"${item.nombre}\" de esta semana? Esto borra la fila en Google Sheets y no se puede deshacer.")
                    errorRegistro?.let { msg -> Text(msg, color = ClayRedText, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.eliminarRegistro(item.id) { ok ->
                            if (ok) { mostrarConfirmarEliminar = false; onDismiss() }
                        }
                    },
                    enabled = !isGuardandoRegistro,
                    colors = ButtonDefaults.buttonColors(containerColor = ClayRedText)
                ) {
                    if (isGuardandoRegistro) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text("Eliminar")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { mostrarConfirmarEliminar = false }, enabled = !isGuardandoRegistro) { Text("Cancelar") } }
        )
    }
}

/** Diálogo para agregar un registro nuevo a la hoja de la semana que se está viendo (antes
 * esta hoja era de solo lectura, poblada solo por el script de Apps Script). */
@Composable
fun Sem6NuevoRegistroDialog(viewModel: Sem6ViewModel, onDismiss: () -> Unit, onCreado: (Sem6Item) -> Unit, prefill: MatrizEntity? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var nombre by remember { mutableStateOf(prefill?.nombre ?: "") }
    var sem by remember { mutableStateOf("6") }
    var req by remember { mutableStateOf(prefill?.requisito?.filter { c -> c.isDigit() || c == '.' } ?: "") }
    var cu by remember { mutableStateOf(prefill?.folioP ?: "") }
    var colonia by remember { mutableStateOf("") }
    var numTT by remember { mutableStateOf(prefill?.numTT ?: "") }
    var ubicacion by remember { mutableStateOf(prefill?.ubicacion?.takeIf { it != "N/A" } ?: "") }
    var buscandoUbicacion by remember { mutableStateOf(false) }
    var mostrarBuscarDireccion by remember { mutableStateOf(false) }
    var buscandoDireccionTexto by remember { mutableStateOf(false) }
    val isGuardando by viewModel.isGuardandoRegistro.collectAsState()
    val errorRegistro by viewModel.errorRegistro.collectAsState()

    // Colonia: se deduce de la ubicación que ya trae el cliente de Matriz (mismo resolvedor que las tarjetas).
    LaunchedEffect(prefill?.id) {
        if (prefill != null && colonia.isBlank()) {
            val (c, _) = resolverColoniaYCalle(context, prefill.ubicacion)
            if (!c.isNullOrBlank()) colonia = c
        }
    }

    LaunchedEffect(buscandoUbicacion) {
        if (buscandoUbicacion) {
            ubicacion = obtenerUbicacionActual(context) ?: ubicacion
            buscandoUbicacion = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo registro") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(value = nombre, onValueChange = { nombre = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = sem, onValueChange = { sem = it.filter { c -> c.isDigit() } }, label = { Text("Sem") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = req, onValueChange = { req = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Req") }, leadingIcon = { Text("$") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = cu, onValueChange = { cu = it }, label = { Text("CU") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = colonia, onValueChange = { colonia = it }, label = { Text("Colonia") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = numTT, onValueChange = { numTT = it }, label = { Text("Num TT") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = ubicacion, onValueChange = { ubicacion = it }, label = { Text("Ubicación (lat,lng)") },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { mostrarBuscarDireccion = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Buscar dirección escrita")
                            }
                            IconButton(onClick = { buscandoUbicacion = true }) {
                                Icon(Icons.Default.MyLocation, contentDescription = "Usar ubicación actual")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                errorRegistro?.let { msg -> Text(msg, color = ClayRedText, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.agregarRegistro(nombre, sem, req, cu, colonia, ubicacion, numTT) { ok ->
                        if (ok) onDismiss()
                    }
                },
                enabled = nombre.isNotBlank() && !isGuardando
            ) {
                if (isGuardando) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text("Guardar")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isGuardando) { Text("Cancelar") } }
    )

    if (mostrarBuscarDireccion) {
        BuscarDireccionDialogSem6(
            buscando = buscandoDireccionTexto,
            onDismiss = { mostrarBuscarDireccion = false },
            onBuscar = { texto ->
                scope.launch {
                    buscandoDireccionTexto = true
                    val coords = geocodificarDireccion(context, texto)
                    buscandoDireccionTexto = false
                    if (coords != null) {
                        ubicacion = "${coords.first},${coords.second}"
                        mostrarBuscarDireccion = false
                    } else {
                        android.widget.Toast.makeText(context, "No se encontró esa dirección", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}

/** Copia local de BuscarDireccionDialog (la de SharedComponents.kt es privada a ese archivo). */
@Composable
private fun BuscarDireccionDialogSem6(buscando: Boolean, onDismiss: () -> Unit, onBuscar: (String) -> Unit) {
    var texto by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Buscar dirección") },
        text = {
            Column {
                OutlinedTextField(
                    value = texto, onValueChange = { texto = it },
                    label = { Text("Dirección (calle, colonia, CP)") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !buscando
                )
                if (buscando) Text("Buscando…", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = { onBuscar(texto) }, enabled = texto.isNotBlank() && !buscando) { Text("Buscar") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !buscando) { Text("Cancelar") } }
    )
}
