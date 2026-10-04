package com.example.matrizapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupScreen(backupManager: BackupManager) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var backups by remember { mutableStateOf(backupManager.listLocalBackups()) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val formato = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "MX")) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Backup de Matriz", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Respaldo local automático y manual. Cuando hay conexión, la copia también se sube a Google Drive.")
        Spacer(Modifier.height(16.dp))
        Button(enabled = !working, onClick = {
            working = true
            message = null
            scope.launch {
                val result = runCatching { backupManager.createManualBackup() }
                message = result.fold(
                    { "Backup creado · " + if (it.uploadedToDrive) "Drive OK" else "Drive pendiente/sin conexión" },
                    { "No se pudo crear el backup: " + it.message }
                )
                backups = backupManager.listLocalBackups()
                working = false
            }
        }, modifier = Modifier.fillMaxWidth()) {
            if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Save, null)
            Spacer(Modifier.width(8.dp))
            Text("Crear Backup ahora")
        }
        Spacer(Modifier.height(8.dp))
        Text("Último local: " + (backupManager.lastLocalTime().takeIf { it > 0 }?.let(formato::format) ?: "Sin respaldo"))
        Text("Último Drive: " + (backupManager.lastDriveTime().takeIf { it > 0 }?.let(formato::format) ?: "Sin respaldo en Drive"))
        message?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(16.dp))
        Text("Respaldos locales", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (backups.isEmpty()) Text("Todavía no hay respaldos.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(backups, key = { it.file.absolutePath }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(formato.format(Date(item.createdAt)), style = MaterialTheme.typography.titleSmall)
                            Text(item.reason.replace('_', ' '), style = MaterialTheme.typography.bodySmall)
                            Text((item.file.length() / 1024).toString() + " KB", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(if (item.uploadedToDrive) Icons.Default.CloudDone else Icons.Default.CloudOff, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = {
                            working = true
                            scope.launch {
                                val result = backupManager.restoreLocalBackup(item.file)
                                message = result.fold(
                                    { "Backup restaurado. Reiniciando Matriz…" },
                                    { "No se pudo restaurar: " + it.message }
                                )
                                if (result.isSuccess) {
                                    // La base se reemplazó con la app abierta: se reinicia el proceso para cargarla limpia.
                                    kotlinx.coroutines.delay(800)
                                    val reinicio = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                    reinicio?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                    if (reinicio != null) context.startActivity(reinicio)
                                    android.os.Process.killProcess(android.os.Process.myPid())
                                }
                                working = false
                            }
                        }, enabled = !working) { Icon(Icons.Default.Restore, "Restaurar") }
                    }
                }
            }
        }
    }
}
