package com.example.matrizapp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.UUID
import kotlin.math.*


private const val SEARCH_RADIUS_METERS = 5000
private const val MAX_CHANNELS = 3
private const val TICKET_WIDTH = 32
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
/** Si el catálogo local nunca se ha sincronizado o ya tiene más de esto sin refrescar, se
 * intenta una sincronización con la hoja antes de buscar (best-effort, no bloquea si falla). */
private const val CATALOGO_MAX_EDAD_MS = 30L * 24 * 60 * 60 * 1000 // 30 días

data class PaymentChannel(val name: String, val type: String, val categoria: CategoriaCanalPago, val address: String, val distanceKm: Double, val lat: Double, val lng: Double)
data class PaymentChannelSearchResult(val channels: List<PaymentChannel>, val error: String? = null)

/** PRINCIPAL = canales propios de Grupo Elektra (Elektra, Elektra Motos, Banco Azteca, Italika,
 * Tiendas Neto). AFILIADO = puntos de pago de terceros (OXXO, 7-Eleven, Soriana, Chedraui) --
 * se muestran aparte porque no son "canal autorizado" en el mismo sentido, son solo lugares
 * donde también se puede pagar. */
enum class CategoriaCanalPago(val etiqueta: String) {
    PRINCIPAL("Canal Elektra"),
    AFILIADO("Punto de pago afiliado")
}

private fun parseCoordinates(text: String?): Pair<Double, Double>? {
    if (text.isNullOrBlank()) return null
    val m = Regex("(-?\\d{1,3}\\.\\d+)\\s*[,; ]\\s*(-?\\d{1,3}\\.\\d+)").find(text) ?: return null
    val lat = m.groupValues[1].toDoubleOrNull() ?: return null
    val lng = m.groupValues[2].toDoubleOrNull() ?: return null
    return if (lat in -90.0..90.0 && lng in -180.0..180.0) lat to lng else null
}

suspend fun resolverUbicacionCliente(context: Context, ubicacion: String?): Pair<Double, Double>? {
    parseCoordinates(ubicacion)?.let { return it }
    if (ubicacion.isNullOrBlank() || ubicacion.equals("N/A", true)) return null
    return geocodificarDireccion(context, ubicacion)
}

/** Busca en el catálogo LOCAL (tabla canal_pago_table, sincronizada desde la hoja "Catálogo
 * Canales Pago") en vez de hablarle a Overpass en vivo -- así la búsqueda es instantánea y no
 * depende de que la red del teléfono en campo deje salir a servidores externos (ver
 * PLAN_TICKET_VISTA_PREVIA.md, incidente de timeout persistente en los 3 espejos de Overpass).
 * El catálogo lo llena AppsScript/SincronizarCanalesPago.gs corriendo del lado de Google, y la
 * app solo lo sincroniza vía Sheets API (que sí funciona en este teléfono, es lo mismo que usa
 * Matriz/Pase de Cartera/etc). Si el catálogo local está vacío se intenta sincronizar una vez
 * en el momento; si ya no está vacío pero es viejo, se dispara un refresh en segundo plano sin
 * bloquear la búsqueda actual (mejora la próxima vez, no esta). */
