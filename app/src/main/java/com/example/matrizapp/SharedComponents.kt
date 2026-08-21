package com.example.matrizapp
import android.location.Geocoder
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

val ESTADOS_MATRIZ = listOf("APP", "Retorno", "Pagado", "PASE", "FILTRAR", "Mano")

suspend fun obtenerUbicacionActual(context: android.content.Context): String? {
    return try {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val location = client.lastLocation.await() ?: return null
        "${location.latitude}, ${location.longitude}"
    } catch (e: SecurityException) { null } catch (e: Exception) { null }
}

fun getStatusColor(estado: String): Color {
    return when (estado.uppercase()) {
        "GESTIONADO", "ENTREGADO", "VISITADO" -> Color(0xFF4CAF50)
        "PENDIENTE" -> Color(0xFFF44336)
        "EN PROCESO" -> Color(0xFFFFC107)
        else -> Color.Gray
    }
}

/**
 * Fila de botones de acción rápida (llamar/SMS/WhatsApp/Maps), compartida entre
 * Matriz, Pase, Filtro Fecha y Solicitud. Solo se muestra el botón si el dato existe.
 */
@Composable
fun ContactActionsRow(numTT: String?, ref1: String? = null, ref2: String? = null, ubicacion: String? = null) {
    val context = LocalContext.current
    val iconSize = 34.dp
    val iconButtonModifier = Modifier.size(iconSize)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        if (!numTT.isNullOrBlank()) {
            IconButton(modifier = iconButtonModifier, onClick = {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$numTT")))
            }) { Icon(Icons.Default.Phone, contentDescription = "Llamar", tint = Color(0xFF1976D2)) }
            IconButton(modifier = iconButtonModifier, onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("sms:$numTT")))
            }) { Icon(Icons.Default.Sms, contentDescription = "SMS", tint = Color(0xFF00897B)) }
            IconButton(modifier = iconButtonModifier, onClick = {
                val url = "https://api.whatsapp.com/send?phone=$numTT"
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }) { Icon(Icons.Default.Chat, contentDescription = "WhatsApp", tint = Color(0xFF25D366)) }
        }
        if (!ref1.isNullOrBlank()) {
            IconButton(modifier = iconButtonModifier, onClick = {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$ref1")))
            }) { Icon(Icons.Default.Phone, contentDescription = "Llamar Ref 1", tint = Color(0xFF1976D2).copy(alpha = 0.6f)) }
            IconButton(modifier = iconButtonModifier, onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("sms:$ref1")))
            }) { Icon(Icons.Default.Sms, contentDescription = "SMS Ref 1", tint = Color(0xFF00897B).copy(alpha = 0.6f)) }
        }
        if (!ref2.isNullOrBlank()) {
            IconButton(modifier = iconButtonModifier, onClick = {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$ref2")))
            }) { Icon(Icons.Default.Phone, contentDescription = "Llamar Ref 2", tint = Color(0xFF1976D2).copy(alpha = 0.4f)) }
            IconButton(modifier = iconButtonModifier, onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("sms:$ref2")))
            }) { Icon(Icons.Default.Sms, contentDescription = "SMS Ref 2", tint = Color(0xFF00897B).copy(alpha = 0.4f)) }
        }
        if (!ubicacion.isNullOrBlank() && ubicacion != "N/A") {
            IconButton(modifier = iconButtonModifier, onClick = {
                val gmmIntentUri = Uri.parse("geo:0,0?q=${Uri.encode(ubicacion)}")
                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply { setPackage("com.google.android.apps.maps") }
                context.startActivity(mapIntent)
            }) { Icon(Icons.Default.Map, contentDescription = "Ver en Maps", tint = Color(0xFFE53935)) }
        }
    }
}

/**
 * Resuelve una referencia de archivo (URI local, URL de Drive, o ruta relativa tipo
 * "Solicitud_Images/archivo.jpg" que guarda el pipeline de OCR) a un Uri local
 * mostrable/adjuntable (descargando a caché y exponiendo vía FileProvider si hace falta).
 * Se usa tanto para compartir por WhatsApp como para la vista previa dentro de la app.
 */
