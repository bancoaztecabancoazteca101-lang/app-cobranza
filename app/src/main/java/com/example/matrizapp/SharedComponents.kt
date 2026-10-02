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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import kotlinx.coroutines.suspendCancellableCoroutine

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
 * Una fila "Etiqueta: número" con los botones de Llamar/SMS pegados a la derecha, igual
 * que se ve en AppSheet (a diferencia de ContactActionsRow, que amontona todos los
 * contactos en una sola fila horizontal al final). Se usa en las vistas rápidas de
 * detalle de Matriz y Filtro Fecha, una fila por cada campo de contacto (NumTT, Ref1, Ref2).
 */
@Composable
fun ContactFieldRow(label: String, numero: String?) {
    if (numero.isNullOrBlank()) return
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$label: $numero", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(modifier = Modifier.size(34.dp), onClick = {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$numero")))
            }) { Icon(Icons.Default.Phone, contentDescription = "Llamar $label", tint = Color(0xFF1976D2)) }
            IconButton(modifier = Modifier.size(34.dp), onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("sms:$numero")))
            }) { Icon(Icons.Default.Sms, contentDescription = "SMS $label", tint = Color(0xFF00897B)) }
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
    textoBuilder.append("*Solicitud:* ${item.nombre}\n")
    if (!item.numero.isNullOrBlank()) textoBuilder.append("*Número:* ${item.numero}\n")
    if (!item.sucursal.isNullOrBlank()) textoBuilder.append("*Sucursal:* ${item.sucursal}\n")
    if (!item.estado.isBlank()) textoBuilder.append("*Estado:* ${item.estado}\n")
    if (!item.observaciones.isNullOrBlank()) textoBuilder.append("*Observaciones:* ${item.observaciones}\n")
    textoBuilder.append("*Gestor asignado:* ${item.gestorAsignado}\n")
    item.fechaHora?.let { millis ->
        val df = java.text.SimpleDateFormat("d/M/yyyy HH:mm", java.util.Locale("es", "MX"))
        textoBuilder.append("*Fecha y hora:* ${df.format(java.util.Date(millis))}\n")
    }
    if (!item.ubicacionRaw.isNullOrBlank() && item.ubicacionRaw != "N/A") {
        textoBuilder.append("*Ubicación:* https://maps.google.com/?q=${Uri.encode(item.ubicacionRaw)}\n")
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
    if (estado.isBlank()) return
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
    onSave: (id: String, nombre: String, semana: String, requisito: String, numTT: String, ref1: String, ref2: String,
             observaciones: String, estado: String, ubicacion: String, fecha: Long, hora: String, ruta: String, folioP: String,
             descuentoPago: String, descuentoAhorro: String,
             ref3: String, ref4: String, diaPago: String, domicilioLaboral: String,
             diasAtraso: String, diasApertura: String) -> Unit,
    // Ref 3/Ref 4/Día de pago/Domicilio Laboral solo existen en matriz_table (no en Pase): el
    // alta de Pase pasa false para no mostrar campos que luego no se guardarían en ningún lado.
    mostrarCamposExtra: Boolean = true,
    // Solo para registro nuevo (item == null): valores iniciales de los campos (lo usa Ruta IA para
    // registrar como cliente nuevo una parada que no está en Matriz).
    prefill: MatrizEntity? = null
) {
    val context = LocalContext.current
    val esNuevo = item == null
    val base = item ?: prefill

    // ID: si es un registro existente, arranca con su ID actual; si es nuevo, sugiere uno
    // autogenerado (hex corto), pero en ambos casos el usuario lo puede editar por si choca
    // con un ID que ya haya generado AppSheet.
    var idEditable by remember { mutableStateOf(item?.id ?: java.util.UUID.randomUUID().toString().replace("-", "").take(8)) }

    var nombre by remember { mutableStateOf(base?.nombre ?: "") }
    var semana by remember { mutableStateOf(base?.semana ?: "") }
    var requisito by remember { mutableStateOf(base?.requisito ?: "") }
    var numTT by remember { mutableStateOf(base?.numTT ?: "") }
    var ref1 by remember { mutableStateOf(base?.ref1 ?: "") }
    var ref2 by remember { mutableStateOf(base?.ref2 ?: "") }
    var observaciones by remember { mutableStateOf(base?.observaciones ?: "") }
    var estado by remember { mutableStateOf(base?.estado ?: "") }
    var ubicacion by remember { mutableStateOf(base?.ubicacion ?: "") }
    // Fecha y Hora (el selector combinado de arriba) siempre inicia en el momento actual.
    // El campo "Hora" de abajo es independiente: si es un registro existente conserva su
    // hora guardada, y si es nuevo empieza en blanco para que se elija a mano.
    var fechaMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var hora by remember { mutableStateOf(item?.hora ?: "") }
    var ruta by remember { mutableStateOf(item?.ruta ?: "") }
    var folioP by remember { mutableStateOf(base?.folioP ?: "") }
    var descuentoPago by remember { mutableStateOf(item?.descuentoPago ?: "") }
    var descuentoAhorro by remember { mutableStateOf(item?.descuentoAhorro ?: "") }
    var ref3 by remember { mutableStateOf(item?.ref3 ?: "") }
    var ref4 by remember { mutableStateOf(item?.ref4 ?: "") }
    var diaPago by remember { mutableStateOf(item?.diaPago ?: "") }
    var domicilioLaboral by remember { mutableStateOf(item?.domicilioLaboral ?: "") }
    var diasAtraso by remember { mutableStateOf(item?.diasAtraso ?: "") }
    var diasApertura by remember { mutableStateOf(item?.diasApertura ?: "") }
    var buscandoUbicacionLaboral by remember { mutableStateOf(false) }
    var estadoMenuExpanded by remember { mutableStateOf(false) }
    var buscandoUbicacion by remember { mutableStateOf(esNuevo && prefill?.ubicacion.isNullOrBlank()) }
    var activePhotoSlot by remember { mutableStateOf(1) }
    // Buscar domicilio escribiendo la dirección (sin necesidad de estar en el sitio): se
    // geocodifica el texto igual que en Ruta IA y se llena el campo con "lat,lng".
    var mostrarBuscarDireccionOficial by remember { mutableStateOf(false) }
    var mostrarBuscarDireccionLaboral by remember { mutableStateOf(false) }
    var buscandoDireccionTexto by remember { mutableStateOf(false) }

    // Leer el nombre desde una foto (OCR), igual que "Buscar con foto" en la lista, pero aquí
    // el resultado llena el campo Nombre en vez de disparar una búsqueda.
    val coroutineScope = rememberCoroutineScope()
    var buscandoNombrePorFoto by remember { mutableStateOf(false) }
    var mostrarSelectorFotoNombre by remember { mutableStateOf(false) }
    var fotoNombreUri by remember { mutableStateOf<Uri?>(null) }

    fun procesarFotoNombre(uri: Uri?) {
        if (uri == null) return
        buscandoNombrePorFoto = true
        coroutineScope.launch {
            val datos = extraerDatosClienteDeImagen(context, uri)
            buscandoNombrePorFoto = false
            if (datos.nombre.isNullOrBlank() && datos.monto.isNullOrBlank() && datos.semana.isNullOrBlank() && datos.cu.isNullOrBlank() && datos.diasAtraso.isNullOrBlank()) {
                Toast.makeText(context, "No se detectó un nombre en la foto, intenta con otra más clara", Toast.LENGTH_LONG).show()
            } else {
                if (!datos.nombre.isNullOrBlank()) nombre = datos.nombre
                if (!datos.monto.isNullOrBlank()) requisito = datos.monto
                if (!datos.semana.isNullOrBlank()) semana = datos.semana
                if (!datos.diasAtraso.isNullOrBlank()) {
                    diasAtraso = datos.diasAtraso
                    diasApertura = calcularDiasApertura(datos.diasAtraso.toInt()).toString()
                }
                if (!datos.cu.isNullOrBlank() && folioP.isBlank()) folioP = datos.cu
            }
        }
    }
    val ocrNombreTakePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success -> if (success) procesarFotoNombre(fotoNombreUri) }
    val ocrNombrePickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> procesarFotoNombre(uri) }
    if (mostrarSelectorFotoNombre) {
        AlertDialog(
            onDismissRequest = { mostrarSelectorFotoNombre = false },
            title = { Text("Leer nombre de una foto") },
            text = { Text("Toma una foto o elige una de la galería. Se leerá el texto para llenar el Nombre, el Req y el Sem.") },
            confirmButton = {
                TextButton(onClick = {
                    mostrarSelectorFotoNombre = false
                    val photoFile = java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "nombre_ocr_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(context, "com.example.matrizapp.fileprovider", photoFile)
                    fotoNombreUri = uri
                    ocrNombreTakePictureLauncher.launch(uri)
                }) { Text("Cámara") }
            },
            dismissButton = {
                TextButton(onClick = {
                    mostrarSelectorFotoNombre = false
                    ocrNombrePickImageLauncher.launch("image/*")
                }) { Text("Galería") }
            }
        )
    }

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
                OutlinedTextField(
                    value = nombre, onValueChange = { nombre = it }, label = { Text("Nombre") },
                    trailingIcon = {
                        if (buscandoNombrePorFoto) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { mostrarSelectorFotoNombre = true }) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "Leer nombre de una foto")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (mostrarCamposExtra) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = semana, onValueChange = { semana = it }, label = { Text("Sem") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = diasAtraso,
                            onValueChange = { nuevo ->
                                diasAtraso = nuevo.filter { it.isDigit() }.take(4)
                                // Apertura se recalcula con el día actual solo cuando cambia Días de atraso
                                // (así un registro guardado no se mueve solo al abrirlo otro día).
                                diasApertura = diasAtraso.toIntOrNull()?.let { calcularDiasApertura(it).toString() } ?: ""
                            },
                            label = { Text("Días atraso") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = diasApertura, onValueChange = {}, readOnly = true, label = { Text("Días apertura") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = diasApertura.toIntOrNull()?.let { diasAperturaASemanas(it).toString() } ?: "",
                            onValueChange = {}, readOnly = true, label = { Text("Sem apertura") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = semana, onValueChange = { semana = it }, label = { Text("Sem") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = requisito, onValueChange = { requisito = it }, label = { Text("Req") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                if (mostrarCamposExtra) {
                    OutlinedTextField(
                        value = diaPago, onValueChange = { diaPago = filtrarMoneda(it) }, label = { Text("Día de pago") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        visualTransformation = MonedaVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = numTT, onValueChange = { numTT = it }, label = { Text("Num TT") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ref1, onValueChange = { ref1 = it }, label = { Text("Ref 1") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ref2, onValueChange = { ref2 = it }, label = { Text("Ref 2") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                if (mostrarCamposExtra) {
                    OutlinedTextField(
                        value = ref3, onValueChange = { ref3 = it }, label = { Text("Ref 3") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = ref4, onValueChange = { ref4 = it }, label = { Text("Ref 4") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
                    value = ubicacion, onValueChange = { ubicacion = filtrarCoordenadas(it) }, label = { Text("Domicilio Oficial") },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { mostrarBuscarDireccionOficial = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Buscar dirección escrita para domicilio oficial")
                            }
                            IconButton(onClick = {
                                buscandoUbicacion = true
                            }) { Icon(Icons.Default.MyLocation, contentDescription = "Usar ubicación actual como domicilio oficial") }
                        }
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
                if (mostrarBuscarDireccionOficial) {
                    BuscarDireccionDialog(
                        buscando = buscandoDireccionTexto,
                        onDismiss = { mostrarBuscarDireccionOficial = false },
                        onBuscar = { texto ->
                            coroutineScope.launch {
                                buscandoDireccionTexto = true
                                val coords = geocodificarDireccion(context, texto)
                                buscandoDireccionTexto = false
                                if (coords != null) {
                                    ubicacion = "${coords.first},${coords.second}"
                                    mostrarBuscarDireccionOficial = false
                                } else {
                                    Toast.makeText(context, "No se encontró esa dirección", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
                AvisoMismaUbicacion(rememberCoincidenciasUbicacion(ubicacion, item?.id))
                if (mostrarCamposExtra) {
                    OutlinedTextField(
                        value = domicilioLaboral, onValueChange = { domicilioLaboral = filtrarCoordenadas(it) }, label = { Text("Domicilio Laboral") },
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { mostrarBuscarDireccionLaboral = true }) {
                                    Icon(Icons.Default.Search, contentDescription = "Buscar dirección escrita para domicilio laboral")
                                }
                                IconButton(onClick = { buscandoUbicacionLaboral = true }) {
                                    Icon(Icons.Default.MyLocation, contentDescription = "Usar ubicación actual como domicilio laboral")
                                }
                            }
                        },
                        supportingText = { if (buscandoUbicacionLaboral) Text("Obteniendo ubicación…") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    LaunchedEffect(buscandoUbicacionLaboral) {
                        if (buscandoUbicacionLaboral) {
                            domicilioLaboral = obtenerUbicacionActual(context) ?: domicilioLaboral
                            buscandoUbicacionLaboral = false
                        }
                    }
                    if (mostrarBuscarDireccionLaboral) {
                        BuscarDireccionDialog(
                            buscando = buscandoDireccionTexto,
                            onDismiss = { mostrarBuscarDireccionLaboral = false },
                            onBuscar = { texto ->
                                coroutineScope.launch {
                                    buscandoDireccionTexto = true
                                    val coords = geocodificarDireccion(context, texto)
                                    buscandoDireccionTexto = false
                                    if (coords != null) {
                                        domicilioLaboral = "${coords.first},${coords.second}"
                                        mostrarBuscarDireccionLaboral = false
                                    } else {
                                        Toast.makeText(context, "No se encontró esa dirección", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }

                // Imagen e Imagen 2 van después de los domicilios, igual que en AppSheet.
                // Solo disponibles al editar un registro existente (se necesita su ID).
                Text("Imagen", style = MaterialTheme.typography.labelMedium)
                ImagenCaptureBox(
                    url = item?.imagenUrl,
                    enabled = item != null && viewModel != null,
                    driveHelper = viewModel?.driveHelper,
                    onClick = {
                        activePhotoSlot = 1
                        val uri = viewModel!!.preparePhotoUri(context, 1)
                        takePictureLauncher.launch(uri)
                    },
                    onDelete = if (item != null && viewModel != null && !item.imagenUrl.isNullOrBlank()) ({ viewModel.borrarImagen(item.id, 1) }) else null
                )
                Text("Imagen 2", style = MaterialTheme.typography.labelMedium)
                ImagenCaptureBox(
                    url = item?.imagenUrl2,
                    enabled = item != null && viewModel != null,
                    driveHelper = viewModel?.driveHelper,
                    onClick = {
                        activePhotoSlot = 2
                        val uri = viewModel!!.preparePhotoUri(context, 2)
                        takePictureLauncher.launch(uri)
                    },
                    onDelete = if (item != null && viewModel != null && !item.imagenUrl2.isNullOrBlank()) ({ viewModel.borrarImagen(item.id, 2) }) else null
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

                // Oferta de descuento del día: solo se manda al titular por SMS después del mensaje
                // normal, y se borra sola a medianoche (válida solo por el día en que se captura).
                Text("Oferta de descuento (solo por hoy, solo SMS al titular)", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = descuentoPago, onValueChange = { descuentoPago = it }, label = { Text("Pago con descuento") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = descuentoAhorro, onValueChange = { descuentoAhorro = it }, label = { Text("Ahorro") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
                    )
                }

                // Hora editable por separado (igual que en AppSheet): se autocompleta al elegir
                // fecha y hora arriba, pero se puede ajustar aparte sin tocar la fecha.
                OutlinedTextField(
                    value = hora, onValueChange = { hora = it }, label = { Text("Hora") },
                    trailingIcon = {
                        IconButton(onClick = {
                            val cal = java.util.Calendar.getInstance().apply { timeInMillis = fechaMillis }
                            android.app.TimePickerDialog(context, { _, h, min ->
                                hora = String.format(Locale.getDefault(), "%02d:%02d:00", h, min)
                            }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
                        }) { Icon(Icons.Default.AccessTime, contentDescription = "Elegir hora") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                // ID: autogenerado, pero editable por si choca con uno que ya generó AppSheet.
                OutlinedTextField(
                    value = idEditable, onValueChange = { idEditable = it }, label = { Text("ID") },
                    supportingText = { Text("Se genera automático, pero puedes cambiarlo si choca con otro ID") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(idEditable, nombre, semana, requisito, numTT, ref1, ref2, observaciones, estado, ubicacion, fechaMillis, hora, ruta, folioP, descuentoPago, descuentoAhorro, ref3, ref4, diaPago, domicilioLaboral, diasAtraso, diasApertura)
                },
                enabled = nombre.isNotBlank() && idEditable.isNotBlank()
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** Diálogo simple para escribir una dirección de texto y geocodificarla (misma lógica que Ruta
 * IA), para poder poner el domicilio sin tener que estar físicamente en el sitio. */
@Composable
private fun BuscarDireccionDialog(buscando: Boolean, onDismiss: () -> Unit, onBuscar: (String) -> Unit) {
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

/** Coordenadas "lat, long": solo dígitos, punto, coma, guion y espacio (se puede pegar directo
 * desde Google Maps, ej. 19.367936, -99.140631). */
private fun filtrarCoordenadas(v: String): String =
    v.filter { it.isDigit() || it == '.' || it == ',' || it == '-' || it == ' ' }

/** Moneda: guarda solo dígitos y un punto decimal (máx. 2 decimales); el "$" y las comas de
 * miles son solo visuales (ver MonedaVisualTransformation), no se guardan. */
private fun filtrarMoneda(v: String): String {
    val limpio = v.filter { it.isDigit() || it == '.' }
    val i = limpio.indexOf('.')
    if (i < 0) return limpio
    val entera = limpio.substring(0, i)
    val decimales = limpio.substring(i + 1).replace(".", "").take(2)
    return entera + "." + decimales
}

/** Muestra el valor como $1,234.50 sin alterar el texto guardado (ej. "1234.5"). */
private class MonedaVisualTransformation : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val raw = text.text
        if (raw.isEmpty()) {
            return androidx.compose.ui.text.input.TransformedText(text, androidx.compose.ui.text.input.OffsetMapping.Identity)
        }
        val punto = raw.indexOf('.')
        val entera = if (punto >= 0) raw.substring(0, punto) else raw
        val decimales = if (punto >= 0) raw.substring(punto) else ""
        val sb = StringBuilder("\$")
        val mapa = IntArray(raw.length + 1)
        var salida = 1
        for (i in entera.indices) {
            if (i > 0 && (entera.length - i) % 3 == 0) { sb.append(','); salida++ }
            mapa[i] = salida
            sb.append(entera[i]); salida++
        }
        for (j in decimales.indices) {
            mapa[entera.length + j] = salida
            sb.append(decimales[j]); salida++
        }
        mapa[raw.length] = salida
        val offsets = object : androidx.compose.ui.text.input.OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = mapa[offset.coerceIn(0, raw.length)]
            override fun transformedToOriginal(offset: Int): Int {
                var res = 0
                for (i in 0..raw.length) { if (mapa[i] <= offset) res = i else break }
                return res
            }
        }
        return androidx.compose.ui.text.input.TransformedText(androidx.compose.ui.text.AnnotatedString(sb.toString()), offsets)
    }
}

@Composable
fun ImagenCaptureBox(url: String?, enabled: Boolean, driveHelper: DriveHelper?, onClick: () -> Unit, onDelete: (() -> Unit)? = null) {
    val context = LocalContext.current
    var uriResuelta by remember(url) { mutableStateOf<String?>(null) }
    var resolviendo by remember(url) { mutableStateOf(false) }
    var mostrarGrande by remember(url) { mutableStateOf(false) }
    var confirmarBorrado by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        uriResuelta = null
        if (!url.isNullOrBlank() && driveHelper != null) {
            resolviendo = true
            val uri = resolverArchivoComoUri(context, driveHelper, url, "imagen_${url.hashCode()}.jpg")
            uriResuelta = uri?.toString()
            resolviendo = false
        }
    }

    val hayImagen = uriResuelta != null
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            // Si ya hay imagen, tocarla la abre en grande (acción principal, más fácil de
            // acertar con el dedo). Si no hay imagen, tocar abre la cámara para tomarla.
            .clickable(enabled = enabled, onClick = if (hayImagen) ({ mostrarGrande = true }) else onClick),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f)),
        color = Color.Transparent
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                hayImagen -> {
                    AsyncImage(model = uriResuelta, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    // Icono de cámara aparte, para reemplazar la foto sin que estorbe el toque
                    // principal (que ahora es para ver en grande).
                    IconButton(
                        onClick = onClick,
                        enabled = enabled,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(28.dp)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "Tomar otra foto", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    if (onDelete != null) {
                        IconButton(
                            onClick = { confirmarBorrado = true },
                            enabled = enabled,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp)
                                .size(28.dp)
                                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Borrar foto", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                resolviendo -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> Icon(
                    Icons.Default.PhotoCamera, contentDescription = "Tomar foto",
                    tint = if (enabled) Color.Gray else Color.LightGray
                )
            }
        }
    }
    if (mostrarGrande && uriResuelta != null) {
        ImageDetailDialog(url = uriResuelta!!, onDismiss = { mostrarGrande = false })
    }
    if (confirmarBorrado && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmarBorrado = false },
            title = { Text("¿Borrar esta foto?") },
            text = { Text("Se quitará del registro. Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = { confirmarBorrado = false; onDelete() }) { Text("Borrar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmarBorrado = false }) { Text("Cancelar") }
            }
        )
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
            .background(ClayNeutralContainer)
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
/** Botón de ordenar reutilizable: icono que abre un menú con las opciones de OrdenLista.
 * Si la opción elegida es por ubicación, primero obtiene el GPS actual del dispositivo
 * (mostrando un pequeño loader) y luego se lo pasa al ViewModel para calcular distancias.
 * Usado en Filtro Fecha, Sem6 y Solicitud. */
@Composable
fun OrdenSelectorButton(orden: OrdenLista, onOrdenChange: (OrdenLista, Pair<Double, Double>?) -> Unit) {
    var expandido by remember { mutableStateOf(false) }
    var buscandoUbicacion by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box {
        IconButton(onClick = { expandido = true }, enabled = !buscandoUbicacion) {
            if (buscandoUbicacion) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Default.Sort,
                    contentDescription = "Ordenar",
                    tint = if (orden != OrdenLista.ORIGINAL) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
        }
        DropdownMenu(expanded = expandido, onDismissRequest = { expandido = false }) {
            OrdenLista.values().forEach { opcion ->
                DropdownMenuItem(
                    text = { Text(opcion.etiqueta) },
                    leadingIcon = { if (opcion == orden) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = {
                        expandido = false
                        if (opcion.necesitaUbicacionActual()) {
                            buscandoUbicacion = true
                            scope.launch {
                                val ubic = parseLatLngOrden(obtenerUbicacionActual(context))
                                buscandoUbicacion = false
                                if (ubic == null) {
                                    Toast.makeText(context, "No se pudo obtener tu ubicación actual (revisa el GPS)", Toast.LENGTH_SHORT).show()
                                } else {
                                    onOrdenChange(opcion, ubic)
                                }
                            }
                        } else {
                            onOrdenChange(opcion, null)
                        }
                    }
                )
            }
        }
    }
}

/** Variante de OrdenSelectorButton solo para Filtro Fecha: mismas opciones de orden, más una
 * opción extra "Pagados" al fondo del mismo menú que filtra la lista a solo los registros con
 * Status="Pagado" del rango de fecha actual (con checkmark cuando está activa). No se tocó
 * OrdenSelectorButton porque lo comparten Matriz, Sem6 y Solicitud, donde "Pagados" no aplica. */
@Composable
fun FiltroFechaOrdenButton(
    orden: OrdenLista,
    onOrdenChange: (OrdenLista, Pair<Double, Double>?) -> Unit,
    soloPagados: Boolean,
    onToggleSoloPagados: () -> Unit
) {
    var expandido by remember { mutableStateOf(false) }
    var buscandoUbicacion by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box {
        IconButton(onClick = { expandido = true }, enabled = !buscandoUbicacion) {
            if (buscandoUbicacion) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Default.Sort,
                    contentDescription = "Ordenar / filtrar",
                    tint = if (orden != OrdenLista.ORIGINAL || soloPagados) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
        }
        DropdownMenu(expanded = expandido, onDismissRequest = { expandido = false }) {
            OrdenLista.values().forEach { opcion ->
                DropdownMenuItem(
                    text = { Text(opcion.etiqueta) },
                    leadingIcon = { if (opcion == orden) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = {
                        expandido = false
                        if (opcion.necesitaUbicacionActual()) {
                            buscandoUbicacion = true
                            scope.launch {
                                val ubic = parseLatLngOrden(obtenerUbicacionActual(context))
                                buscandoUbicacion = false
                                if (ubic == null) {
                                    Toast.makeText(context, "No se pudo obtener tu ubicación actual (revisa el GPS)", Toast.LENGTH_SHORT).show()
                                } else {
                                    onOrdenChange(opcion, ubic)
                                }
                            }
                        } else {
                            onOrdenChange(opcion, null)
                        }
                    }
                )
            }
            Divider()
            DropdownMenuItem(
                text = { Text("Pagados") },
                leadingIcon = { if (soloPagados) Icon(Icons.Default.Check, contentDescription = null) },
                onClick = { expandido = false; onToggleSoloPagados() }
            )
        }
    }
}

/** OCR local (ML Kit, on-device, sin costo) para el buscador con foto: intenta detectar el
 * nombre más probable en la imagen (línea sin números, 2 a 5 palabras, elige la de texto
 * más grande/alto ya que suele ser el título/nombre del cliente en la pantalla fotografiada).
 * Devuelve null si no encuentra nada que parezca un nombre. */
suspend fun extraerNombreDeImagen(context: android.content.Context, uri: Uri): String? = suspendCancellableCoroutine { cont ->
    try {
        val image = com.google.mlkit.vision.common.InputImage.fromFilePath(context, uri)
        val alturaImagen = image.height
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
        )
        // Palabras/frases típicas de encabezados, pasos (stepper) y botones de apps como
        // AppSheet/CONTENCION que NO son el nombre del cliente. Se descartan aunque cumplan
        // el resto de criterios (2-5 palabras, solo letras), porque si no ganan por ser más
        // grandes/gruesas que el nombre real.
        val palabrasUi = listOf(
            "resumen del cliente", "expediente del cliente", "ver expediente", "ver otros lugares",
            "prepárate", "preparate", "en ruta", "localiza", "contacta", "cobra",
            "monto solicitado", "folio de solicitud", "torre de control", "temporizador",
            "espera en el lugar", "seguimiento a esta solicitud", "continuar con tu ruta",
            "originación", "originacion", "días de atraso", "dias de atraso", "último pago", "ultimo pago"
        )
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val mejorLinea: String? = detectarNombreEnTexto(visionText, alturaImagen)
                if (cont.isActive) cont.resume(mejorLinea?.uppercase()) {}
            }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) {} }
    } catch (e: Exception) {
        if (cont.isActive) cont.resume(null) {}
    }
}

/** Palabras de encabezados/botones de la app de Banco Azteca y de AppSheet que NO son el nombre. */
private val PALABRAS_UI_NOMBRE = listOf(
    "resumen del cliente", "expediente del cliente", "ver expediente", "ver otros lugares",
    "prepárate", "preparate", "en ruta", "localiza", "contacta", "cobra",
    "monto solicitado", "folio de solicitud", "torre de control", "temporizador",
    "espera en el lugar", "seguimiento a esta solicitud", "continuar con tu ruta",
    "originación", "originacion", "días de atraso", "dias de atraso", "último pago", "ultimo pago"
)

/** Detecta el nombre completo del cliente en la foto. El nombre largo se parte en 2 renglones
 * visuales (ej. "MONTSERRAT VEGA SIERRA" / "PENICHE"), y ML Kit suele meter el CU y el "Último
 * pago" en el MISMO bloque que el nombre, así que reconstruirlo por bloque falla (el bloque trae
 * números). Por eso se trabaja por LÍNEA: se toman solo las líneas de puras letras, se agrupan las
 * que están pegadas verticalmente, con letra del mismo tamaño y alineadas a la izquierda, y se
 * elige el grupo con la letra más grande (2 a 6 palabras). */
internal fun detectarNombreEnTexto(visionText: com.google.mlkit.vision.text.Text, alturaImagen: Int): String? {
    class Linea(val texto: String, val box: android.graphics.Rect)
    val candidatas = ArrayList<Linea>()
    val cuBoxes = ArrayList<android.graphics.Rect>()
    for (block in visionText.textBlocks) for (line in block.lines) {
        val box = line.boundingBox ?: continue
        if (patronCuOcr.containsMatchIn(line.text)) cuBoxes.add(box)
        // NFC: compone letra + acento suelto en un solo carácter; trim de símbolos sueltos en los
        // extremos (ej. "PENICHE." o "| PENICHE") sin tocar lo de en medio.
        val texto = java.text.Normalizer.normalize(line.text, java.text.Normalizer.Form.NFC)
            .trim { !it.isLetter() }
        val sinEspacios = texto.replace(" ", "")
        // >= 3 letras por línea: descarta íconos de la barra de estado que el OCR lee como letras sueltas ("A", "G", "G")
        if (sinEspacios.length < 3 || !sinEspacios.all { it.isLetter() }) continue
        if (PALABRAS_UI_NOMBRE.any { texto.contains(it, ignoreCase = true) }) continue
        if (alturaImagen > 0 && box.top < alturaImagen * 0.25) continue
        candidatas.add(Linea(texto, box))
    }
    candidatas.sortBy { it.box.top }
    val grupos = ArrayList<MutableList<Linea>>()
    for (l in candidatas) {
        val actual = grupos.lastOrNull()
        val previa = actual?.last()
        var sigue = false
        if (actual != null && previa != null) {
            val h = l.box.height().coerceAtLeast(1)
            val hPrevia = previa.box.height().coerceAtLeast(1)
            val ratio = h.toFloat() / hPrevia
            val hueco = l.box.top - previa.box.bottom
            sigue = ratio in 0.7f..1.4f &&
                hueco in (-h / 2)..(h * 13 / 10) &&
                kotlin.math.abs(l.box.left - previa.box.left) <= h * 3 / 2
        }
        if (sigue) actual!!.add(l) else grupos.add(mutableListOf(l))
    }
    fun valido(g: List<Linea>): Boolean {
        val texto = g.joinToString(" ") { it.texto }
        val palabras = texto.split(" ").filter { it.isNotBlank() }
        return palabras.size in 2..6 && texto.length in 5..60
    }
    // El nombre siempre va justo arriba del CU (01-09-00201-11134): si hay CU en la foto, se prefiere
    // el grupo anclado ahí. Evita agarrar textos de la barra de estado o encabezados.
    fun anclado(g: List<Linea>): Boolean {
        val ult = g.last()
        val h = ult.box.height().coerceAtLeast(1)
        return cuBoxes.any { cu ->
            (cu.top - ult.box.bottom) in (-h / 2)..(h * 3) &&
                kotlin.math.abs(cu.left - g.first().box.left) <= h * 3
        }
    }
    val validos = grupos.filter { valido(it) }
    val pool = validos.filter { anclado(it) }.ifEmpty { validos }
    val elegido = pool.maxByOrNull { g -> g.sumOf { it.box.height() }.toDouble() / g.size }
    return elegido?.joinToString(" ") { it.texto }
}

/** Resultado del OCR usado en el diálogo de Editar/Nuevo registro: nombre, monto "Requerido",
 * semana de atraso (calculada a partir de los "días de atraso") y CU detectados en la misma
 * foto (los cuatro nullable de forma independiente, puede venir solo alguno de ellos). */
data class DatosClienteOcr(val nombre: String?, val monto: String?, val semana: String?, val cu: String?, val diasAtraso: String? = null)

/** Días transcurridos desde el lunes de la semana actual (lunes=0 ... domingo=6). */
fun diasDesdeLunes(hoy: java.util.Calendar = java.util.Calendar.getInstance()): Int =
    (hoy.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7

/** Días de atraso que el cliente tenía el lunes de esta semana (apertura): días de atraso de hoy
 * menos los días que han pasado desde el lunes. Ej.: hoy sábado (5 días desde lunes), 20 de atraso -> 15. */
fun calcularDiasApertura(diasAtraso: Int, hoy: java.util.Calendar = java.util.Calendar.getInstance()): Int =
    (diasAtraso - diasDesdeLunes(hoy)).coerceAtLeast(0)

/** Tabla de Diego (cortes "menos de"): menos de 6 días = semana 1, menos de 13 = 2, menos de 20 = 3,
 * menos de 27 = 4, menos de 34 = 5, menos de 41 = 6, menos de 48 = 7, 48 o más = 8. Tabla base: 1 a 6, 6 a 13, ... 41 a 48
 * (el límite superior ya pertenece a la semana siguiente). 0 días = 0 (no traía atraso el lunes). */
fun diasAperturaASemanas(dias: Int): Int = if (dias < 1) 0 else ((dias + 1) / 7) + 1

/** Mismo patrón de 4 bloques que `patronCu` en PaseFotoImport.kt (CU formato
 * 01-01-01627-89102: 2-2-5-5 dígitos), pero tolerante al separador: en fotos tomadas con
 * cámara (con glare/ruido, no capturas limpias) ML Kit a veces lee el guion como espacio o
 * como guion largo (–/—) en vez de "-". Los grupos se reconstruyen siempre con "-" normal. */
private val patronCuOcr = Regex("(?<!\\d)(\\d{1,2})[\\s\\-–—](\\d{1,2})[\\s\\-–—](\\d{3,6})[\\s\\-–—](\\d{3,8})(?!\\d)")
private fun MatchResult.aCu(): String = "${groupValues[1]}-${groupValues[2]}-${groupValues[3]}-${groupValues[4]}"

/** Convierte "días de atraso" (como lo muestra la app de Banco Azteca) a la "Sem" que usa
 * Matriz, según la tabla que dio Diego: menos de 7 días = semana 1; 7 días = semana 2;
 * 14 = semana 3; 21 = semana 4; 28 = semana 5; 35 = semana 6; 42 = semana 7 (patrón:
 * cada 7 días completos suma una semana, arrancando en 1). */
fun diasAtrasoASemana(dias: Int): Int = (dias / 7) + 1

/** OCR local (ML Kit) para el diálogo de Editar/Nuevo registro (botón de cámara junto al
 * campo Nombre): además del nombre (misma lógica de detección que extraerNombreDeImagen,
 * línea sin números de 2-5 palabras con el texto más grande), busca el monto "Requerido"
 * y los "días de atraso" que muestra la app de Banco Azteca en el resumen del cliente
 * (ej. "Requerido $2,034" / "18 días de atraso") para llenar también los campos Req y Sem.
 * No reemplaza a extraerNombreDeImagen, que se sigue usando donde solo hace falta el
 * nombre (buscador con foto, Solicitud, exportar Matriz). */
suspend fun extraerDatosClienteDeImagen(context: android.content.Context, uri: Uri): DatosClienteOcr = suspendCancellableCoroutine { cont ->
    try {
        val image = com.google.mlkit.vision.common.InputImage.fromFilePath(context, uri)
        val alturaImagen = image.height
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
        )
        val palabrasUi = listOf(
            "resumen del cliente", "expediente del cliente", "ver expediente", "ver otros lugares",
            "prepárate", "preparate", "en ruta", "localiza", "contacta", "cobra",
            "monto solicitado", "folio de solicitud", "torre de control", "temporizador",
            "espera en el lugar", "seguimiento a esta solicitud", "continuar con tu ruta",
            "originación", "originacion", "días de atraso", "dias de atraso", "último pago", "ultimo pago"
        )
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val mejorLinea: String? = detectarNombreEnTexto(visionText, alturaImagen)
                // Monto "Requerido" y "días de atraso": se buscan sobre el texto completo (no
                // línea por línea) porque el OCR a veces separa la etiqueta y el número en
                // bloques distintos que igual quedan consecutivos en visionText.text.
                val textoCompleto = java.text.Normalizer.normalize(visionText.text, java.text.Normalizer.Form.NFC)
                // Monto: si viene con comas de miles se corta en el último grupo de 3 dígitos (así "$5,39336 días"
                // no se traga el "36" de los días); sin comas toma todos los dígitos.
                val patronMonto = "\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?"
                // 1) Por etiqueta ("Requerido $X" o "Mínimo $X", los dos formatos de la app), tolerando OCR imperfecto.
                var montoMatch = Regex("(?i)(?:[qg]u?erido|m[ií]n[ií]mo)\\s*\\$?\\s*($patronMonto)").find(textoCompleto)
                // 2) Respaldo si la etiqueta no se leyó: el monto en $ que va antes de "días de atraso"
                //    (o el último $ de la foto), ignorando el de "Último pago $X el ...".
                if (montoMatch == null) {
                    val diasIdx = Regex("(?i)d[ií]as?\\s*de\\s*a").find(textoCompleto)?.range?.first ?: textoCompleto.length
                    val candidatos = Regex("\\$\\s*($patronMonto)").findAll(textoCompleto).filter { m ->
                        val previo = textoCompleto.substring(maxOf(0, m.range.first - 25), m.range.first)
                        !previo.contains("pago", ignoreCase = true)
                    }.toList()
                    montoMatch = candidatos.lastOrNull { it.range.first < diasIdx } ?: candidatos.lastOrNull()
                }
                val monto = montoMatch?.groupValues?.get(1)?.let { "$$it" }
                // Días de atraso: se busca en el texto SIN el monto, y solo se exige "de a" porque en fotos
                // con la pantalla cortada la palabra "atraso" sale incompleta ("36 días de atras").
                val textoSinMonto = montoMatch?.let { textoCompleto.removeRange(it.range) } ?: textoCompleto
                val diasMatch = Regex("(?i)(\\d{1,3})\\s*d[ií]as?\\s*de\\s*a").find(textoSinMonto)
                val semana = diasMatch?.groupValues?.get(1)?.toIntOrNull()?.let { diasAtrasoASemana(it).toString() }
                val cu = patronCuOcr.find(textoCompleto)?.aCu()
                val diasAtrasoTxt = diasMatch?.groupValues?.get(1)?.toIntOrNull()?.toString()
                if (cont.isActive) cont.resume(DatosClienteOcr(mejorLinea?.uppercase(), monto, semana, cu, diasAtrasoTxt)) {}
            }
            .addOnFailureListener { if (cont.isActive) cont.resume(DatosClienteOcr(null, null, null, null)) {} }
    } catch (e: Exception) {
        if (cont.isActive) cont.resume(DatosClienteOcr(null, null, null, null)) {}
    }
}

/** OCR local (ML Kit) solo para CU, usado por el backfill masivo de Matriz (fotos ya
 * subidas a Drive, no se necesita nombre/monto/semana de esos registros existentes). */
suspend fun extraerCuDeImagen(context: android.content.Context, uri: Uri): String? = suspendCancellableCoroutine { cont ->
    try {
        val image = com.google.mlkit.vision.common.InputImage.fromFilePath(context, uri)
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
        )
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val cu = patronCuOcr.find(visionText.text)?.aCu()
                if (cont.isActive) cont.resume(cu) {}
            }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) {} }
    } catch (e: Exception) {
        if (cont.isActive) cont.resume(null) {}
    }
}

/** Resultado del OCR del ticket de cobranza (foto): nombre, monto pagado y CU detectados
 * (los tres nullable de forma independiente). */
data class DatosTicketOcr(val nombre: String?, val monto: Double?, val cu: String?, val textoCompleto: String = "")

private val patronUstedPago = Regex("(?i)Usted\\s+pag[oó]:?\\s*\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
private val patronNoCliente = Regex("(?i)No\\.?\\s*de\\s*Cliente")
private val patronEncabezadoTicket = Regex("(?i)(cliente|datos|resumen|expediente|ticket|recibo|fecha|hora|folio|sucursal|banco|azteca|pag[oó]|aprobad|referencia|autorizaci[oó]n|tel[eé]fono|operaci[oó]n|monto|total)")
private fun esLineaDeNombreTicket(l: String): Boolean =
    l.length >= 2 && l.none { it.isDigit() } && !l.contains(':') && !patronEncabezadoTicket.containsMatchIn(l)

/** OCR local (ML Kit) para el escaneo de ticket de cobranza en Filtro Fecha (ver
 * FiltroFechaViewModel.registrarPagoDesdeTicket). Soporta varios formatos reales de ticket
 * de Banco Azteca (recibo estándar con "DATOS DEL CLIENTE" y terminal/compropago con
 * "Tu pago fue Aprobado"): en todos ellos el nombre del cliente queda siempre en la línea
 * inmediatamente arriba de "No. de Cliente:", así que esa es la heurística usada en vez de
 * depender de un encabezado fijo. El monto siempre aparece como "Usted pagó: $X.XX". El CU
 * a veces viene parcialmente enmascarado con X (formato de terminal), en cuyo caso el regex
 * simplemente no encuentra match y queda null -- el match en el ViewModel se hace por nombre. */
suspend fun extraerDatosTicketDeImagen(context: android.content.Context, uri: Uri): DatosTicketOcr = suspendCancellableCoroutine { cont ->
    try {
        val image = com.google.mlkit.vision.common.InputImage.fromFilePath(context, uri)
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
        )
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val texto = java.text.Normalizer.normalize(visionText.text, java.text.Normalizer.Form.NFC)
                val lineas = texto.split("\n").map { it.trim() }
                val idxNoCliente = lineas.indexOfFirst { patronNoCliente.containsMatchIn(it) }
                // Un nombre largo se parte en 2 o 3 renglones en el ticket (ej. "JOSE ROSARIO AVILA" /
                // "ANGELES"): antes solo se leía el renglón pegado a "No. de Cliente" y se perdía el resto.
                // Se sube desde ahí juntando renglones mientras parezcan nombre (sin dígitos, sin ":" ni
                // encabezados como "DATOS DEL CLIENTE"), hasta 3.
                val nombre = if (idxNoCliente > 0) {
                    val previas = (idxNoCliente - 1 downTo 0).map { lineas[it] }.filter { it.isNotBlank() }
                    val partes = previas.takeWhile { esLineaDeNombreTicket(it) }.take(3).reversed()
                    (if (partes.isNotEmpty()) partes.joinToString(" ") else previas.firstOrNull())?.uppercase()
                } else null
                val monto = patronUstedPago.find(texto)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
                val cu = patronCuOcr.find(texto)?.aCu()
                if (cont.isActive) cont.resume(DatosTicketOcr(nombre, monto, cu, texto)) {}
            }
            .addOnFailureListener { if (cont.isActive) cont.resume(DatosTicketOcr(null, null, null)) {} }
    } catch (e: Exception) {
        if (cont.isActive) cont.resume(DatosTicketOcr(null, null, null)) {}
    }
}


// ───────── Aviso: otros registros en la misma ubicación (coordenadas) ─────────
// Radio fijo de 10 m (el mismo que usa Filtrar para "cercanos por GPS"). Compara contra TODOS los
// registros de Matriz (Room, offline); no depende de Sheets.
const val RADIO_MISMA_UBICACION_M = 10.0

data class CoincidenciaUbicacion(val id: String, val nombre: String, val distanciaM: Int)

fun buscarCoincidenciasUbicacion(
    coordenadas: String?,
    registros: List<MatrizEntity>,
    excluirId: String? = null,
    radioMetros: Double = RADIO_MISMA_UBICACION_M
): List<CoincidenciaUbicacion> {
    val origen = parseLatLngOrden(coordenadas) ?: return emptyList()
    return registros.asSequence()
        .filter { it.id != excluirId }
        .mapNotNull { r ->
            val p = parseLatLngOrden(r.ubicacion) ?: return@mapNotNull null
            val d = distanciaKm(origen, p) * 1000.0
            if (d <= radioMetros) CoincidenciaUbicacion(r.id, r.nombre, d.toInt()) else null
        }
        .sortedBy { it.distanciaM }
        .toList()
}

/** Lee todos los registros de Matriz desde Room (Flow) sin cambiar la firma de los diálogos. */
@Composable
fun rememberTodosMatriz(): List<MatrizEntity> {
    val context = LocalContext.current
    val flow = remember {
        (context.applicationContext as MainApplication).container.database.matrizDao().getAllMatriz()
    }
    return flow.collectAsState(initial = emptyList()).value
}

/** Calcula fuera del hilo principal para no trabar la escritura (cada tecla en el formulario). */
@Composable
fun rememberCoincidenciasUbicacion(coordenadas: String?, excluirId: String?): List<CoincidenciaUbicacion> {
    val todos = rememberTodosMatriz()
    return produceState(emptyList<CoincidenciaUbicacion>(), coordenadas, excluirId, todos) {
        value = withContext(Dispatchers.Default) { buscarCoincidenciasUbicacion(coordenadas, todos, excluirId) }
    }.value
}

@Composable
fun AvisoMismaUbicacion(coincidencias: List<CoincidenciaUbicacion>) {
    if (coincidencias.isEmpty()) return
    var abierto by remember(coincidencias) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { abierto = !abierto }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                val n = coincidencias.size
                Text(
                    if (n == 1) "Hay 1 registro más en esta ubicación" else "Hay $n registros más en esta ubicación",
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            if (abierto) {
                Spacer(Modifier.height(6.dp))
                coincidencias.forEach {
                    Text("• ${it.nombre} (${it.distanciaM} m)", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}


/** Abre WhatsApp con el texto listo para elegir contacto; si no está instalado, cae al selector. */
fun enviarTextoPorWhatsApp(context: android.content.Context, texto: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, texto.trimEnd())
    }
    try {
        intent.setPackage("com.whatsapp")
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            intent.setPackage(null)
            context.startActivity(Intent.createChooser(intent, "Compartir vía"))
        } catch (e2: Exception) {
            Toast.makeText(context, "No se pudo compartir: ${e2.message}", Toast.LENGTH_LONG).show()
        }
    }
}

private fun urlMapsDeUbicacion(ubicacion: String?): String? =
    if (ubicacion.isNullOrBlank() || ubicacion == "N/A") null
    else "https://maps.google.com/?q=${ubicacion.replace(" ", "")}"

private fun montoConSigno(texto: String): String {
    val numero = texto.replace("[^0-9.]".toRegex(), "").toDoubleOrNull() ?: return texto
    return "$" + java.text.NumberFormat.getNumberInstance(Locale("es", "MX")).apply { maximumFractionDigits = 0 }.format(numero)
}

/** Comparte por WhatsApp el registro de Matriz / Filtro Fecha (mismo estilo que la Solicitud):
 * cliente, CU, requerido, estado, hora de retorno (si hay), dirección con nombre de calle/colonia
 * y URL de Maps. [estado] y [hora] permiten mandar el valor que está en pantalla (editado y aún
 * sin guardar) en vez del guardado. Solo texto. */
suspend fun compartirMatrizPorWhatsApp(
    context: android.content.Context,
    item: MatrizEntity,
    estado: String = item.estado,
    hora: String? = item.hora
) {
    try {
        val (colonia, calle) = resolverColoniaYCalle(context, item.ubicacion)
        val direccion = listOfNotNull(calle, colonia).joinToString(", ")
        val sb = StringBuilder()
        sb.append("*Cliente:* ${item.nombre}\n")
        if (!item.folioP.isNullOrBlank()) sb.append("*CU:* ${item.folioP}\n")
        sb.append("*Requerido:* ${formatearMontoMatriz(item.requisito)}\n")
        if (estado.isNotBlank()) sb.append("*Estado:* $estado\n")
        if (!hora.isNullOrBlank()) sb.append("*Hora de retorno:* $hora\n")
        if (direccion.isNotBlank()) sb.append("*Dirección:* $direccion\n")
        urlMapsDeUbicacion(item.ubicacion)?.let { sb.append("*Ubicación:* $it\n") }
        enviarTextoPorWhatsApp(context, sb.toString())
    } catch (e: Exception) {
        Toast.makeText(context, "No se pudo compartir: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

/** Comparte por WhatsApp lo que se ve en el detalle de Semana 6, con los valores actuales de los
 * campos editables (aunque no se hayan guardado todavía). */
suspend fun compartirSem6PorWhatsApp(
    context: android.content.Context,
    item: Sem6Item,
    capital: String,
    seContiene: String,
    status: String,
    observaciones: String
) {
    try {
        val (coloniaGeo, calle) = resolverColoniaYCalle(context, item.ubicacion)
        val colonia = item.colonia.ifBlank { coloniaGeo ?: "" }
        val sb = StringBuilder()
        sb.append("*Cliente:* ${item.nombre}\n")
        sb.append("*Sem:* ${item.sem}  ·  *Req:* ${montoConSigno(item.req)}\n")
        if (capital.isNotBlank()) sb.append("*Capital:* ${montoConSigno(capital)}\n")
        if (item.cu.isNotBlank()) sb.append("*CU:* ${item.cu}\n")
        if (colonia.isNotBlank()) sb.append("*Colonia:* $colonia\n")
        if (!calle.isNullOrBlank()) sb.append("*Calle:* $calle\n")
        if (item.ultimaFechaVisita.isNotBlank()) sb.append("*Última vez:* ${item.ultimaFechaVisita}\n")
        sb.append("*Visitas:* ${item.visitas}\n")
        if (seContiene.isNotBlank()) sb.append("*Se Contiene:* ${montoConSigno(seContiene)}\n")
        if (status.isNotBlank()) sb.append("*Status:* $status\n")
        if (observaciones.isNotBlank()) sb.append("*Observaciones:* $observaciones\n")
        urlMapsDeUbicacion(item.ubicacion)?.let { sb.append("*Ubicación:* $it\n") }
        enviarTextoPorWhatsApp(context, sb.toString())
    } catch (e: Exception) {
        Toast.makeText(context, "No se pudo compartir: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
