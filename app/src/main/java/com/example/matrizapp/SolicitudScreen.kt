package com.example.matrizapp
import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolicitudScreen(viewModel: SolicitudViewModel, searchQuery: String = "") {
    val allItems by viewModel.solicitudList.collectAsState()
    val items = remember(allItems, searchQuery) {
        if (searchQuery.isBlank()) allItems else allItems.filter { item ->
            val q = searchQuery.trim()
            item.nombre.contains(q, ignoreCase = true) ||
                item.estado.contains(q, ignoreCase = true) ||
                item.numero?.contains(q, ignoreCase = true) == true ||
                item.sucursal?.contains(q, ignoreCase = true) == true ||
                item.ubicacionRaw?.contains(q, ignoreCase = true) == true
        }
    }
    val isRecording by viewModel.isRecording.collectAsState()
    val context = LocalContext.current
    var activeIdForAudio by remember { mutableStateOf("") }
    var fullScreenImageUrl by remember { mutableStateOf<String?>(null) }
    var cargandoImagen by remember { mutableStateOf(false) }
    var itemToEditId by remember { mutableStateOf<String?>(null) }
    // Se deriva del listado reactivo (no una copia congelada) para que reflejar cambios
    // hechos DENTRO de la misma sesión del diálogo (ej. grabar audio y luego compartir)
    // siempre use el dato más reciente, en vez de lo que había al abrir el diálogo.
    val itemToEdit = itemToEditId?.let { id -> items.find { it.id == id } }
    var showCreateDialog by remember { mutableStateOf(false) }
    // Cuando compartir un registro con audio, el audio se manda como mensaje aparte (ver
    // comentario en shareSolicitudPorWhatsApp). Este estado guarda el audio pendiente para
    // ofrecer un botón "Enviar audio" que el usuario toca cuando ya terminó de compartir
    // las fotos/texto, en vez de mandarlo automático (eso perdía el primer mensaje).
    var audioPendiente by remember { mutableStateOf<Pair<android.net.Uri, String>?>(null) }

    fun compartir(item: SolicitudEntity) {
        viewModel.compartirPorWhatsApp(context, item) { uri, nombre -> audioPendiente = uri to nombre }
    }

    fun abrirImagen(raw: String) {
        cargandoImagen = true
        viewModel.resolverImagenParaVista(context, raw) { uri ->
            cargandoImagen = false
            if (uri != null) fullScreenImageUrl = uri
            else Toast.makeText(context, "No se pudo cargar la imagen", Toast.LENGTH_SHORT).show()
        }
    }

    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (it) viewModel.startRecording() else Toast.makeText(context, "Permiso denegado", Toast.LENGTH_SHORT).show() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.id }) { item ->
                    SolicitudItemCard(
                        item = item,
                        onCardClick = { itemToEditId = item.id },
                        onShareWhatsApp = { compartir(item) }
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { showCreateDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Default.Add, contentDescription = "Nuevo registro") }

        if (cargandoImagen) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        fullScreenImageUrl?.let { url ->
            ImageDetailDialog(url = url, onDismiss = { fullScreenImageUrl = null })
        }

        audioPendiente?.let { (uri, nombre) ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = {
                    TextButton(onClick = {
                        compartirAudioSolicitudPorWhatsApp(context, uri, nombre)
                        audioPendiente = null
                    }) { Text("Enviar audio") }
                },
                dismissAction = {
                    IconButton(onClick = { audioPendiente = null }) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }
            ) { Text("Enviado. El audio se manda aparte.") }
        }
    }

    itemToEdit?.let { item ->
        SolicitudFullFormDialog(
            item = item,
            driveHelper = viewModel.driveHelper,
            viewModel = viewModel,
            isRecording = isRecording && activeIdForAudio == item.id,
            onDismiss = { itemToEditId = null },
            onAudioToggle = {
                activeIdForAudio = item.id
                if (isRecording) viewModel.stopRecordingAndSave(item.id)
                else audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onViewImage = { abrirImagen(it) },
            onShareWhatsApp = { compartir(item) },
            audioPendiente = audioPendiente,
            onEnviarAudio = {
                audioPendiente?.let { (uri, nombre) -> compartirAudioSolicitudPorWhatsApp(context, uri, nombre) }
                audioPendiente = null
            },
            onSave = { nombre, numero, sucursal, ubicacion, nombreRef1, ref1, nombreRef2, ref2, observaciones, estado, _, _, _, _ ->
                viewModel.guardarRegistroCompleto(item.id, nombre, numero, sucursal, ubicacion, nombreRef1, ref1, nombreRef2, ref2, observaciones, estado)
                itemToEditId = null
            }
        )
    }

    if (showCreateDialog) {
        var nuevoAudioPath by remember { mutableStateOf<String?>(null) }
        SolicitudFullFormDialog(
            item = null,
            driveHelper = viewModel.driveHelper,
            viewModel = viewModel,
            audioUrlOverride = nuevoAudioPath,
            isRecording = isRecording,
            audioGrabadoLocal = nuevoAudioPath != null,
            onDismiss = { showCreateDialog = false },
            onAudioToggle = {
                if (isRecording) {
                    nuevoAudioPath = viewModel.stopRecordingLocalOnly()
                } else {
                    audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onViewImage = {},
            onShareWhatsApp = { Toast.makeText(context, "Guarda el registro primero para poder compartir", Toast.LENGTH_SHORT).show() },
            onSave = { nombre, numero, sucursal, ubicacion, nombreRef1, ref1, nombreRef2, ref2, observaciones, estado, nuevaImagenUrl, nuevaImagenUrl2, nuevaImagenUrl3, nuevaImagenUrl4 ->
                viewModel.crearRegistro(nombre, numero, sucursal, ubicacion, nombreRef1, ref1, nombreRef2, ref2, observaciones, estado, nuevoAudioPath, nuevaImagenUrl, nuevaImagenUrl2, nuevaImagenUrl3, nuevaImagenUrl4)
                showCreateDialog = false
            }
        )
    }
}

/**
 * Tarjeta de lista de Solicitud: igual estilo que Matriz — botones de llamado a la
 * acción (llamar/SMS/WhatsApp/Maps) visibles solo si hay datos, y al tocar la tarjeta
 * se abre el formulario completo con acceso a todos los datos y edición.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolicitudItemCard(
    item: SolicitudEntity,
    onCardClick: () -> Unit,
    onShareWhatsApp: () -> Unit
) {
    Card(onClick = onCardClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = item.nombre, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusBadge(estado = item.estado)
            }
            Text(
                text = item.observaciones?.takeIf { it.isNotBlank() } ?: "Sin observaciones",
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis
            )

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    ContactActionsRow(numTT = item.numero, ref1 = item.ref1, ref2 = item.ref2, ubicacion = item.ubicacionRaw)
                }
                if (!item.audioUrl.isNullOrBlank()) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp).padding(horizontal = 2.dp))
                }
                if (item.isDirty) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color.Red, modifier = Modifier.size(18.dp).padding(horizontal = 2.dp))
                }
                IconButton(onClick = onShareWhatsApp) {
                    Icon(Icons.Default.Share, contentDescription = "Compartir por WhatsApp", tint = Color(0xFF25D366))
                }
            }
        }
    }
}

/**
 * Formulario completo de Solicitud: sirve tanto para crear un registro nuevo (item = null,
 * como en Matriz) como para editar todos los campos de uno existente — nombre, número,
 * sucursal, ubicación, referencias, estado, observaciones, imágenes y audio.
 * El botón de grabar audio va justo después de Observaciones, y hay un botón para
 * compartir ubicación/fotos/audio por WhatsApp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolicitudFullFormDialog(
    item: SolicitudEntity?,
    driveHelper: DriveHelper,
    viewModel: SolicitudViewModel? = null,
    isRecording: Boolean,
    audioGrabadoLocal: Boolean = false,
    audioUrlOverride: String? = null,
    onDismiss: () -> Unit,
    onAudioToggle: () -> Unit,
    onViewImage: (String) -> Unit,
    onShareWhatsApp: () -> Unit,
    audioPendiente: Pair<android.net.Uri, String>? = null,
    onEnviarAudio: () -> Unit = {},
    onSave: (nombre: String, numero: String, sucursal: String, ubicacion: String,
             nombreRef1: String, ref1: String, nombreRef2: String, ref2: String,
             observaciones: String, estado: String, nuevaImagenUrl: String?, nuevaImagenUrl2: String?,
             nuevaImagenUrl3: String?, nuevaImagenUrl4: String?) -> Unit
) {
    val context = LocalContext.current
    val esNuevo = item == null
    var nombre by remember { mutableStateOf(item?.nombre ?: "") }
    var numero by remember { mutableStateOf(item?.numero ?: "") }
    var sucursal by remember { mutableStateOf(item?.sucursal ?: "") }
    var ubicacion by remember { mutableStateOf(item?.ubicacionRaw ?: "") }
    var nombreRef1 by remember { mutableStateOf(item?.nombreRef1 ?: "") }
    var ref1 by remember { mutableStateOf(item?.ref1 ?: "") }
    var nombreRef2 by remember { mutableStateOf(item?.nombreRef2 ?: "") }
    var ref2 by remember { mutableStateOf(item?.ref2 ?: "") }
    var observaciones by remember { mutableStateOf(item?.observaciones ?: "") }
    var estado by remember { mutableStateOf(item?.estado ?: "") }
    var estadoMenuExpanded by remember { mutableStateOf(false) }
    val opciones = listOf("PENDIENTE", "GESTIONADO", "ENTREGADO", "VISITADO", "EN PROCESO")

    // Ubicación con un clic: toma las coordenadas del GPS directamente, sin escribir nada.
    var buscandoUbicacion by remember { mutableStateOf(false) }
    LaunchedEffect(buscandoUbicacion) {
        if (buscandoUbicacion) {
            ubicacion = obtenerUbicacionActual(context) ?: ubicacion
            buscandoUbicacion = false
        }
    }

    // Fotos: dos slots (Imagen / Imagen 2), cada uno con opción de Cámara o Galería.
    // Para un registro NUEVO (esNuevo=true, item=null) las fotos se guardan localmente en
    // estas variables y se mandan al crear el registro; para uno existente se escriben
    // directo a la base local vía el ViewModel.
    var nuevaImagenUrl by remember { mutableStateOf<String?>(null) }
    var nuevaImagenUrl2 by remember { mutableStateOf<String?>(null) }
    var nuevaImagenUrl3 by remember { mutableStateOf<String?>(null) }
    var nuevaImagenUrl4 by remember { mutableStateOf<String?>(null) }
    var slotActivo by remember { mutableStateOf(1) }
    var mostrarSelectorFoto by remember { mutableStateOf(0) } // 0=no, 1=slot1, 2=slot2, 3=slot3, 4=slot4

    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (esNuevo) {
            val uri = viewModel?.onPhotoTakenLocalOnly(success)
            if (uri != null) {
                when (slotActivo) {
                    2 -> nuevaImagenUrl2 = uri
                    3 -> nuevaImagenUrl3 = uri
                    4 -> nuevaImagenUrl4 = uri
                    else -> nuevaImagenUrl = uri
                }
            }
        } else if (item != null) {
            viewModel?.onPhotoTaken(item.id, success)
        }
    }
    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (esNuevo) {
            viewModel?.onImagePickedFromGalleryLocalOnly(context, uri) { local ->
                if (local != null) {
                    when (slotActivo) {
                        2 -> nuevaImagenUrl2 = local
                        3 -> nuevaImagenUrl3 = local
                        4 -> nuevaImagenUrl4 = local
                        else -> nuevaImagenUrl = local
                    }
                }
            }
        } else if (item != null) {
            viewModel?.onImagePickedFromGallery(context, item.id, slotActivo, uri)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (esNuevo) "Nueva solicitud" else "Solicitud: ${item!!.nombre}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = nombre, onValueChange = { nombre = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = numero, onValueChange = { numero = it }, label = { Text("Número") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = sucursal, onValueChange = { sucursal = it }, label = { Text("Sucursal") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = ubicacion, onValueChange = { ubicacion = it }, label = { Text("Ubicación") },
                    trailingIcon = {
                        IconButton(onClick = { buscandoUbicacion = true }) {
                            Icon(Icons.Default.MyLocation, contentDescription = "Usar ubicación actual")
                        }
                    },
                    supportingText = { if (buscandoUbicacion) Text("Obteniendo ubicación…") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Imagen", style = MaterialTheme.typography.labelMedium)
                ImagenCaptureBox(
                    url = nuevaImagenUrl ?: item?.imageUrl,
                    enabled = true,
                    onClick = { slotActivo = 1; mostrarSelectorFoto = 1 }
                )
                Text("Imagen 2", style = MaterialTheme.typography.labelMedium)
                ImagenCaptureBox(
                    url = nuevaImagenUrl2 ?: item?.imageUrl2,
                    enabled = true,
                    onClick = { slotActivo = 2; mostrarSelectorFoto = 2 }
                )
                Text("Imagen 3", style = MaterialTheme.typography.labelMedium)
                ImagenCaptureBox(
                    url = nuevaImagenUrl3 ?: item?.imageUrl3,
                    enabled = true,
                    onClick = { slotActivo = 3; mostrarSelectorFoto = 3 }
                )
                Text("Imagen 4", style = MaterialTheme.typography.labelMedium)
                ImagenCaptureBox(
                    url = nuevaImagenUrl4 ?: item?.imageUrl4,
                    enabled = true,
                    onClick = { slotActivo = 4; mostrarSelectorFoto = 4 }
                )

                Text("Referencia 1", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(value = nombreRef1, onValueChange = { nombreRef1 = it }, label = { Text("Nombre Ref 1") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = ref1, onValueChange = { ref1 = it }, label = { Text("Teléfono Ref 1") }, modifier = Modifier.fillMaxWidth())
                Text("Referencia 2", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(value = nombreRef2, onValueChange = { nombreRef2 = it }, label = { Text("Nombre Ref 2") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = ref2, onValueChange = { ref2 = it }, label = { Text("Teléfono Ref 2") }, modifier = Modifier.fillMaxWidth())

                OutlinedTextField(
                    value = observaciones, onValueChange = { observaciones = it }, label = { Text("Observaciones") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2
                )
                val audioActual = audioUrlOverride ?: item?.audioUrl
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onAudioToggle) {
                        Icon(
                            if (isRecording) Icons.Default.StopCircle else Icons.Default.Mic,
                            contentDescription = "Grabar audio",
                            tint = if (isRecording) Color.Red else if (!audioActual.isNullOrBlank()) Color(0xFF4CAF50) else Color.Gray
                        )
                    }
                    Text(if (isRecording) "Grabando…" else if (!audioActual.isNullOrBlank()) "Audio guardado" else "Grabar audio")
                    if (!audioActual.isNullOrBlank()) {
                        ResolvedAudioPlayerButton(rawAudioUrl = audioActual, driveHelper = driveHelper)
                        EnviarAudioIconButton(rawAudioUrl = audioActual, nombre = item?.nombre ?: nombre, driveHelper = driveHelper)
                    }
                }

                ExposedDropdownMenuBox(expanded = estadoMenuExpanded, onExpandedChange = { estadoMenuExpanded = it }) {
                    OutlinedTextField(
                        value = estado, onValueChange = {}, readOnly = true, label = { Text("Estado") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(estadoMenuExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = getStatusColor(estado))
                    )
                    ExposedDropdownMenu(expanded = estadoMenuExpanded, onDismissRequest = { estadoMenuExpanded = false }) {
                        DropdownMenuItem(text = { Text("(Sin status)") }, onClick = { estado = ""; estadoMenuExpanded = false })
                        opciones.forEach { opcion ->
                            DropdownMenuItem(text = { Text(opcion) }, onClick = { estado = opcion; estadoMenuExpanded = false })
                        }
                    }
                }

                if (item != null && (!item.imageUrl.isNullOrBlank() || !item.imageUrl2.isNullOrBlank() || !item.imageUrl3.isNullOrBlank() || !item.imageUrl4.isNullOrBlank())) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        item.imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                            TextButton(onClick = { onViewImage(url) }) { Text("Ver imagen 1 en grande") }
                        }
                        item.imageUrl2?.takeIf { it.isNotBlank() }?.let { url ->
                            TextButton(onClick = { onViewImage(url) }) { Text("Ver imagen 2 en grande") }
                        }
                        item.imageUrl3?.takeIf { it.isNotBlank() }?.let { url ->
                            TextButton(onClick = { onViewImage(url) }) { Text("Ver imagen 3 en grande") }
                        }
                        item.imageUrl4?.takeIf { it.isNotBlank() }?.let { url ->
                            TextButton(onClick = { onViewImage(url) }) { Text("Ver imagen 4 en grande") }
                        }
                    }
                }
                if (esNuevo) {
                    Text("Podrás compartir por WhatsApp una vez que guardes el registro.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ContactActionsRow(numTT = numero, ref1 = ref1, ref2 = ref2, ubicacion = ubicacion)
                }
                if (!esNuevo) {
                    Button(onClick = onShareWhatsApp, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Compartir por WhatsApp")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(nombre, numero, sucursal, ubicacion, nombreRef1, ref1, nombreRef2, ref2, observaciones, estado, nuevaImagenUrl, nuevaImagenUrl2, nuevaImagenUrl3, nuevaImagenUrl4) }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )

    if (mostrarSelectorFoto != 0) {
        AlertDialog(
            onDismissRequest = { mostrarSelectorFoto = 0 },
            title = { Text("Agregar foto") },
            text = { Text("¿Tomar una foto nueva o elegir una de la galería?") },
            confirmButton = {
                TextButton(onClick = {
                    mostrarSelectorFoto = 0
                    val uri = viewModel?.preparePhotoUri(context, slotActivo) ?: return@TextButton
                    takePictureLauncher.launch(uri)
                }) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Cámara")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    mostrarSelectorFoto = 0
                    pickImageLauncher.launch("image/*")
                }) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Galería")
                }
            }
        )
    }
}