suspend fun resolverArchivoComoUri(
    context: android.content.Context, driveHelper: DriveHelper, raw: String?, nombreDestino: String
): Uri? {
    if (raw.isNullOrBlank()) return null
    val cacheDir = java.io.File(context.cacheDir, "compartir").apply { mkdirs() }
    return try {
        when {
            raw.startsWith("content://") -> Uri.parse(raw)
            raw.startsWith("file://") -> {
                val f = java.io.File(Uri.parse(raw).path ?: return null)
                if (f.exists()) FileProvider.getUriForFile(context, "com.example.matrizapp.fileprovider", f) else null
            }
            raw.startsWith("http") -> {
                val destino = java.io.File(cacheDir, nombreDestino)
                if (driveHelper.downloadFile(raw, destino)) FileProvider.getUriForFile(context, "com.example.matrizapp.fileprovider", destino) else null
            }
            raw.contains("/") -> {
                // Ruta relativa tipo "Solicitud_Images/archivo.jpg" (sin URL, la guarda el OCR)
                val destino = java.io.File(cacheDir, nombreDestino)
                if (driveHelper.downloadByRelativePath(raw, destino)) FileProvider.getUriForFile(context, "com.example.matrizapp.fileprovider", destino) else null
            }
            else -> null
        }
    } catch (e: Exception) {
        // No dejar que un error de red/Drive al descargar tumbe silenciosamente todo el
        // compartir; quien llama decide qué hacer si devuelve null (avisar al usuario, etc).
        null
    }
}

/**
 * Comparte los datos de un registro de Solicitud por WhatsApp: texto (nombre, número,
 * ubicación, observaciones, estado) + adjunta imágenes/audio si son archivos locales
 * (content:// / file://). Si son URLs remotas (ya sincronizadas a Drive), se incluyen
 * como enlace dentro del texto porque WhatsApp no puede adjuntar una URL http directamente.
 */
/**
 * Devuelve el Uri del audio si el registro tiene uno y no se pudo mandar junto con las
 * fotos (WhatsApp descarta el mensaje si se mezclan fotos+audio en un solo envío con
 * ACTION_SEND_MULTIPLE con tipo comodín (imagen+audio mezclados), o null si no hay audio pendiente de compartir aparte.
 * Quien llama puede usar ese Uri para ofrecer un botón "Enviar audio" y mandarlo en un
 * segundo mensaje cuando el usuario quiera (nunca automático: dos startActivity seguidos
 * hacia WhatsApp hacen que el segundo reemplace la pantalla del primero antes de que el
 * usuario alcance a elegir el contacto, perdiendo el primer envío).
 */
suspend fun shareSolicitudPorWhatsApp(context: android.content.Context, item: SolicitudEntity, driveHelper: DriveHelper): Uri? {
    return try {
        shareSolicitudPorWhatsAppInterno(context, item, driveHelper)
    } catch (e: Exception) {
        Toast.makeText(context, "No se pudo compartir: ${e.message}", Toast.LENGTH_LONG).show()
        null
    }
}