suspend fun buscarCanalesPagoCercanos(context: Context, ubicacion: String?): PaymentChannelSearchResult {
    val coords = resolverUbicacionCliente(context, ubicacion)
        ?: return PaymentChannelSearchResult(emptyList(), "No se pudo ubicar el domicilio del cliente.")
    val repository = (context.applicationContext as MainApplication).container.repository
    var catalogo = withContext(Dispatchers.IO) { repository.catalogoCanalesPagoLocal() }
    if (catalogo.isEmpty()) {
        val sync = withTimeoutOrNull(15000) { withContext(Dispatchers.IO) { repository.sincronizarCatalogoCanalesPago() } }
        if (sync?.isSuccess == true) catalogo = withContext(Dispatchers.IO) { repository.catalogoCanalesPagoLocal() }
        else return PaymentChannelSearchResult(
            emptyList(),
            "El catálogo de lugares de pago está vacío y no se pudo sincronizar" +
                (sync?.exceptionOrNull()?.message?.let { ": $it" } ?: " (sin conexión).")
        )
    } else if (withContext(Dispatchers.IO) { repository.catalogoCanalesPagoDesactualizado(CATALOGO_MAX_EDAD_MS) }) {
        CoroutineScope(Dispatchers.IO).launch { runCatching { repository.sincronizarCatalogoCanalesPago() } }
    }
    val candidatos = catalogo.mapNotNull { c ->
        val clasificacion = classifyChannel(c.nombre, c.empresa ?: "", "") ?: return@mapNotNull null
        val distancia = distanciaKm(coords, c.lat to c.lng)
        if (distancia * 1000 > SEARCH_RADIUS_METERS * 3) return@mapNotNull null // margen amplio, el catálogo ya viene acotado a la zona
        PaymentChannel(if (c.nombre.isNotBlank()) c.nombre else clasificacion.tipo, clasificacion.tipo, clasificacion.categoria, c.direccion?.takeIf { it.isNotBlank() } ?: "Dirección no disponible", distancia, c.lat, c.lng)
    }
    val unique = candidatos.sortedBy { it.distanceKm }
        .distinctBy { "${it.name.lowercase(Locale.getDefault())}|${"%.5f".format(Locale.US, it.lat)}|${"%.5f".format(Locale.US, it.lng)}" }
        .take(MAX_CHANNELS)
    return if (unique.isEmpty()) PaymentChannelSearchResult(emptyList(), "No se encontraron lugares de pago cercanos en el catálogo disponible.") else PaymentChannelSearchResult(unique)
}

/** Texto plano equivalente a lo que sendTicket() manda a la impresora (mismos saltos de línea,
 * mismo wrapText, mismo orden), pero sin comandos ESC/POS ni QR real -- sirve para que Diego
 * vea cómo va a quedar el ticket sin necesidad de tener la impresora conectada. Si se cambia el
 * formato en sendTicket(), hay que reflejarlo aquí también (ver PLAN_TICKET_VISTA_PREVIA.md). */
fun buildTicketPreviewText(customerName: String, channels: List<PaymentChannel>): String {
    val sb = StringBuilder()
    sb.append(customerName).append("\n\n")
    sb.append("Ahora además puedes\npagar tu crédito Elektra\n")
    sb.append("muy cerca de tu domicilio:\n\n")
    channels.forEachIndexed { i, ch ->
        sb.append(ch.name.take(TICKET_WIDTH)).append("\n")
        sb.append(ch.categoria.etiqueta).append("\n")
        sb.append("Distancia: ").append("%.2f".format(Locale.US, ch.distanceKm)).append(" KM\n")
        sb.append(wrapTextPreview(ch.address, TICKET_WIDTH)).append("\n")
        if (i != channels.lastIndex) sb.append("--------------------------------\n")
    }
    sb.append("\n¿Dónde puedo hacer mis pagos?\n\n")
    sb.append("[código QR: elektra.mx/buscador-de-tiendas]\n")
    return sb.toString()
}

/** Copia de ThermalPrinterManager.wrapText() -- se duplica a propósito porque esa es privada
 * dentro de la clase que habla con el socket Bluetooth, y la vista previa no debe depender de
 * esa clase (no necesita permisos de Bluetooth para mostrarse). */
private fun wrapTextPreview(text: String, width: Int): String {
    if (text.length <= width) return "$text\n"
    val lines = mutableListOf<String>(); var line = ""
    for (word in text.split(" ")) {
        if (line.isNotEmpty() && line.length + word.length + 1 > width) { lines += line; line = "" }
        if (line.isNotEmpty()) line += " "; line += word
    }
    if (line.isNotEmpty()) lines += line
    return lines.joinToString("\n") + "\n"
}

private data class ClasificacionCanal(val tipo: String, val categoria: CategoriaCanalPago)

/** Clasificación por texto, sin buscar exactitud de sucursal -- solo qué cadena es y si es
 * canal principal (Grupo Elektra) o afiliado (tercero). "Elektra Motos" se revisa antes que
 * "Elektra" a secas porque si no, cualquier local con "Elektra Motos" en el nombre cae
 * genérico en "Elektra" y se pierde el caso Motos. */
