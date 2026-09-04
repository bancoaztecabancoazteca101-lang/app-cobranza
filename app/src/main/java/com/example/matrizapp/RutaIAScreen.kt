package com.example.matrizapp
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch

@Composable
fun RutaIAScreen(viewModel: RutaIAViewModel, matrizViewModel: MatrizViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rutaOrdenada by viewModel.rutaOrdenada.collectAsState()
    val criterios by viewModel.criterios.collectAsState()
    val procesando by viewModel.procesando.collectAsState()
    val progreso by viewModel.progreso.collectAsState()

    var fotosCapturadas by remember { mutableStateOf(listOf<Uri>()) }
    var mostrarPanelFotos by remember { mutableStateOf(false) }
    var mostrarFiltro by remember { mutableStateOf(false) }
    var fotoTemporalUri by remember { mutableStateOf<Uri?>(null) }
    var fotoAmpliada by remember { mutableStateOf<Uri?>(null) }
    var matrizAbierto by remember { mutableStateOf<MatrizEntity?>(null) }
    var matrizAEditar by remember { mutableStateOf<MatrizEntity?>(null) }
    var mostrarMapa by remember { mutableStateOf(false) }

    /** Misma acción que tocar una tarjeta en la lista, reutilizada por los marcadores del mapa:
     * si el cliente tuvo match en Matriz abre su detalle, si es nuevo solo avisa. */
    fun abrirEnMatriz(item: RutaIAEntity) {
        if (item.esNuevo || item.cuMatrizMatch == null) {
            Toast.makeText(context, "Cliente nuevo: todavía no existe en Matriz", Toast.LENGTH_SHORT).show()
        } else {
            scope.launch {
                val registro = viewModel.buscarMatrizPorId(item.cuMatrizMatch)
                if (registro != null) matrizAbierto = registro
                else Toast.makeText(context, "No se encontró el registro en Matriz", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val tomarFotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { exito ->
        if (exito && fotoTemporalUri != null) fotosCapturadas = fotosCapturadas + fotoTemporalUri!!
    }
    fun tomarOtraFoto() {
        val file = java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "ruta_ia_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "com.example.matrizapp.fileprovider", file)
        fotoTemporalUri = uri
        tomarFotoLauncher.launch(uri)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Barra superior: total de paradas + botones redondos de acción (filtro, mapa,
            // limpiar). Antes había también una línea fija con el resumen de criterios
            // ("Distancia (▲) › Días de atraso (▼)...") que ocupaba varias líneas y desordenaba
            // esta zona -- se quitó; el botón de filtro ya abre el panel completo con el estado
            // actual marcado, no hace falta repetirlo aquí fijo.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (rutaOrdenada.isEmpty()) "Sin ruta generada hoy" else "${rutaOrdenada.size} paradas",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledIconButton(
                        onClick = { mostrarFiltro = true },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) { Icon(Icons.Default.Tune, contentDescription = "Filtrar y ordenar ruta", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                    if (rutaOrdenada.any { it.lat != null && it.lng != null }) {
                        FilledIconButton(
                            onClick = { mostrarMapa = true },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) { Icon(Icons.Default.Map, contentDescription = "Ver mapa", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                    }
                    if (rutaOrdenada.isNotEmpty()) {
                        FilledIconButton(
                            onClick = { viewModel.limpiarRutaAhora() },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) { Icon(Icons.Default.DeleteSweep, contentDescription = "Limpiar ruta") }
                    }
                }
            }

            if (rutaOrdenada.isEmpty() && !procesando) {
                Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Route, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Toma 4-8 fotos de \"Clientes de cobranza\"\ny genera tu ruta del día", color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(rutaOrdenada, key = { _, item -> item.id }) { idx, item ->
                        RutaIAItemCard(
                            item = item, posicion = idx + 1,
                            onMarcarVisitado = {
                                viewModel.marcarVisitado(item.id)
                                Toast.makeText(context, "Marcado como visitado", Toast.LENGTH_SHORT).show()
                            },
                            onVerFoto = { item.fotoOrigenUrl?.let { fotoAmpliada = Uri.parse(it) } },
                            onAbrirEnMatriz = { abrirEnMatriz(item) }
                        )
                    }
                }
            }
        }

        if (procesando) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), Alignment.Center) {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(progreso.ifBlank { "Procesando..." })
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { mostrarPanelFotos = true; fotosCapturadas = emptyList() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Default.CameraAlt, contentDescription = "Tomar fotos y generar ruta") }
    }

    if (mostrarPanelFotos) {
        AlertDialog(
            onDismissRequest = { if (!procesando) mostrarPanelFotos = false },
            title = { Text("Fotos del día (${fotosCapturadas.size})") },
            text = {
                Column {
                    Text("Toma una foto por pantalla de \"Clientes de cobranza\" (2-3 clientes por foto). Recomendado: 4-8 fotos.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    if (fotosCapturadas.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(fotosCapturadas) { uri ->
                                coil.compose.AsyncImage(
                                    model = uri, contentDescription = null,
                                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedButton(onClick = { tomarOtraFoto() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.AddAPhoto, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Tomar foto")
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = fotosCapturadas.isNotEmpty(),
                    onClick = {
                        mostrarPanelFotos = false
                        viewModel.procesarFotos(fotosCapturadas) { exito, error ->
                            if (!exito) Toast.makeText(context, error ?: "No se pudo generar la ruta", Toast.LENGTH_LONG).show()
                        }
                    }
                ) { Text("Generar ruta") }
            },
            dismissButton = {
                TextButton(onClick = { mostrarPanelFotos = false }) { Text("Cancelar") }
            }
        )
    }

    if (mostrarFiltro) {
        RutaIAFiltroDialog(
            criteriosActuales = criterios,
            onDismiss = { mostrarFiltro = false },
            onAplicar = { nuevos -> viewModel.actualizarCriterios(nuevos); mostrarFiltro = false }
        )
    }

    // Foto de origen ampliada (la foto completa que se tomó esa mañana, no un recorte del
    // cliente individual -- ver nota en RutaIAItemCard sobre por qué no se recorta la carita).
    fotoAmpliada?.let { uri ->
        Dialog(onDismissRequest = { fotoAmpliada = null }) {
            Box(Modifier.clip(RoundedCornerShape(12.dp))) {
                coil.compose.AsyncImage(
                    model = uri, contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                )
            }
        }
    }

    // Registro existente en Matriz (solo llega aquí si el cliente sí tuvo match) -- se
    // reutilizan los mismos diálogos de detalle/edición que usa la pantalla de Matriz.
    matrizAbierto?.let { item ->
        MatrizDetailDialog(
            item = item,
            driveHelper = matrizViewModel.driveHelper,
            onDismiss = { matrizAbierto = null },
            onEditClick = { matrizAEditar = item; matrizAbierto = null }
        )
    }
    matrizAEditar?.let { item ->
        MatrizFullFormDialog(
            item = item,
            viewModel = matrizViewModel,
            onDismiss = { matrizAEditar = null },
            onSave = { idEditado, nombre, semana, requisito, numTT, ref1, ref2, observaciones, estado, ubicacion, fecha, hora, ruta, folioP ->
                matrizViewModel.cambiarIdYGuardar(
                    item.id, idEditado, nombre, semana, requisito, numTT, ref1, ref2,
                    observaciones, estado, ubicacion, fecha, hora, ruta, folioP
                ) { exito, error ->
                    if (!exito) Toast.makeText(context, error ?: "No se pudo guardar", Toast.LENGTH_LONG).show()
                }
                matrizAEditar = null
            }
        )
    }
    // Mapa a pantalla completa (botón redondo "Ver mapa" del encabezado) -- se abre encima de
    // todo lo demás con un Dialog sin restricción de ancho, para que ocupe toda la pantalla.
    if (mostrarMapa) {
        Dialog(onDismissRequest = { mostrarMapa = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            RutaIAMapaFullScreen(
                items = rutaOrdenada,
                onCerrar = { mostrarMapa = false },
                onMarcadorClick = { item -> abrirEnMatriz(item) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RutaIAItemCard(
    item: RutaIAEntity,
    posicion: Int,
    onMarcarVisitado: () -> Unit,
    onVerFoto: () -> Unit,
    onAbrirEnMatriz: () -> Unit
) {
    val context = LocalContext.current
    val visitado = item.estado.equals("Visitado", ignoreCase = true)
    Card(
        onClick = onAbrirEnMatriz,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (visitado) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                Alignment.Center
            ) { Text("$posicion", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            Spacer(Modifier.width(8.dp))
            // Miniatura de la foto de origen (la foto completa de esa mañana, no un recorte del
            // cliente): recortar la carita redonda de cada cliente no sale confiable porque las
            // fotos son de una pantalla física (reflejos, ángulo, movimiento) -- así al menos
            // Diego puede tocarla y ver de qué foto salió ese cliente si necesita revisar algo.
            if (item.fotoOrigenUrl != null) {
                coil.compose.AsyncImage(
                    model = Uri.parse(item.fotoOrigenUrl), contentDescription = "Foto de origen",
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onVerFoto),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.nombre, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (item.esNuevo) {
                        AssistChip(onClick = {}, label = { Text("Nuevo", style = MaterialTheme.typography.labelSmall) })
                    }
                }
                Text(item.direccion, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Row(Modifier.padding(top = 4.dp)) {
                    item.diasAtraso?.let { Text("Atraso: $it d.  ", style = MaterialTheme.typography.labelSmall) }
                    item.pagoRequerido?.let { Text("Pago: $${"%,.0f".format(it)}", style = MaterialTheme.typography.labelSmall) }
                }
                if (item.lat == null || item.lng == null) {
                    Text("Sin ubicar en el mapa", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                if (!item.esNuevo) {
                    Text("Toca la tarjeta para ver el registro en Matriz", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (item.lat != null && item.lng != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("geo:${item.lat},${item.lng}?q=${item.lat},${item.lng}(${android.net.Uri.encode(item.nombre)})")
                            )
                            try { context.startActivity(intent) } catch (e: Exception) { Toast.makeText(context, "No hay app de mapas instalada", Toast.LENGTH_SHORT).show() }
                        }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Map, contentDescription = "Ver en mapa") }
                        Text("Mapa", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = onMarcarVisitado, enabled = !visitado, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (visitado) Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline,
                            contentDescription = "Marcar como visitado",
                            tint = if (visitado) Color(0xFF4CAF50) else LocalContentColor.current
                        )
                    }
                    Text(if (visitado) "Visitado" else "Visitar", style = MaterialTheme.typography.labelSmall, color = if (visitado) Color(0xFF4CAF50) else Color.Gray)
                }
            }
        }
    }
}

/** Panel de filtro/orden combinable: cada criterio (Distancia, Días de atraso, Pago requerido)
 * se activa con un checkbox y, una vez activo, se le elige dirección (asc/desc). La prioridad
 * es el orden en que se van activando -- el primero que se marca manda, los siguientes solo
 * desempatan -- con flechas para reordenar manualmente. Misma idea que el filtro de la app de
 * trabajo de Banco Azteca (Distancia/Cercania, Dias de atraso, Pago requerido, Personalizado).
 *
 * El estado activo/inactivo se marca con relleno sólido vs. contorno (no con un tinte claro
 * apenas distinguible) para que sea obvio de un vistazo qué está prendido, tanto en el criterio
 * completo (tarjeta con fondo de color cuando está marcado) como en el botón de dirección
 * (Menor a mayor / Mayor a menor). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RutaIAFiltroDialog(
    criteriosActuales: List<CriterioOrdenRutaIA>,
    onDismiss: () -> Unit,
    onAplicar: (List<CriterioOrdenRutaIA>) -> Unit
) {
    var seleccion by remember { mutableStateOf(criteriosActuales) }

    fun estaActivo(campo: CampoOrdenRutaIA) = seleccion.any { it.campo == campo }
    fun direccionDe(campo: CampoOrdenRutaIA) = seleccion.find { it.campo == campo }?.direccion ?: DireccionOrdenRutaIA.ASC

    fun alternar(campo: CampoOrdenRutaIA) {
        seleccion = if (estaActivo(campo)) seleccion.filter { it.campo != campo }
        else seleccion + CriterioOrdenRutaIA(campo, DireccionOrdenRutaIA.ASC)
    }
    fun cambiarDireccion(campo: CampoOrdenRutaIA, dir: DireccionOrdenRutaIA) {
        seleccion = seleccion.map { if (it.campo == campo) it.copy(direccion = dir) else it }
    }
    fun mover(campo: CampoOrdenRutaIA, delta: Int) {
        val idx = seleccion.indexOfFirst { it.campo == campo }
        val nuevoIdx = idx + delta
        if (idx == -1 || nuevoIdx < 0 || nuevoIdx >= seleccion.size) return
        val lista = seleccion.toMutableList()
        val tmp = lista[idx]; lista[idx] = lista[nuevoIdx]; lista[nuevoIdx] = tmp
        seleccion = lista
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ordenar ruta por...") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Marca uno o varios criterios. El orden en que los marques define la prioridad (el primero manda, los demás solo desempatan).", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                CampoOrdenRutaIA.values().forEach { campo ->
                    val activo = estaActivo(campo)
                    val prioridad = seleccion.indexOfFirst { it.campo == campo } + 1
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (activo) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = if (activo) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        onClick = { alternar(campo) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (activo) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = null,
                                    tint = if (activo) MaterialTheme.colorScheme.primary else Color.Gray
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    campo.etiqueta, modifier = Modifier.weight(1f),
                                    fontWeight = if (activo) FontWeight.Bold else FontWeight.Normal
                                )
                                if (activo) {
                                    // Insignia de prioridad + flechas con área de toque grande y
                                    // fondo propio, para que se vean como controles y no como
                                    // texto suelto -- se detienen los clicks aquí para que no le
                                    // "peguen" también al toggle de toda la tarjeta.
                                    Box(
                                        Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                                        Alignment.Center
                                    ) { Text("$prioridad", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                                    Spacer(Modifier.width(4.dp))
                                    FilledIconButton(
                                        onClick = { mover(campo, -1) }, modifier = Modifier.size(32.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface)
                                    ) { Icon(Icons.Default.ArrowUpward, contentDescription = "Subir prioridad", modifier = Modifier.size(16.dp)) }
                                    Spacer(Modifier.width(4.dp))
                                    FilledIconButton(
                                        onClick = { mover(campo, 1) }, modifier = Modifier.size(32.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface)
                                    ) { Icon(Icons.Default.ArrowDownward, contentDescription = "Bajar prioridad", modifier = Modifier.size(16.dp)) }
                                }
                            }
                            if (activo) {
                                Spacer(Modifier.height(8.dp))
                                SegmentedToggleDireccion(
                                    seleccionAsc = direccionDe(campo) == DireccionOrdenRutaIA.ASC,
                                    etiquetaAsc = if (campo == CampoOrdenRutaIA.DISTANCIA) "Más cercano" else "Menor a mayor",
                                    etiquetaDesc = if (campo == CampoOrdenRutaIA.DISTANCIA) "Más lejano" else "Mayor a menor",
                                    onSeleccionar = { esAsc -> cambiarDireccion(campo, if (esAsc) DireccionOrdenRutaIA.ASC else DireccionOrdenRutaIA.DESC) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onAplicar(seleccion) }) { Text("Aplicar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** Toggle de dos opciones con contraste fuerte: la opción activa se pinta con relleno sólido y
 * texto blanco; la inactiva queda solo con contorno sobre fondo transparente. Nada de tintes
 * claros casi iguales entre sí -- de un vistazo se distingue cuál está prendida. Reutilizable
 * para cualquier par de opciones excluyentes en la app (no solo Ruta IA). */
@Composable
fun SegmentedToggleDireccion(
    seleccionAsc: Boolean,
    etiquetaAsc: String,
    etiquetaDesc: String,
    onSeleccionar: (esAsc: Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SegmentoToggleBoton(texto = etiquetaAsc, seleccionado = seleccionAsc, onClick = { onSeleccionar(true) }, modifier = Modifier.weight(1f))
        SegmentoToggleBoton(texto = etiquetaDesc, seleccionado = !seleccionAsc, onClick = { onSeleccionar(false) }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SegmentoToggleBoton(texto: String, seleccionado: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (seleccionado) {
        Button(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(vertical = 10.dp)) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(texto, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(vertical = 10.dp)) {
            Text(texto, style = MaterialTheme.typography.labelLarge, maxLines = 1, color = Color.Gray)
        }
    }
}
