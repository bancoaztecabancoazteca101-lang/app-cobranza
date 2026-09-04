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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale
import java.util.UUID
import kotlin.math.*

private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
private const val SEARCH_RADIUS_METERS = 5000
private const val MAX_CHANNELS = 3
private const val TICKET_WIDTH = 32

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

data class PaymentChannel(
    val name: String,
    val type: String,
    val address: String,
    val distanceKm: Double,
    val lat: Double,
    val lng: Double
)

data class PaymentChannelSearchResult(
    val channels: List<PaymentChannel>,
    val error: String? = null
)

private fun parseCoordinates(text: String?): Pair<Double, Double>? {
    if (text.isNullOrBlank()) return null
    val match = Regex("(-?\\d{1,3}\\.\\d+)\\s*[,; ]\\s*(-?\\d{1,3}\\.\\d+)").find(text) ?: return null
    val lat = match.groupValues[1].toDoubleOrNull() ?: return null
    val lng = match.groupValues[2].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    return lat to lng
}

suspend fun resolverUbicacionCliente(context: Context, ubicacion: String?): Pair<Double, Double>? {
    parseCoordinates(ubicacion)?.let { return it }
    if (ubicacion.isNullOrBlank() || ubicacion.equals("N/A", true)) return null
    return geocodificarDireccion(context, ubicacion)
}

