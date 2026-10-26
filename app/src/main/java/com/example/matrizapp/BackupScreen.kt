package com.example.matrizapp

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File
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
    // Restauración pendiente de confirmar: descripción + cómo obtener el archivo (local, Drive o importado).
    var pendiente by remember { mutableStateOf<Pair<String, suspend () -> File>?>(null) }
    var driveLista by remember { mutableStateOf<List<DriveBackupFile>?>(null) }
    val formato = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "MX")) }
    fun prepararRestauracion(descripcion: String, obtener: suspend () -> File) { pendiente = descripcion to obtener }

    fun reiniciarApp() {
        // La base se reemplazó con la app abierta: se reinicia el proceso para cargarla limpia.
        val reinicio = context.packageManager.getLaunchIntentForPackage(context.packageName)
        reinicio?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (reinicio != null) context.startActivity(reinicio)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    val importador = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            working = true
            message = "Validando el archivo…"
            runCatching { backupManager.importBackup(uri) }
                .onSuccess { archivo ->
                    backups = backupManager.listLocalBackups()
                    message = null
                    prepararRestauracion("el archivo importado") { archivo }
                }
                .onFailure { message = "No se pudo importar: " + it.message }
            working = false
        }
    }

    pendiente?.let { (descripcion, obtener) ->
        AlertDialog(
            onDismissRequest = { pendiente = null },
            title = { Text("¿Restaurar este respaldo?") },
            text = {
                Text("Se reemplaza TODA la información de este teléfono con $descripcion. Lo que aquí no esté en ese respaldo (y no se haya sincronizado) se pierde. Antes se guarda una copia automática de lo actual (\"antes restaurar\"), y Matriz se reiniciará.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendiente = null
                    scope.launch {
                        working = true
                        message = "Preparando el respaldo…"
                        val archivo = runCatching { obtener() }
                        if (archivo.isFailure) {
                            message = "No se pudo obtener el respaldo: " + archivo.exceptionOrNull()?.message
                            working = false
                            return@launch
                        }
                        val result = backupManager.restoreLocalBackup(archivo.getOrThrow())
                        backups = backupManager.listLocalBackups()
                        message = result.fold(
                            { "Backup restaurado. Reiniciando Matriz…" },
                            { "No se pudo restaurar: " + it.message }
                        )
                        if (result.isSuccess) {
                            kotlinx.coroutines.delay(800)
                            reiniciarApp()
                        }
                        working = false
                    }
                }) { Text("Restaurar") }
            },
            dismissButton = { TextButton(onClick = { pendiente = null }) { Text("Cancelar") } }
        )
    }

    driveLista?.let { lista ->
        AlertDialog(
            onDismissRequest = { driveLista = null },
            title = { Text("Respaldos en Drive") },
            text = {
                if (lista.isEmpty()) Text("No hay respaldos en la carpeta de Drive de esta cuenta.")
                else LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(lista, key = { it.id }) { f ->
                        TextButton(onClick = {
                            driveLista = null
                            prepararRestauracion("el respaldo de Drive del " + formato.format(Date(f.createdMillis)) + " (" + f.name.substringAfterLast("_").removeSuffix(".zip").replace('_', ' ') + ")") { backupManager.downloadDriveBackup(f) }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(formato.format(Date(f.createdMillis)), style = MaterialTheme.typography.titleSmall)
                                Text(f.name.substringAfterLast("_").removeSuffix(".zip").replace('_', ' ') + " · " + (f.sizeBytes / 1024) + " KB", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { driveLista = null }) { Text("Cerrar") } }
        )
    }

    // Todo dentro de un LazyColumn: con los botones nuevos la pantalla sigue desplazándose completa.
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Backup de Matriz", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Respaldo local automático y manual. Cuando hay conexión, la copia también se sube a Google Drive. Para pasar datos de un teléfono a otro: crea el backup en el primero y restáuralo en el segundo desde Drive (misma cuenta de Google) o importando el .zip.")
        }
        item {
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
        }
        item {
            OutlinedButton(enabled = !working, onClick = {
                working = true
                message = "Consultando Drive…"
                scope.launch {
                    runCatching { backupManager.listDriveBackups() }
                        .onSuccess { message = null; driveLista = it }
                        .onFailure { message = "No se pudo consultar Drive (revisa la conexión): " + it.message }
                    working = false
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CloudDownload, null)
                Spacer(Modifier.width(8.dp))
                Text("Restaurar desde Drive")
            }
        }
        item {
            OutlinedButton(enabled = !working, onClick = {
                importador.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("Importar respaldo (.zip)")
            }
        }
        item {
            Text("Último local: " + (backupManager.lastLocalTime().takeIf { it > 0 }?.let(formato::format) ?: "Sin respaldo"))
            Text("Último Drive: " + (backupManager.lastDriveTime().takeIf { it > 0 }?.let(formato::format) ?: "Sin respaldo en Drive"))
            message?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.primary) }
        }
        item { Text("Respaldos locales", style = MaterialTheme.typography.titleMedium) }
        if (backups.isEmpty()) item { Text("Todavía no hay respaldos.") }
        else items(backups, key = { it.file.absolutePath }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(formato.format(Date(item.createdAt)), style = MaterialTheme.typography.titleSmall)
                        Text(item.reason.replace('_', ' '), style = MaterialTheme.typography.bodySmall)
                        Text((item.file.length() / 1024).toString() + " KB", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(if (item.uploadedToDrive) Icons.Default.CloudDone else Icons.Default.CloudOff, contentDescription = if (item.uploadedToDrive) "Está en Drive" else "No está en Drive")
                    IconButton(onClick = {
                        scope.launch {
                            runCatching {
                                val copia = backupManager.prepararParaCompartir(item.file)
                                val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.example.matrizapp.fileprovider", copia)
                                val enviar = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(enviar, "Compartir respaldo"))
                            }.onFailure { message = "No se pudo compartir: " + it.message }
                        }
                    }, enabled = !working) { Icon(Icons.Default.Share, "Compartir") }
                    IconButton(onClick = {
                        prepararRestauracion("el respaldo local del " + formato.format(Date(item.createdAt))) { item.file }
                    }, enabled = !working) { Icon(Icons.Default.Restore, "Restaurar") }
                }
            }
        }
    }
}