private suspend fun shareSolicitudPorWhatsAppInterno(context: android.content.Context, item: SolicitudEntity, driveHelper: DriveHelper): Uri? {
    val textoBuilder = StringBuilder()
    textoBuilder.append("Solicitud: ${item.nombre}\n")
    if (!item.numero.isNullOrBlank()) textoBuilder.append("Número: ${item.numero}\n")
    if (!item.sucursal.isNullOrBlank()) textoBuilder.append("Sucursal: ${item.sucursal}\n")
    if (!item.estado.isBlank()) textoBuilder.append("Estado: ${item.estado}\n")
    if (!item.observaciones.isNullOrBlank()) textoBuilder.append("Observaciones: ${item.observaciones}\n")
    textoBuilder.append("Gestor asignado: ${item.gestorAsignado}\n")
    item.fechaHora?.let { millis ->
        val df = java.text.SimpleDateFormat("d/M/yyyy HH:mm", java.util.Locale("es", "MX"))
        textoBuilder.append("Fecha y hora: ${df.format(java.util.Date(millis))}\n")
    }
    if (!item.ubicacionRaw.isNullOrBlank() && item.ubicacionRaw != "N/A") {
        textoBuilder.append("Ubicación: https://maps.google.com/?q=${Uri.encode(item.ubicacionRaw)}\n")
    }

    val fallasAdjuntar = mutableListOf<String>()
    suspend fun resolverArchivo(raw: String?, nombreDestino: String, etiqueta: String): Uri? {
        if (raw.isNullOrBlank()) return null
        val uri = resolverArchivoComoUri(context, driveHelper, raw, nombreDestino)
        if (uri == null) fallasAdjuntar.add(etiqueta)
        return uri
    }

    val imagenUris = ArrayList<Uri>()
    resolverArchivo(item.imageUrl, "${item.id}_imagen1.jpg", "Foto 1")?.let { imagenUris.add(it) }
    resolverArchivo(item.imageUrl2, "${item.id}_imagen2.jpg", "Foto 2")?.let { imagenUris.add(it) }
    resolverArchivo(item.imageUrl3, "${item.id}_imagen3.jpg", "Foto 3")?.let { imagenUris.add(it) }
    resolverArchivo(item.imageUrl4, "${item.id}_imagen4.jpg", "Foto 4")?.let { imagenUris.add(it) }
    val audioUri = resolverArchivo(item.audioUrl, "${item.id}_audio.m4a", "Audio")

    fun enviar(uris: List<Uri>, mimeType: String, texto: String) {
        val intent = if (uris.isNotEmpty()) {
            Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                type = mimeType
                if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                else putExtra(Intent.EXTRA_STREAM, uris[0])
                putExtra(Intent.EXTRA_TEXT, texto)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                uris.forEach { context.grantUriPermission("com.whatsapp", it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, texto)
            }
        }
        try {
            intent.setPackage("com.whatsapp")
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                intent.setPackage(null)
                context.startActivity(Intent.createChooser(intent, "Compartir vía"))
            } catch (e2: Exception) {
                Toast.makeText(context, "No se pudo compartir: ${e2.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Texto + fotos van SIEMPRE juntos en un solo mensaje (funciona bien). El audio NO se
    // mete en ese mismo Intent: si se mandan fotos+audio mezclados en un ACTION_SEND_MULTIPLE
    // con tipo comodín (imagen+audio mezclados), WhatsApp descarta los adjuntos silenciosamente (solo llega el texto).
    // Si hay audio, se devuelve su Uri para que quien llama ofrezca un botón "Enviar audio"
    // y el usuario decida cuándo mandarlo como mensaje aparte.
    val mimeType = if (imagenUris.isEmpty()) "text/plain" else "image/*"
    enviar(imagenUris, mimeType, textoBuilder.toString())

    if (fallasAdjuntar.isNotEmpty()) {
        Toast.makeText(
            context,
            "No se pudo adjuntar: ${fallasAdjuntar.joinToString(", ")} (revisa tu conexión)",
            Toast.LENGTH_LONG
        ).show()
    }

    return audioUri
}

/** Manda el audio solo, como mensaje aparte (llamar cuando el usuario toque "Enviar audio"). */
fun compartirAudioSolicitudPorWhatsApp(context: android.content.Context, audioUri: Uri, nombre: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/mp4"
        putExtra(Intent.EXTRA_STREAM, audioUri)
        putExtra(Intent.EXTRA_TEXT, "Audio: $nombre")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setPackage("com.whatsapp")
    }
    try {
        context.grantUriPermission("com.whatsapp", audioUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            intent.setPackage(null)
            context.startActivity(Intent.createChooser(intent, "Compartir vía"))
        } catch (e2: Exception) {
            Toast.makeText(context, "No se pudo compartir el audio: ${e2.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun StatusBadge(estado: String) {
    val color = getStatusColor(estado)
    Surface(color = color.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, color.copy(alpha = 0.5f))) {
        Text(text = estado, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun EditMatrizDialog(item: MatrizEntity, onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
    var estado by remember { mutableStateOf(item.estado) }
    var observaciones by remember { mutableStateOf(item.observaciones ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Actualizar Gestión") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = estado, onValueChange = { estado = it }, label = { Text("Estado") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = observaciones, onValueChange = { observaciones = it }, label = { Text("Observaciones") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(item.id, estado, observaciones) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/**
 * Formulario completo de Matriz: sirve tanto para crear un registro nuevo (item = null)
 * como para editar todos los campos de uno existente.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatrizFullFormDialog(
    item: MatrizEntity?,
    viewModel: MatrizViewModel? = null,
    onDismiss: () -> Unit,
    onSave: (nombre: String, semana: String, requisito: String, numTT: String, ref1: String, ref2: String,
             observaciones: String, estado: String, ubicacion: String, fecha: Long, hora: String, ruta: String, folioP: String) -> Unit
) {
    val context = LocalContext.current
    val esNuevo = item == null

    var nombre by remember { mutableStateOf(item?.nombre ?: "") }
    var semana by remember { mutableStateOf(item?.semana ?: "") }
    var requisito by remember { mutableStateOf(item?.requisito ?: "") }
    var numTT by remember { mutableStateOf(item?.numTT ?: "") }
    var ref1 by remember { mutableStateOf(item?.ref1 ?: "") }
    var ref2 by remember { mutableStateOf(item?.ref2 ?: "") }
    var observaciones by remember { mutableStateOf(item?.observaciones ?: "") }
    var estado by remember { mutableStateOf(item?.estado ?: "") }
    var ubicacion by remember { mutableStateOf(item?.ubicacion ?: "") }
    // Fecha y Hora siempre inician en el momento actual (aunque se esté editando un
    // registro existente), pero el selector permite cambiarlas si se necesita otra fecha.
    var fechaMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var hora by remember {
        mutableStateOf(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(fechaMillis)))
    }
    var ruta by remember { mutableStateOf(item?.ruta ?: "") }
    var folioP by remember { mutableStateOf(item?.folioP ?: "") }
    var estadoMenuExpanded by remember { mutableStateOf(false) }
    var buscandoUbicacion by remember { mutableStateOf(esNuevo) }
    var activePhotoSlot by remember { mutableStateOf(1) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (item != null && viewModel != null) viewModel.onPhotoTaken(item.id, success)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (esNuevo) "Nuevo registro" else "Editar registro") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = nombre, onValueChange = { nombre = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = semana, onValueChange = { semana = it }, label = { Text("Sem") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = requisito, onValueChange = { requisito = it }, label = { Text("Req") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = numTT, onValueChange = { numTT = it }, label = { Text("Num TT") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = ref1, onValueChange = { ref1 = it }, label = { Text("Ref 1") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = ref2, onValueChange = { ref2 = it }, label = { Text("Ref 2") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = observaciones, onValueChange = { observaciones = it }, label = { Text("Observaciones") }, modifier = Modifier.fillMaxWidth(), minLines = 2)

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
                    value = ubicacion, onValueChange = { ubicacion = it }, label = { Text("Ubicación") },
                    trailingIcon = {
                        IconButton(onClick = {
                            buscandoUbicacion = true
                        }) { Icon(Icons.Default.MyLocation, contentDescription = "Ubicación actual") }
                    },
                    supportingText = { if (buscandoUbicacion) Text("Obteniendo ubicación…") },
                    modifier = Modifier.fillMaxWidth()
                )
                LaunchedEffect(buscandoUbicacion) {
                    if (buscandoUbicacion) {
                        ubicacion = obtenerUbicacionActual(context) ?: ubicacion
                        buscandoUbicacion = false
                    }
                }

                // Imagen e Imagen 2 van justo después de Ubicación, igual que en AppSheet.
                // Solo disponibles al editar un registro existente (se necesita su ID).
                Text("Imagen", style = MaterialTheme.typography.labelMedium)
                ImagenCaptureBox(
                    url = item?.imagenUrl,
                    enabled = item != null && viewModel != null,
                    onClick = {
                        activePhotoSlot = 1
                        val uri = viewModel!!.preparePhotoUri(context, 1)
                        takePictureLauncher.launch(uri)
                    }
                )
                Text("Imagen 2", style = MaterialTheme.typography.labelMedium)
                ImagenCaptureBox(
                    url = item?.imagenUrl2,
                    enabled = item != null && viewModel != null,
                    onClick = {
                        activePhotoSlot = 2
                        val uri = viewModel!!.preparePhotoUri(context, 2)
                        takePictureLauncher.launch(uri)
                    }
                )
                if (esNuevo) {
                    Text("Guarda el registro primero para poder agregar fotos.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }

                val fechaFormateada = remember(fechaMillis) {
                    SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(java.util.Date(fechaMillis))
                }
                OutlinedTextField(
                    value = fechaFormateada, onValueChange = {}, readOnly = true,
                    label = { Text("Fecha y Hora") },
                    trailingIcon = {
                        IconButton(onClick = {
                            val cal = java.util.Calendar.getInstance().apply { timeInMillis = fechaMillis }
                            android.app.DatePickerDialog(context, { _, y, m, d ->
                                android.app.TimePickerDialog(context, { _, h, min ->
                                    cal.set(y, m, d, h, min, 0)
                                    fechaMillis = cal.timeInMillis
                                    hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(cal.time)
                                }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
                            }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
                        }) { Icon(Icons.Default.CalendarMonth, contentDescription = "Elegir fecha y hora") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Ruta", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = ruta == "Cartucho 1", onClick = { ruta = "Cartucho 1" }, label = { Text("Cartucho 1") })
                    FilterChip(selected = ruta == "Cartucho 2", onClick = { ruta = "Cartucho 2" }, label = { Text("Cartucho 2") })
                }
                OutlinedTextField(value = folioP, onValueChange = { folioP = it }, label = { Text("CU") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(nombre, semana, requisito, numTT, ref1, ref2, observaciones, estado, ubicacion, fechaMillis, hora, ruta, folioP)
                },
                enabled = nombre.isNotBlank()
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
fun ImagenCaptureBox(url: String?, enabled: Boolean, onClick: () -> Unit) {
    var mostrarGrande by remember(url) { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f)),
        color = Color.Transparent
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (!url.isNullOrBlank()) {
                AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                // Icono de lupa aparte del área principal (que sigue abriendo la cámara para
                // reemplazar la foto): permite ver la imagen actual en grande sin tomar una nueva.
                IconButton(
                    onClick = { mostrarGrande = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(28.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                ) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Ver en grande", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            } else {
                Icon(
                    Icons.Default.PhotoCamera, contentDescription = "Tomar foto",
                    tint = if (enabled) Color.Gray else Color.LightGray
                )
            }
        }
    }
    if (mostrarGrande && !url.isNullOrBlank()) {
        ImageDetailDialog(url = url, onDismiss = { mostrarGrande = false })
    }
}

/**
 * Botón para mandar el audio del registro por WhatsApp directamente, sin depender de haber
 * tocado antes "Compartir por WhatsApp" (que manda fotos+texto y omite el audio porque
 * WhatsApp descarta el mensaje si se mezclan). Va junto al botón de reproducir, siempre
 * visible mientras haya un audio guardado, para poder mandarlo en cualquier momento.
 */
@Composable
fun EnviarAudioIconButton(rawAudioUrl: String, nombre: String, driveHelper: DriveHelper) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enviando by remember { mutableStateOf(false) }
    if (enviando) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
    } else {
        IconButton(onClick = {
            enviando = true
            scope.launch {
                val uri = resolverArchivoComoUri(context, driveHelper, rawAudioUrl, "audio_whatsapp_${System.currentTimeMillis()}.m4a")
                enviando = false
                if (uri != null) compartirAudioSolicitudPorWhatsApp(context, uri, nombre)
                else Toast.makeText(context, "No se pudo preparar el audio para enviar", Toast.LENGTH_SHORT).show()
            }
        }) {
            Icon(Icons.Default.Share, contentDescription = "Enviar audio por WhatsApp", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun AudioPlayerControl(audioUrl: String) {
    var isPlaying by remember { mutableStateOf(false) }
    val mediaPlayer = remember { MediaPlayer() }
    val context = LocalContext.current
    DisposableEffect(Unit) { onDispose { mediaPlayer.release() } }
    IconButton(onClick = {
        try {
            if (isPlaying) { mediaPlayer.stop(); mediaPlayer.reset(); isPlaying = false }
            else {
                mediaPlayer.apply {
                    reset()
                    setDataSource(context, Uri.parse(audioUrl))
                    prepareAsync()
                    setOnPreparedListener { start(); isPlaying = true }
                    setOnCompletionListener { isPlaying = false }
                    setOnErrorListener { _, _, _ -> isPlaying = false; true }
                }
            }
        } catch (e: Exception) { Toast.makeText(context, "Error de audio: ${e.message}", Toast.LENGTH_SHORT).show() }
    }) {
        Icon(if (isPlaying) Icons.Default.StopCircle else Icons.Default.PlayArrow, contentDescription = null, tint = if (isPlaying) Color.Red else MaterialTheme.colorScheme.primary)
    }
}

/**
 * Botón de reproducir audio que primero RESUELVE la referencia (link de Drive o ruta
 * relativa del OCR) a un archivo local descargado, igual que se hace para ver fotos —
 * un link de Drive (webViewLink) no es un stream de audio reproducible directamente.
 * Local (content:///file://) se reproduce de inmediato sin descarga.
 */
@Composable
fun ResolvedAudioPlayerButton(rawAudioUrl: String, driveHelper: DriveHelper) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var resolviendo by remember { mutableStateOf(false) }
    var uriResuelta by remember(rawAudioUrl) {
        mutableStateOf(if (rawAudioUrl.startsWith("content://") || rawAudioUrl.startsWith("file://")) rawAudioUrl else null)
    }
    if (uriResuelta != null) {
        AudioPlayerControl(audioUrl = uriResuelta!!)
    } else if (resolviendo) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
    } else {
        IconButton(onClick = {
            resolviendo = true
            scope.launch {
                val uri = resolverArchivoComoUri(context, driveHelper, rawAudioUrl, "audio_preview_${System.currentTimeMillis()}.m4a")
                resolviendo = false
                if (uri != null) uriResuelta = uri.toString()
                else Toast.makeText(context, "No se pudo cargar el audio", Toast.LENGTH_SHORT).show()
            }
        }) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Cargar audio", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun ImageDetailDialog(url: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(contentAlignment = Alignment.Center) {
                AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                }
            }
        }
    }
}

/** Miniatura ("portada") con la primera imagen del registro, usada en Filtro Fecha y Semana 6
 * (mismo estilo de vista que tenía AppSheet). Resuelve tanto links de Drive (webViewLink) como
 * rutas relativas del pipeline de OCR, descargándolas a caché local la primera vez que la
 * tarjeta se muestra. */
@Composable
fun PortadaThumbnail(rawImageUrl: String?, driveHelper: DriveHelper, size: androidx.compose.ui.unit.Dp = 56.dp) {
    val context = LocalContext.current
    var uriResuelta by remember(rawImageUrl) { mutableStateOf<String?>(null) }
    var fallo by remember(rawImageUrl) { mutableStateOf(false) }
    var mostrarGrande by remember(rawImageUrl) { mutableStateOf(false) }

    LaunchedEffect(rawImageUrl) {
        if (!rawImageUrl.isNullOrBlank()) {
            val uri = resolverArchivoComoUri(context, driveHelper, rawImageUrl, "portada_${rawImageUrl.hashCode()}.jpg")
            if (uri != null) uriResuelta = uri.toString() else fallo = true
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFE0E0E0))
            .then(
                if (uriResuelta != null) Modifier.clickable { mostrarGrande = true } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            uriResuelta != null -> AsyncImage(model = uriResuelta, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            rawImageUrl.isNullOrBlank() || fallo -> Icon(Icons.Default.Image, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(24.dp))
            else -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }

    if (mostrarGrande && uriResuelta != null) {
        ImageDetailDialog(url = uriResuelta!!, onDismiss = { mostrarGrande = false })
    }
}

/**
 * Convierte una "Ubicación" tipo "19.371516, -99.104376" en el nombre de colonia/zona más
 * cercano, usando el Geocoder del propio dispositivo (sin costo de API, a diferencia del
 * geocoder de Apps Script que sí gasta cuota de Maps). Si no hay red, el geocoder de Android
 * no está disponible en el dispositivo, o el texto no trae coordenadas válidas, regresa null
 * en silencio: la Colonia es un dato "de más", nunca debe tumbar la pantalla.
 */
suspend fun resolverColoniaYCalle(context: android.content.Context, ubicacion: String?): Pair<String?, String?> = withContext(Dispatchers.IO) {
    if (ubicacion.isNullOrBlank()) return@withContext null to null
    try {
        val partes = ubicacion.replace('−', '-').replace('–', '-').split(",").map { it.trim() }
        if (partes.size < 2) return@withContext null to null
        val lat = partes[0].toDoubleOrNull() ?: return@withContext null to null
        val lng = partes[1].toDoubleOrNull() ?: return@withContext null to null
        if (!Geocoder.isPresent()) return@withContext null to null
        val geocoder = Geocoder(context, Locale("es", "MX"))
        @Suppress("DEPRECATION")
        val resultados = geocoder.getFromLocation(lat, lng, 1)
        val direccion = resultados?.firstOrNull() ?: return@withContext null to null
        val colonia = direccion.subLocality ?: direccion.locality
        val calle = direccion.thoroughfare?.let { calleBase ->
            direccion.subThoroughfare?.let { numero -> "$calleBase $numero" } ?: calleBase
        }
        colonia to calle
    } catch (e: Exception) {
        null to null
    }
}

suspend fun resolverColonia(context: android.content.Context, ubicacion: String?): String? {
    val (colonia, calle) = resolverColoniaYCalle(context, ubicacion)
    return colonia ?: calle
}

/** Muestra la Colonia y Calle calculadas a partir de coordenadas (columna Ubicación) cuando la hoja
 * de origen no trae esos datos directamente, como es el caso de Filtro Fecha. */
@Composable
fun ColoniaLabel(ubicacion: String?, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall) {
    val context = LocalContext.current
    var colonia by remember(ubicacion) { mutableStateOf<String?>(null) }
    var calle by remember(ubicacion) { mutableStateOf<String?>(null) }
    LaunchedEffect(ubicacion) {
        val (c, cl) = resolverColoniaYCalle(context, ubicacion)
        colonia = c
        calle = cl
    }
    colonia?.let { Text("Colonia: $it", style = style, color = Color.Gray) }
    calle?.let { Text("Calle: $it", style = style, color = Color.Gray) }
}

/** Muestra solo la Calle calculada por geocoding inverso, para pantallas como Semana 6
 * que ya traen su propia Colonia directo de la hoja (columna G de Cont-Sem-NN). */
@Composable
fun CalleLabel(ubicacion: String?, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall) {
    val context = LocalContext.current
    var calle by remember(ubicacion) { mutableStateOf<String?>(null) }
    LaunchedEffect(ubicacion) {
        val (_, cl) = resolverColoniaYCalle(context, ubicacion)
        calle = cl
    }
    calle?.let { Text("Calle: $it", style = style, color = Color.Gray) }
}