private fun classifyChannel(name: String, brand: String, operator: String): ClasificacionCanal? {
    val t = "$name $brand $operator".lowercase(Locale.getDefault())
    return when {
        "elektra motos" in t -> ClasificacionCanal("Elektra Motos", CategoriaCanalPago.PRINCIPAL)
        "banco azteca" in t -> ClasificacionCanal("Banco Azteca", CategoriaCanalPago.PRINCIPAL)
        "elektra" in t -> ClasificacionCanal("Elektra", CategoriaCanalPago.PRINCIPAL)
        "italika" in t -> ClasificacionCanal("Italika", CategoriaCanalPago.PRINCIPAL)
        "neto" in t -> ClasificacionCanal("Tiendas Neto", CategoriaCanalPago.PRINCIPAL)
        "oxxo" in t -> ClasificacionCanal("OXXO", CategoriaCanalPago.AFILIADO)
        "7-eleven" in t || "seven eleven" in t -> ClasificacionCanal("7-Eleven", CategoriaCanalPago.AFILIADO)
        "soriana" in t -> ClasificacionCanal("Soriana", CategoriaCanalPago.AFILIADO)
        "chedraui" in t -> ClasificacionCanal("Chedraui", CategoriaCanalPago.AFILIADO)
        else -> null
    }
}

@SuppressLint("MissingPermission")
private fun pairedPrinters(context: Context): List<BluetoothDevice> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
    return adapter.bondedDevices.filter { d ->
        val n = d.name?.lowercase(Locale.getDefault()) ?: ""
        n.contains("printer") || n.contains("impres") || n.contains("pos") || n.contains("thermal") || n.contains("58") || n.contains("80")
    }.sortedBy { it.name ?: "" }
}

