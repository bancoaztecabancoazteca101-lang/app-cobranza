package com.example.matrizapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class NotificacionesDispositivosActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 13+: pedir permiso para que FCM pueda mostrar avisos en la barra.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        setContent { NotificacionesDispositivosScreen() }
    }
}

@Composable
private fun NotificacionesDispositivosScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { MultiDeviceNotificationManager(context) }
    var name by remember { mutableStateOf(manager.getDeviceName()) }
    var enabled by remember { mutableStateOf(manager.isEnabled()) }
    var devices by remember { mutableStateOf<List<MultiDeviceNotificationManager.RemoteDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val isAdmin = (devices.find { it.deviceId == manager.installationId }?.isAdmin ?: false) ||
        manager.getDeviceName().contains("kingkong", ignoreCase = true)
    // El propio dispositivo ya está representado arriba en "Este dispositivo": no repetirlo
    // otra vez en la lista de abajo para no duplicar espacio e información.
    val otherDevices = devices.filterNot { it.deviceId == manager.installationId }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            manager.register()
            manager.listDevices().onSuccess { devices = it }.onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Notificaciones multi-dispositivo", style = MaterialTheme.typography.titleLarge)
        Text(
            "Avisos de RETORNO + hora por dispositivo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Este dispositivo", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "ID: ${manager.installationId.takeLast(6)}" + if (isAdmin) " · Admin" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre del dispositivo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Recibir notificaciones", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = enabled, onCheckedChange = {
                            enabled = it
                            manager.setEnabled(it)
                            scope.launch {
                                manager.setRemoteEnabled(manager.installationId, it)
                                    .onFailure { err -> Toast.makeText(context, err.message, Toast.LENGTH_LONG).show() }
                                refresh()
                            }
                        })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = {
                                manager.setDeviceName(name)
                                scope.launch {
                                    manager.register().onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                    refresh()
                                }
                            },
                            contentPadding = ButtonDefaults.TextButtonContentPadding
                        ) { Text("Guardar") }
                        Button(
                            onClick = {
                                scope.launch {
                                    manager.sendTest(manager.installationId)
                                        .onSuccess { Toast.makeText(context, "Prueba enviada", Toast.LENGTH_SHORT).show() }
                                        .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                }
                            },
                            contentPadding = ButtonDefaults.TextButtonContentPadding
                        ) { Text("Probar") }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Otros dispositivos", style = MaterialTheme.typography.titleSmall)
            if (loading) CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
        }
        error?.let { Text("Backend: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        if (isAdmin) {
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        manager.cleanupDuplicates()
                            .onSuccess { removed ->
                                val msg = if (removed > 0) "Se fusionaron $removed duplicados" else "No había duplicados"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                refresh()
                            }
                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                    }
                },
                contentPadding = ButtonDefaults.TextButtonContentPadding,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Fusionar duplicados") }
        }
        Spacer(Modifier.height(8.dp))

        if (otherDevices.isEmpty() && !loading) {
            Text(
                "No hay otros dispositivos registrados.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(otherDevices, key = { it.deviceId }) { device ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                device.name + if (device.isAdmin) " · Admin" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "ID: ${device.deviceId.takeLast(6)} · " +
                                    (if (device.enabled) "Activo" else "Desactivado") +
                                    (if (device.lastSeen.isNotBlank()) " · ${device.lastSeen.take(16).replace("T", " ")}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isAdmin) {
                            Switch(
                                checked = device.enabled,
                                onCheckedChange = { value ->
                                    scope.launch {
                                        manager.setRemoteEnabled(device.deviceId, value)
                                            .onSuccess { refresh() }
                                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                    }
                                }
                            )
                            IconButton(onClick = {
                                scope.launch {
                                    manager.deleteDevice(device.deviceId)
                                        .onSuccess { refresh() }
                                        .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                }
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Eliminar dispositivo")
                            }
                        }
                    }
                }
            }
        }
    }
}