suspend fun buscarCanalesPagoCercanos(context: Context, ubicacion: String?): PaymentChannelSearchResult {
    val coords = resolverUbicacionCliente(context, ubicacion)
        ?: return PaymentChannelSearchResult(emptyList(), "No se pudo ubicar el domicilio del cliente.")
    return withContext(Dispatchers.IO) {
        try {
            val query = """
                [out:json][timeout:20];
                (
                  nwr(around:$SEARCH_RADIUS_METERS,${coords.first},${coords.second})["name"~"Elektra|Banco Azteca|Italika|Neto|OXXO|7-Eleven|Seven Eleven|Soriana|Chedraui",i];
                  nwr(around:$SEARCH_RADIUS_METERS,${coords.first},${coords.second})["brand"~"Elektra|Banco Azteca|Italika|Neto|OXXO|7-Eleven|Seven Eleven|Soriana|Chedraui",i];
                );
                out center tags;
            """.trimIndent()
            val connection = (URL(OVERPASS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 25000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("User-Agent", "MatrizApp/1.0")
            }
            connection.outputStream.use { out ->
                out.write(("data=" + java.net.URLEncoder.encode(query, "UTF-8")).toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            if (code !in 200..299) return@withContext PaymentChannelSearchResult(emptyList(), "No se pudo consultar el catálogo de lugares de pago ($code).")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            parseOverpassChannels(body, coords)
        } catch (e: Exception) {
            PaymentChannelSearchResult(emptyList(), "No fue posible consultar lugares de pago: ${e.message ?: "error de red"}")
        }
    }
}

private fun parseOverpassChannels(json: String, origin: Pair<Double, Double>): PaymentChannelSearchResult {
    val elements = JSONObject(json).optJSONArray("elements") ?: return PaymentChannelSearchResult(emptyList(), "La consulta no devolvió lugares de pago.")
    val candidates = mutableListOf<PaymentChannel>()
    for (i in 0 until elements.length()) {
        val element = elements.optJSONObject(i) ?: continue
        val tags = element.optJSONObject("tags") ?: continue
        val name = tags.optString("name").trim()
        val brand = tags.optString("brand").trim()
        val rawName = if (name.isNotBlank()) name else brand
        if (rawName.isBlank()) continue
        val type = classifyChannel(rawName, brand, tags.optString("operator")) ?: continue
        val lat = when {
            element.has("lat") -> element.optDouble("lat", Double.NaN)
            element.has("center") -> element.optJSONObject("center")?.optDouble("lat", Double.NaN) ?: Double.NaN
            else -> Double.NaN
        }
        val lng = when {
            element.has("lon") -> element.optDouble("lon", Double.NaN)
            element.has("center") -> element.optJSONObject("center")?.optDouble("lon", Double.NaN) ?: Double.NaN
            else -> Double.NaN
        }
        if (lat.isNaN() || lng.isNaN()) continue
        val distance = distanciaKm(origin, lat to lng)
        val address = buildAddress(tags)
        candidates += PaymentChannel(rawName, type, address, distance, lat, lng)
    }
    val unique = candidates
        .sortedBy { it.distanceKm }
        .distinctBy { "${it.name.lowercase(Locale.getDefault())}|${"%.5f".format(Locale.US, it.lat)}|${"%.5f".format(Locale.US, it.lng)}" }
        .take(MAX_CHANNELS)
    return if (unique.isEmpty()) PaymentChannelSearchResult(emptyList(), "No se encontraron lugares de pago cercanos en el catálogo disponible.")
    else PaymentChannelSearchResult(unique)
}

private fun classifyChannel(name: String, brand: String, operator: String): String? {
    val text = "$name $brand $operator".lowercase(Locale.getDefault())
    return when {
        "banco azteca" in text -> "Banco Azteca"
        "elektra" in text -> "Elektra"
        "italika" in text -> "Italika"
        "neto" in text -> "Tiendas Neto"
        "oxxo" in text -> "OXXO"
        "7-eleven" in text || "seven eleven" in text -> "7-Eleven"
        "soriana" in text -> "Soriana"
        "chedraui" in text -> "Chedraui"
        else -> null
    }
}

private fun buildAddress(tags: JSONObject): String {
    val parts = listOf(
        tags.optString("addr:street"),
        tags.optString("addr:housenumber"),
        tags.optString("addr:suburb"),
        tags.optString("addr:postcode")
    ).filter { it.isNotBlank() }
    return parts.joinToString(" ").ifBlank { "Dirección no disponible" }
}

private fun distanciaKm(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val earthKm = 6371.0088
    val dLat = Math.toRadians(b.first - a.first)
    val dLng = Math.toRadians(b.second - a.second)
    val aa = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) * sin(dLng / 2).pow(2.0)
    return earthKm * 2 * atan2(sqrt(aa), sqrt(1 - aa))
}

@SuppressLint("MissingPermission")
private fun pairedPrinters(context: Context): List<BluetoothDevice> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
    return adapter.bondedDevices.filter { device ->
        val name = device.name?.lowercase(Locale.getDefault()) ?: ""
        name.contains("printer") || name.contains("impres") || name.contains("pos") || name.contains("thermal") || name.contains("58") || name.contains("80")
    }.sortedBy { it.name ?: "" }
}

@Composable
fun PaymentChannelsDialog(
    customerName: String,
    ubicacion: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<PaymentChannelSearchResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var printing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var printers by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var selectedPrinter by remember { mutableStateOf<BluetoothDevice?>(null) }
    var permissionRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) grants[Manifest.permission.BLUETOOTH_CONNECT] == true else true
        if (granted) printers = pairedPrinters(context)
        else message = "Se necesita permiso de Bluetooth para detectar la impresora emparejada."
    }

    fun refreshPrinters() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        } else {
            printers = pairedPrinters(context)
        }
    }

    LaunchedEffect(ubicacion) {
        loading = true
        result = buscarCanalesPagoCercanos(context, ubicacion)
        loading = false
        refreshPrinters()
    }

    AlertDialog(
        onDismissRequest = { if (!printing) onDismiss() },
        title = { Text("Lugares de pago cercanos") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cliente: $customerName")
                Text("Por privacidad, el ticket NO imprime la dirección del cliente.", style = MaterialTheme.typography.bodySmall)
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.height(24.dp))
                        Text("Buscando los 3 lugares más cercanos…")
                    }
                } else {
                    result?.channels?.let { channels ->
                        LazyColumn(modifier = Modifier.height(230.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(channels) { channel ->
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Text(channel.name, style = MaterialTheme.typography.titleSmall)
                                    Text("${channel.type} · ${"%.2f".format(Locale.US, channel.distanceKm)} km")
                                    Text(channel.address, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    result?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Divider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Impresora Bluetooth", style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { refreshPrinters() }, enabled = !printing) { Icon(Icons.Default.Refresh, contentDescription = "Actualizar impresoras") }
                }
                if (printers.isEmpty()) {
                    Text("No hay una impresora térmica emparejada. Empareja la impresora desde Ajustes > Bluetooth y toca actualizar.", style = MaterialTheme.typography.bodySmall)
                } else {
                    printers.forEach { printer ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            RadioButton(selected = selectedPrinter?.address == printer.address, onClick = { selectedPrinter = printer })
                            Column {
                                Text(printer.name ?: "Impresora Bluetooth")
                                Text(printer.address, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                message?.let { Text(it, color = if (it.startsWith("Impresión")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val channelResult = result?.channels.orEmpty()
                    val printer = selectedPrinter
                    if (channelResult.isEmpty()) {
                        message = "Primero debemos encontrar al menos un lugar de pago."
                    } else if (printer == null) {
                        message = "Selecciona una impresora Bluetooth emparejada."
                    } else {
                        printing = true
                        message = null
                        scope.launch {
                            message = imprimirTicketCanalesPago(context, printer, customerName, channelResult)
                            printing = false
                        }
                    }
                },
                enabled = !loading && !printing && result?.channels?.isNotEmpty() == true
            ) {
                Icon(Icons.Default.Print, contentDescription = null)
                Spacer(Modifier.padding(3.dp))
                Text(if (printing) "Imprimiendo…" else "Imprimir ticket")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !printing) { Text("Cerrar") } }
    )
}

suspend fun imprimirTicketCanalesPago(
    context: Context,
    printer: BluetoothDevice,
    customerName: String,
    channels: List<PaymentChannel>
): String = withContext(Dispatchers.IO) {
    try {
        val manager = ThermalPrinterManager(context)
        manager.printPaymentChannelsTicket(printer, customerName, channels)
        "Impresión enviada correctamente."
    } catch (e: Exception) {
        "Error al imprimir: ${e.message ?: "no se pudo conectar con la impresora"}"
    }
}

private class ThermalPrinterManager(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun printPaymentChannelsTicket(device: BluetoothDevice, customerName: String, channels: List<PaymentChannel>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Permiso Bluetooth no concedido")
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: throw IllegalStateException("Este dispositivo no tiene Bluetooth")
        adapter.cancelDiscovery()
        val socket = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        } catch (_: Exception) {
            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        }
        socket.use { connected ->
            connected.outputStream.use { out ->
                sendTicket(out, customerName, channels)
                out.flush()
            }
        }
    }

    private fun sendTicket(out: OutputStream, customerName: String, channels: List<PaymentChannel>) {
        val cp1252 = Charset.forName("windows-1252")
        fun bytes(text: String): ByteArray = text.toByteArray(cp1252)
        fun write(text: String) { out.write(bytes(text)) }
        fun command(vararg values: Int) { out.write(values.map { it and 0xFF }.toIntArray().toByteArray()) }
        command(0x1B, 0x40)
        command(0x1B, 0x74, 16)
        command(0x1B, 0x61, 1)
        command(0x1B, 0x21, 0x08)
        write("PATRICIA NAVA ESCOBAR\n")
        command(0x1B, 0x21, 0x00)
        write("\n")
        command(0x1B, 0x61, 1)
        write("Ahora además puedes\n")
        write("pagar tu crédito Elektra\n")
        command(0x1B, 0x21, 0x08)
        write("muy cerca de tu domicilio:\n")
        command(0x1B, 0x21, 0x00)
        write("\n")
        command(0x1B, 0x61, 0)
        write("Cliente: $customerName\n")
        write("\n")
        channels.forEachIndexed { index, channel ->
            command(0x1B, 0x21, 0x08)
            write("${channel.name.take(TICKET_WIDTH)}\n")
            command(0x1B, 0x21, 0x00)
            write("${channel.type}\n")
            write("Distancia: ${"%.2f".format(Locale.US, channel.distanceKm)} KM\n")
            write(wrapText(channel.address, TICKET_WIDTH))
            write("\n")
            if (index != channels.lastIndex) write("--------------------------------\n")
        }
        command(0x1B, 0x61, 1)
        write("\n¿Dónde puedo hacer mis pagos?\n\n")
        writeQr(out, "https://www.elektra.mx/buscador-de-tiendas")
        write("\n")
        command(0x1B, 0x64, 5)
        command(0x1D, 0x56, 0)
    }

    private fun wrapText(text: String, width: Int): String {
        if (text.length <= width) return "$text\n"
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        for (word in words) {
            if (line.length + word.length + 1 > width && line.isNotEmpty()) {
                lines += line.toString()
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines.joinToString("\n") + "\n"
    }

    private fun writeQr(out: OutputStream, data: String) {
        val payload = data.toByteArray(Charsets.UTF_8)
        val storeLen = payload.size + 3
        out.write(byteArrayOf(0x1D, 0x28, 0x6B, (storeLen and 0xFF).toByte(), ((storeLen shr 8) and 0xFF).toByte(), 0x31, 0x50, 0x30) + payload)
        out.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00))
        out.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, 0x05))
        out.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x31))
        out.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30))
        out.write(byteArrayOf(0x0A))
    }
}