@Composable
fun PaymentChannelsDialog(customerName: String, ubicacion: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<PaymentChannelSearchResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var printing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var printers by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var selectedPrinter by remember { mutableStateOf<BluetoothDevice?>(null) }
    var showPreview by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || grants[Manifest.permission.BLUETOOTH_CONNECT] == true) printers = pairedPrinters(context)
        else message = "Se necesita permiso de Bluetooth para detectar la impresora emparejada."
    }
    fun refreshPrinters() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        else printers = pairedPrinters(context)
    }
    // Antes refreshPrinters() corría al final del mismo LaunchedEffect que la búsqueda de
    // sucursales -- si esa búsqueda se colgaba (ver buscarCanalesPagoCercanos), la impresora
    // nunca se detectaba aunque estuviera emparejada. Ahora corre aparte, de inmediato.
    LaunchedEffect(Unit) { refreshPrinters() }
    LaunchedEffect(ubicacion) {
        loading = true; result = buscarCanalesPagoCercanos(context, ubicacion); loading = false
    }

    AlertDialog(
        onDismissRequest = { if (!printing) onDismiss() },
        title = { Text("Lugares de pago cercanos") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cliente: $customerName")
                Text("La dirección del cliente NO se imprime por privacidad.", style = MaterialTheme.typography.bodySmall)
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp)); Text("Buscando los 3 lugares más cercanos…")
                    }
                } else {
                    result?.channels?.let { channels ->
                        LazyColumn(modifier = Modifier.height(230.dp)) {
                            items(channels) { ch ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(ch.name, style = MaterialTheme.typography.titleSmall)
                                    Text("${ch.type} · ${ch.categoria.etiqueta} · ${"%.2f".format(Locale.US, ch.distanceKm)} km")
                                    Text(ch.address, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    result?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (result?.channels?.isNotEmpty() == true) {
                        TextButton(onClick = { showPreview = !showPreview }) {
                            Text(if (showPreview) "Ocultar vista previa del ticket" else "Ver vista previa del ticket")
                        }
                        if (showPreview) {
                            Text(
                                buildTicketPreviewText(customerName, result?.channels.orEmpty()),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState())
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(8.dp)
                            )
                        }
                    }
                }
                Divider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Impresora Bluetooth", style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { refreshPrinters() }, enabled = !printing) { Icon(Icons.Default.Refresh, contentDescription = "Actualizar impresoras") }
                }
                if (printers.isEmpty()) Text("Empareja la impresora térmica desde Ajustes > Bluetooth y toca actualizar.", style = MaterialTheme.typography.bodySmall)
                printers.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = selectedPrinter?.address == p.address, onClick = { selectedPrinter = p })
                        Column { Text(p.name ?: "Impresora Bluetooth"); Text(p.address, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                message?.let { Text(it, color = if (it.startsWith("Impresión")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !loading && !printing && result?.channels?.isNotEmpty() == true, onClick = {
                val channels = result?.channels.orEmpty(); val printer = selectedPrinter
                when {
                    channels.isEmpty() -> message = "Primero debemos encontrar lugares de pago."
                    printer == null -> message = "Selecciona una impresora Bluetooth emparejada."
                    else -> { printing = true; scope.launch { message = imprimirTicketCanalesPago(context, printer, customerName, channels); printing = false } }
                }
            }) {
                Icon(Icons.Default.Print, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(if (printing) "Imprimiendo…" else "Imprimir ticket")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !printing) { Text("Cerrar") } }
    )
}

suspend fun imprimirTicketCanalesPago(context: Context, printer: BluetoothDevice, customerName: String, channels: List<PaymentChannel>): String = withContext(Dispatchers.IO) {
    try { ThermalPrinterManager(context).printPaymentChannelsTicket(printer, customerName, channels); "Impresión enviada correctamente." }
    catch (e: Exception) { "Error al imprimir: ${e.message ?: "no se pudo conectar con la impresora"}" }
}

private class ThermalPrinterManager(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun printPaymentChannelsTicket(device: BluetoothDevice, customerName: String, channels: List<PaymentChannel>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) throw SecurityException("Permiso Bluetooth no concedido")
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: throw IllegalStateException("Este dispositivo no tiene Bluetooth")
        adapter.cancelDiscovery()
        val socket = try { device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() } }
        catch (_: Exception) { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() } }
        socket.use { it.outputStream.use { out -> sendTicket(out, customerName, channels); out.flush() } }
    }

    private fun sendTicket(out: OutputStream, customerName: String, channels: List<PaymentChannel>) {
        val cp1252 = Charset.forName("windows-1252")
        fun write(s: String) { out.write(s.toByteArray(cp1252)) }
        fun cmd(vararg v: Int) { v.forEach { out.write(it and 0xFF) } }
        cmd(0x1B,0x40); cmd(0x1B,0x74,16); cmd(0x1B,0x61,1); cmd(0x1B,0x21,8)
        write("$customerName\n")
        cmd(0x1B,0x21,0); write("\nAhora además puedes\npagar tu crédito Elektra\n")
        cmd(0x1B,0x21,8); write("muy cerca de tu domicilio:\n"); cmd(0x1B,0x21,0); write("\n")
        channels.forEachIndexed { i, ch ->
            cmd(0x1B,0x61,0); cmd(0x1B,0x21,8); write("${ch.name.take(TICKET_WIDTH)}\n"); cmd(0x1B,0x21,0)
            write("${ch.categoria.etiqueta}\nDistancia: ${"%.2f".format(Locale.US, ch.distanceKm)} KM\n")
            write(wrapText(ch.address, TICKET_WIDTH)); write("\n")
            if (i != channels.lastIndex) write("--------------------------------\n")
        }
        cmd(0x1B,0x61,1); write("\n¿Dónde puedo hacer mis pagos?\n\n")
        writeQr(out, "https://www.elektra.mx/buscador-de-tiendas")
        write("\n\n"); cmd(0x1B,0x64,5); cmd(0x1D,0x56,0)
    }

    private fun wrapText(text: String, width: Int): String {
        if (text.length <= width) return "$text\n"
        val lines = mutableListOf<String>(); var line = ""
        for (word in text.split(" ")) {
            if (line.isNotEmpty() && line.length + word.length + 1 > width) { lines += line; line = "" }
            if (line.isNotEmpty()) line += " "; line += word
        }
        if (line.isNotEmpty()) lines += line
        return lines.joinToString("\n") + "\n"
    }

    private fun writeQr(out: OutputStream, data: String) {
        val p = data.toByteArray(Charsets.UTF_8); val n = p.size + 3
        fun c(vararg v: Int) { v.forEach { out.write(it and 0xFF) } }
        c(0x1D,0x28,0x6B,4,0,49,65,50,0); c(0x1D,0x28,0x6B,3,0,49,67,5); c(0x1D,0x28,0x6B,3,0,49,69,49)
        out.write(byteArrayOf(0x1D,0x28,0x6B,(n and 0xFF).toByte(),((n shr 8) and 0xFF).toByte(),49,80,48) + p)
        c(0x1D,0x28,0x6B,3,0,49,81,48); c(10)
    }
}