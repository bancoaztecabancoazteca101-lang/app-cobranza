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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Notificaciones multi-dispositivo", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Activa o desactiva qué dispositivos recibirán los avisos de RETORNO + hora.")
        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Este dispositivo", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre del dispositivo") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Recibir notificaciones")
                    Switch(checked = enabled, onCheckedChange = {
                        enabled = it
                        manager.setEnabled(it)
                        scope.launch { manager.register() }
                    })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        manager.setDeviceName(name)
                        scope.launch {
                            manager.register().onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                            refresh()
                        }
                    }) { Text("Guardar") }
                    Button(onClick = {
                        scope.launch {
                            manager.sendTest(manager.installationId)
                                .onSuccess { Toast.makeText(context, "Prueba enviada", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        }
                    }) { Text("Probar") }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Dispositivos registrados", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        if (loading) CircularProgressIndicator()
        error?.let { Text("Backend: $it", color = MaterialTheme.colorScheme.error) }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices) { device ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(if (device.deviceId == manager.installationId) "${device.name} (este dispositivo)" else device.name)
                            Text(if (device.enabled) "Recibe notificaciones" else "Desactivado", style = MaterialTheme.typography.bodySmall)
                        }
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
                    }
                }
            }
        }
    }
}
