package com.example.matrizapp

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SmsStatusScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { MultiDeviceNotificationManager(context) }
    var statuses by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var selectedDeviceId by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf<List<MultiDeviceNotificationManager.RemoteDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var lineas by remember { mutableStateOf(SmsHelper.lineasActivas(context)) }
    val prefs = remember { context.getSharedPreferences("sms_status_config", android.content.Context.MODE_PRIVATE) }
    var subId by remember { mutableStateOf(prefs.getInt("subscriptionId", -1).let { if (it < 0) null else it }) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { lineas = SmsHelper.lineasActivas(context) }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            val config = manager.smsStatusConfig()
            val devs = manager.listDevices()
            config.onSuccess {
                statuses = it.statuses
                selectedDeviceId = it.selectedDeviceId
            }.onFailure { error = it.message }
            devs.onSuccess { devices = it }.onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (!SmsHelper.tienePermisos(context)) {
            permLauncher.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE))
        }
        refresh()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Regresar") }
            Icon(Icons.Default.Sms, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("SMS por Status APP", style = MaterialTheme.typography.titleLarge)
        }

        Text(
            "Solo el dispositivo seleccionado enviará SMS cuando el status tenga su interruptor activado.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 48.dp, bottom = 12.dp)
        )

        error?.let {
            Text(it ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Status que disparan SMS", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
            }

            if (loading && statuses.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            items(statuses.keys.sorted()) { status ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(status, style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = statuses[status] == true,
                            onCheckedChange = { value ->
                                statuses = statuses.toMutableMap().apply { put(status, value) }
                                scope.launch {
                                    manager.setSmsStatus(status, value)
                                        .onFailure {
                                            Toast.makeText(context, it.message ?: "No se pudo guardar", Toast.LENGTH_LONG).show()
                                            refresh()
                                        }
                                }
                            }
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(10.dp))
                Text("Dispositivo que enviará SMS por Status", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Solo uno puede estar activo. Al activar otro, el anterior queda desactivado.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
            }

            items(devices, key = { it.deviceId }) { device ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                (if (device.enabled) "Activo" else "Desactivado") + " · ID " + device.deviceId.takeLast(6),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = selectedDeviceId == device.deviceId,
                            enabled = device.enabled,
                            onCheckedChange = { value ->
                                scope.launch {
                                    manager.setSmsStatusDevice(device.deviceId, value)
                                        .onSuccess {
                                            selectedDeviceId = if (value) device.deviceId else ""
                                            refresh()
                                        }
                                        .onFailure {
                                            Toast.makeText(context, it.message ?: "No se pudo guardar", Toast.LENGTH_LONG).show()
                                        }
                                }
                            }
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(10.dp))
                Text("Línea para SMS por Status", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Esta línea se guarda en este dispositivo y se usa cuando este teléfono sea el seleccionado.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))

                if (lineas.isEmpty()) {
                    OutlinedButton(onClick = {
                        permLauncher.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE))
                    }) { Text("Conceder permisos / actualizar SIM") }
                } else {
                    lineas.forEach { linea ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            RadioButton(
                                selected = subId == linea.subscriptionId,
                                onClick = {
                                    subId = linea.subscriptionId
                                    prefs.edit().putInt("subscriptionId", linea.subscriptionId).apply()
                                }
                            )
                            Text(linea.etiqueta)
                        }
                    }
                }
            }
        }
    }
}
