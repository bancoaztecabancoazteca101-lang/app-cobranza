package com.example.matrizapp

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RutaIAScreen(viewModel: RutaIAViewModel, matrizViewModel: MatrizViewModel) {
    val context = LocalContext.current
    val ruta by viewModel.rutaOrdenada.collectAsState()
    val matrizList by matrizViewModel.matrizList.collectAsState()
    // Foto del cliente (misma "portada" que muestra Matriz) para las paradas que coinciden con un registro de Matriz
    val fotosMatriz = remember(matrizList) {
        matrizList.associate { it.id to (it.imagenUrl?.takeIf { u -> u.isNotBlank() } ?: it.imagenUrl2) }
    }
    val configuracionGuardada by viewModel.configuracion.collectAsState()
    val procesando by viewModel.procesando.collectAsState()
    val progreso by viewModel.progreso.collectAsState()
    var mostrarMenu by remember { mutableStateOf(false) }
    var mostrarFiltros by remember { mutableStateOf(false) }
    var mostrarMapa by remember { mutableStateOf(false) }
    var mostrarAyuda by remember { mutableStateOf(false) }
    var configuracionDraft by remember { mutableStateOf(configuracionGuardada) }

    LaunchedEffect(mostrarFiltros) {
        if (mostrarFiltros) configuracionDraft = configuracionGuardada
    }

    val importarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.importarJson(uri, configuracionGuardada) { exito, mensaje, advertencias ->
            val texto = if (exito) buildString {
                append(mensaje ?: "Ruta generada")
                if (advertencias.isNotEmpty()) append("\nAdvertencias: ${advertencias.size}")
            } else mensaje ?: "No se pudo importar"
            Toast.makeText(context, texto, Toast.LENGTH_LONG).show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ruta IA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (ruta.isEmpty()) "Sin ruta cargada" else "${ruta.size} paradas", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = { mostrarAyuda = true }) { Icon(Icons.Default.HelpOutline, "Ayuda") }
                    if (ruta.any { it.lat != null && it.lng != null }) IconButton(onClick = { mostrarMapa = true }) { Icon(Icons.Default.Map, "Mapa") }
                    IconButton(onClick = { mostrarMenu = true }) { Icon(Icons.Default.MoreVert, "Más opciones") }
                    DropdownMenu(expanded = mostrarMenu, onDismissRequest = { mostrarMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Filtros y configuración") },
                            leadingIcon = { Icon(Icons.Default.Tune, null) },
                            onClick = { mostrarMenu = false; mostrarFiltros = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Importar JSON de Gemini") },
                            leadingIcon = { Icon(Icons.Default.UploadFile, null) },
                            enabled = !procesando,
                            onClick = { mostrarMenu = false; importarLauncher.launch(arrayOf("application/json", "text/plain", "text/*")) }
                        )
                        if (ruta.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Limpiar ruta") },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                                onClick = { mostrarMenu = false; viewModel.limpiarRutaAhora() }
                            )
                        }
                    }
                }
            }

            if (ruta.isEmpty() && !procesando) {
                Box(Modifier.fillMaxSize().weight(1f), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Default.Route, null, tint = Color.Gray, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Sube las fotos a Gemini, guarda el JSON y después impórtalo aquí.", textAlign = TextAlign.Center, color = Color.Gray)
                    }
                }
            } else {
                // Lista local editable en vivo mientras se arrastra una tarjeta; se resincroniza con
                // la ruta real del ViewModel en cuanto no hay nada siendo arrastrado (import nuevo,
                // cambio de filtros, etc. no pisan un arrastre en curso).
                var listaLocal by remember { mutableStateOf(ruta) }
                var idArrastrado by remember { mutableStateOf<String?>(null) }
                var desplazamientoArrastre by remember { mutableStateOf(0f) }
                var alturaPromedioItemPx by remember { mutableStateOf(260f) }
                LaunchedEffect(ruta) { if (idArrastrado == null) listaLocal = ruta }

                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(listaLocal, key = { _, item -> item.id }) { index, item ->
                        RutaIANuevaCard(
                            item = item,
                            posicion = index + 1,
                            imagenUrl = item.cuMatrizMatch?.let { fotosMatriz[it] },
                            driveHelper = matrizViewModel.driveHelper,
                            puedeSubir = index > 0,
                            puedeBajar = index < listaLocal.lastIndex,
                            onVisitado = { viewModel.alternarVisitado(item) },
                            onSubir = { viewModel.moverManualmente(item.id, -1) },
                            onBajar = { viewModel.moverManualmente(item.id, 1) },
                            onMatriz = {
                                if (item.cuMatrizMatch == null) Toast.makeText(context, "Cliente nuevo o sin coincidencia en Matriz", Toast.LENGTH_SHORT).show()
                                else Toast.makeText(context, "Coincide en Matriz: ${item.nombre}", Toast.LENGTH_SHORT).show()
                            },
                            arrastrando = idArrastrado == item.id,
                            desplazamientoY = if (idArrastrado == item.id) desplazamientoArrastre else 0f,
                            onArrastreInicio = { idArrastrado = item.id; desplazamientoArrastre = 0f },
                            onArrastre = { deltaY, alturaPx ->
                                if (alturaPx > 40f) alturaPromedioItemPx = alturaPx
                                desplazamientoArrastre += deltaY
                                val actual = listaLocal.indexOfFirst { it.id == item.id }
                                if (actual != -1) {
                                    val destino = (actual + (desplazamientoArrastre / alturaPromedioItemPx).roundToInt()).coerceIn(0, listaLocal.lastIndex)
                                    if (destino != actual) {
                                        listaLocal = listaLocal.toMutableList().apply { add(destino, removeAt(actual)) }
                                        desplazamientoArrastre -= (destino - actual) * alturaPromedioItemPx
                                    }
                                }
                            },
                            onArrastreFin = {
                                if (idArrastrado != null) viewModel.reordenarManual(listaLocal.map { it.id })
                                idArrastrado = null
                                desplazamientoArrastre = 0f
                            }
                        )
                    }
                }
            }
        }
        if (procesando) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)), Alignment.Center) {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(progreso.ifBlank { "Procesando..." })
                    }
                }
            }
        }
    }

    if (mostrarFiltros) {
        AlertDialog(
            onDismissRequest = { mostrarFiltros = false },
            title = { Text("Filtros y configuración") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 560.dp)) {
                    item {
                        Text("FILTROS", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(configuracionDraft.usarDiasAtraso, { configuracionDraft = configuracionDraft.copy(usarDiasAtraso = it) })
                            Text("Días de atraso", fontWeight = FontWeight.SemiBold)
                        }
                        if (configuracionDraft.usarDiasAtraso) {
                            OutlinedTextField(
                                value = configuracionDraft.minimoDiasAtraso?.toString() ?: "",
                                onValueChange = { valor -> configuracionDraft = configuracionDraft.copy(minimoDiasAtraso = valor.filter(Char::isDigit).toIntOrNull()) },
                                label = { Text("Atraso mínimo") },
                                placeholder = { Text("Opcional") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            DireccionSelector(
                                titulo = "Orden de días de atraso",
                                direccion = configuracionDraft.direccionDiasAtraso,
                                onChange = { configuracionDraft = configuracionDraft.copy(direccionDiasAtraso = it) }
                            )
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(configuracionDraft.usarSaldoAtraso, { configuracionDraft = configuracionDraft.copy(usarSaldoAtraso = it) })
                            Text("Saldo en atraso", fontWeight = FontWeight.SemiBold)
                        }
                        if (configuracionDraft.usarSaldoAtraso) {
                            OutlinedTextField(
                                value = configuracionDraft.minimoSaldoAtraso?.toString() ?: "",
                                onValueChange = { valor -> configuracionDraft = configuracionDraft.copy(minimoSaldoAtraso = valor.filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.').toDoubleOrNull()) },
                                label = { Text("Saldo mínimo") },
                                placeholder = { Text("Opcional") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            DireccionSelector(
                                titulo = "Orden de saldo en atraso",
                                direccion = configuracionDraft.direccionSaldoAtraso,
                                onChange = { configuracionDraft = configuracionDraft.copy(direccionSaldoAtraso = it) }
                            )
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(configuracionDraft.excluirNoVisitables, { configuracionDraft = configuracionDraft.copy(excluirNoVisitables = it) })
                            Text("Excluir registros no visitables")
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(configuracionDraft.priorizarNuevos, { configuracionDraft = configuracionDraft.copy(priorizarNuevos = it) })
                            Text("Visitar primero a los clientes nuevos (no están en Matriz)")
                        }
                    }
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text("RUTA", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(configuracionDraft.modoRuta == ModoRutaIA.AUTOMATICA, { configuracionDraft = configuracionDraft.copy(modoRuta = ModoRutaIA.AUTOMATICA) })
                            Text("Ruta automática")
                        }
                        if (configuracionDraft.modoRuta == ModoRutaIA.AUTOMATICA) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(configuracionDraft.usarGpsInicio, { configuracionDraft = configuracionDraft.copy(usarGpsInicio = it) })
                                Text("Iniciar por punto más cercano a mi GPS")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(configuracionDraft.usarCercaniaEncadenada, { configuracionDraft = configuracionDraft.copy(usarCercaniaEncadenada = it) })
                                Text("Continuar por cercanía entre puntos")
                            }
                            Text("La siguiente parada se calcula desde el punto anterior, no nuevamente desde mi GPS.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            DireccionSelector(
                                titulo = "Orden de cercanía",
                                direccion = configuracionDraft.direccionCercania,
                                onChange = { configuracionDraft = configuracionDraft.copy(direccionCercania = it) }
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(true, {})
                                Text("Ruta manual")
                            }
                            Text("Yo ordeno las paradas. La aplicación respeta el orden manual y solamente mantiene activos los filtros seleccionados.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.actualizarConfiguracion(configuracionDraft)
                    mostrarFiltros = false
                }) { Text("Aplicar") }
            },
            dismissButton = { TextButton(onClick = { mostrarFiltros = false }) { Text("Cancelar") } }
        )
    }

    if (mostrarAyuda) AlertDialog(onDismissRequest = { mostrarAyuda = false }, title = { Text("Cómo funciona") }, text = { Text("Los filtros se pueden combinar. Días de atraso y saldo en atraso primero determinan qué clientes entran. La ruta automática puede iniciar por el punto más cercano a tu GPS y después continuar desde cada punto anterior. La ruta manual respeta el orden que establezcas.\n\nLos clientes nuevos (tarjeta azul, no encontrados en Matriz) se visitan primero si activas esa opción. No se dan de alta en Matriz al importar: solo cuando marcas \"Visitado\" se crea su registro en Matriz.\n\nGemini extrae los datos; la app valida, filtra y decide la ruta.") }, confirmButton = { TextButton(onClick = { mostrarAyuda = false }) { Text("Entendido") } })

    if (mostrarMapa) Dialog(onDismissRequest = { mostrarMapa = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        RutaIAMapaFullScreen(items = ruta, onCerrar = { mostrarMapa = false }, onMarcadorClick = { })
    }
}

@Composable
private fun DireccionSelector(titulo: String, direccion: DireccionOrdenRutaIA, onChange: (DireccionOrdenRutaIA) -> Unit) {
    Text(titulo, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (direccion == DireccionOrdenRutaIA.ASC) {
            Button(onClick = { onChange(DireccionOrdenRutaIA.ASC) }, modifier = Modifier.weight(1f)) { Text("Menor → mayor") }
            OutlinedButton(onClick = { onChange(DireccionOrdenRutaIA.DESC) }, modifier = Modifier.weight(1f)) { Text("Mayor → menor") }
        } else {
            OutlinedButton(onClick = { onChange(DireccionOrdenRutaIA.ASC) }, modifier = Modifier.weight(1f)) { Text("Menor → mayor") }
            Button(onClick = { onChange(DireccionOrdenRutaIA.DESC) }, modifier = Modifier.weight(1f)) { Text("Mayor → menor") }
        }
    }
}

@Composable
private fun RutaIANuevaCard(
    item: RutaIAEntity, posicion: Int, imagenUrl: String?, driveHelper: DriveHelper, puedeSubir: Boolean, puedeBajar: Boolean,
    onVisitado: () -> Unit, onSubir: () -> Unit, onBajar: () -> Unit, onMatriz: () -> Unit,
    arrastrando: Boolean, desplazamientoY: Float,
    onArrastreInicio: () -> Unit, onArrastre: (deltaY: Float, alturaPx: Float) -> Unit, onArrastreFin: () -> Unit
) {
    val visitado = item.estado.equals("Visitado", ignoreCase = true)
    val colorFondo = when {
        visitado -> Color(0xFFE8F5E9)
        item.esNuevo -> Color(0xFFE3F2FD)
        else -> MaterialTheme.colorScheme.surface
    }
    var alturaTarjetaPx by remember { mutableStateOf(0f) }
    Card(
        modifier = Modifier
            .zIndex(if (arrastrando) 1f else 0f)
            .graphicsLayer { translationY = desplazamientoY }
            .onGloballyPositioned { alturaTarjetaPx = it.size.height.toFloat() }
            .shadow(if (arrastrando) 10.dp else 0.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colorFondo)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onSubir, enabled = puedeSubir, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.KeyboardArrowUp, null) }
                Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) { Text("$posicion", fontWeight = FontWeight.Bold) }
                IconButton(onClick = onBajar, enabled = puedeBajar, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.KeyboardArrowDown, null) }
            }
            Spacer(Modifier.width(8.dp))
            // Miniatura de la foto del cliente; al tocarla se ve en grande (igual que en Matriz)
            PortadaThumbnail(rawImageUrl = imagenUrl, driveHelper = driveHelper, size = 56.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.nombre, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (item.esNuevo) AssistChip(
                        onClick = {},
                        label = { Text("Nuevo") },
                        colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF1565C0), labelColor = Color.White)
                    )
                }
                Text(item.direccion, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Row(Modifier.padding(top = 5.dp)) {
                    item.diasAtraso?.let { Text("Atraso: $it d.  ", style = MaterialTheme.typography.labelSmall) }
                    item.saldoAtraso?.let { Text("Saldo: $${"%,.0f".format(it)}  ", style = MaterialTheme.typography.labelSmall) }
                    item.pagoRequerido?.let { Text("Req.: $${"%,.0f".format(it)}", style = MaterialTheme.typography.labelSmall) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = onVisitado, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) { Text(if (visitado) "Visitado" else "Marcar visitado") }
                    OutlinedButton(onClick = onMatriz, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) { Text("Matriz") }
                }
            }
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "Mantén presionado y arrastra para reordenar",
                tint = Color.Gray,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(28.dp)
                    .pointerInput(item.id) {
                        detectDragGestures(
                            onDragStart = { onArrastreInicio() },
                            onDragEnd = { onArrastreFin() },
                            onDragCancel = { onArrastreFin() },
                            onDrag = { change, arrastre ->
                                change.consume()
                                onArrastre(arrastre.y, alturaTarjetaPx)
                            }
                        )
                    }
            )
        }
    }
}
