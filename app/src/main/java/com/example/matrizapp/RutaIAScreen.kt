package com.example.matrizapp

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RutaIAScreen(viewModel: RutaIAViewModel, matrizViewModel: MatrizViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ruta by viewModel.rutaOrdenada.collectAsState()
    val procesando by viewModel.procesando.collectAsState()
    val progreso by viewModel.progreso.collectAsState()
    var estrategia by remember { mutableStateOf(EstrategiaRutaIA.INTELIGENTE) }
    var selectorEstrategia by remember { mutableStateOf(false) }
    var mostrarMapa by remember { mutableStateOf(false) }
    var mostrarAyuda by remember { mutableStateOf(false) }
    var minimoDiasTexto by remember { mutableStateOf("") }
    var minimoRequeridoTexto by remember { mutableStateOf("") }
    var exigirDireccion by remember { mutableStateOf(true) }
    var direccion by remember { mutableStateOf(DireccionOrdenRutaIA.ASC) }

    val importarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val minimoDias = minimoDiasTexto.trim().toIntOrNull()
        val minimoRequerido = minimoRequeridoTexto.trim().replace(",", ".").toDoubleOrNull()
        if (minimoDiasTexto.isNotBlank() && (minimoDias == null || minimoDias < 0)) {
            Toast.makeText(context, "Mínimo de atraso inválido", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult
        }
        if (minimoRequeridoTexto.isNotBlank() && (minimoRequerido == null || minimoRequerido < 0)) {
            Toast.makeText(context, "Mínimo requerido inválido", Toast.LENGTH_SHORT).show(); return@rememberLauncherForActivityResult
        }
        viewModel.importarJson(uri, estrategia, direccion, minimoDias, minimoRequerido, exigirDireccion) { exito, mensaje, advertencias ->
            val texto = if (exito) buildString { append(mensaje ?: "Ruta generada"); if (advertencias.isNotEmpty()) append("\nAdvertencias: ${advertencias.size}") } else mensaje ?: "No se pudo importar"
            Toast.makeText(context, texto, Toast.LENGTH_LONG).show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ruta IA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (ruta.isEmpty()) "Sin ruta cargada" else "${ruta.size} paradas", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { mostrarAyuda = true }) { Icon(Icons.Default.HelpOutline, "Ayuda") }
                    if (ruta.any { it.lat != null && it.lng != null }) IconButton(onClick = { mostrarMapa = true }) { Icon(Icons.Default.Map, "Mapa") }
                    if (ruta.isNotEmpty()) IconButton(onClick = { viewModel.limpiarRutaAhora() }) { Icon(Icons.Default.DeleteSweep, "Limpiar") }
                }
            }

            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Estrategia de ruta", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    ExposedDropdownMenuBox(expanded = selectorEstrategia, onExpandedChange = { selectorEstrategia = !selectorEstrategia }) {
                        OutlinedTextField(value = estrategia.etiqueta, onValueChange = {}, readOnly = true, label = { Text("Cómo ordenar") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(selectorEstrategia) }, modifier = Modifier.fillMaxWidth().menuAnchor())
                        ExposedDropdownMenu(expanded = selectorEstrategia, onDismissRequest = { selectorEstrategia = false }) {
                            EstrategiaRutaIA.values().forEach { opcion -> DropdownMenuItem(text = { Text(opcion.etiqueta) }, onClick = { estrategia = opcion; selectorEstrategia = false }) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Dirección de cercanía", fontWeight = FontWeight.Bold)
                    Text("Controla el sentido de la ruta inteligente.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (direccion == DireccionOrdenRutaIA.ASC) {
                            Button(onClick = { direccion = DireccionOrdenRutaIA.ASC }, modifier = Modifier.weight(1f)) { Text("Menor a mayor") }
                            OutlinedButton(onClick = { direccion = DireccionOrdenRutaIA.DESC }, modifier = Modifier.weight(1f)) { Text("Mayor a menor") }
                        } else {
                            OutlinedButton(onClick = { direccion = DireccionOrdenRutaIA.ASC }, modifier = Modifier.weight(1f)) { Text("Menor a mayor") }
                            Button(onClick = { direccion = DireccionOrdenRutaIA.DESC }, modifier = Modifier.weight(1f)) { Text("Mayor a menor") }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(when (estrategia) {
                        EstrategiaRutaIA.INTELIGENTE -> if (direccion == DireccionOrdenRutaIA.ASC) "GPS determina el inicio; después cada parada busca la siguiente más cercana." else "Se invierte la cadena de cercanía calculada desde el GPS para recorrerla en sentido contrario."
                        EstrategiaRutaIA.MAYOR_ATRASO -> "Prioriza los mayores días de atraso y usa cercanía para resolver el orden."
                        EstrategiaRutaIA.MAYOR_REQUERIDO -> "Prioriza mayor requerido/saldo y usa cercanía para resolver el orden."
                        EstrategiaRutaIA.PRIORIDAD_COBRANZA -> "Combina días de atraso y requerido como prioridad económica."
                    }, style = MaterialTheme.typography.bodySmall, color = Color.Gray)

                    Spacer(Modifier.height(12.dp))
                    Text("Filtros", fontWeight = FontWeight.Bold)
                    Text("Se aplican antes de ordenar la ruta y se combinan con AND.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = minimoDiasTexto, onValueChange = { minimoDiasTexto = it.filter(Char::isDigit) }, label = { Text("Atraso mínimo") }, placeholder = { Text("Opcional") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = minimoRequeridoTexto, onValueChange = { minimoRequeridoTexto = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, label = { Text("Requerido mínimo") }, placeholder = { Text("Opcional") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = exigirDireccion, onCheckedChange = { exigirDireccion = it })
                        Text("Excluir registros sin dirección válida")
                    }
                    Button(onClick = { importarLauncher.launch(arrayOf("application/json", "text/plain", "text/*")) }, modifier = Modifier.fillMaxWidth(), enabled = !procesando) {
                        Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Importar JSON de Gemini")
                    }
                }
            }

            if (ruta.isEmpty() && !procesando) {
                Box(Modifier.fillMaxSize().weight(1f), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Default.Route, null, tint = Color.Gray, modifier = Modifier.size(56.dp)); Spacer(Modifier.height(10.dp))
                        Text("Sube las fotos a Gemini, guarda el JSON y después impórtalo aquí.", textAlign = TextAlign.Center, color = Color.Gray)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(ruta, key = { _, item -> item.id }) { index, item ->
                        RutaIANuevaCard(item, index + 1, index > 0, index < ruta.lastIndex, { viewModel.alternarVisitado(item) }, { viewModel.moverManualmente(item.id, -1) }, { viewModel.moverManualmente(item.id, 1) }) {
                            if (item.cuMatrizMatch == null) Toast.makeText(context, "Cliente nuevo o sin coincidencia en Matriz", Toast.LENGTH_SHORT).show()
                            else scope.launch { viewModel.buscarMatrizPorId(item.cuMatrizMatch)?.let { Toast.makeText(context, "Coincide en Matriz: ${it.nombre}", Toast.LENGTH_SHORT).show() } }
                        }
                    }
                }
            }
        }
        if (procesando) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)), Alignment.Center) {
                Card(shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text(progreso.ifBlank { "Procesando..." }) } }
            }
        }
    }

    if (mostrarAyuda) AlertDialog(onDismissRequest = { mostrarAyuda = false }, title = { Text("Cómo funciona") }, text = { Text("1. Sube las fotos a Gemini.\n2. Pide el JSON con el formato de Ruta IA.\n3. Guarda el archivo.\n4. Selecciona estrategia, dirección y filtros.\n5. Pulsa Importar JSON.\n6. La app valida, cruza con Matriz, geocodifica, filtra y construye la ruta.\n\nGemini extrae los datos; la app decide qué registros pasan los filtros y el orden.") }, confirmButton = { TextButton(onClick = { mostrarAyuda = false }) { Text("Entendido") } })

    if (mostrarMapa) Dialog(onDismissRequest = { mostrarMapa = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        RutaIAMapaFullScreen(items = ruta, onCerrar = { mostrarMapa = false }, onMarcadorClick = { Toast.makeText(context, it.nombre, Toast.LENGTH_SHORT).show() })
    }
}

@Composable
private fun RutaIANuevaCard(item: RutaIAEntity, posicion: Int, puedeSubir: Boolean, puedeBajar: Boolean, onVisitado: () -> Unit, onSubir: () -> Unit, onBajar: () -> Unit, onMatriz: () -> Unit) {
    val visitado = item.estado.equals("Visitado", ignoreCase = true)
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = if (visitado) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onSubir, enabled = puedeSubir, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.KeyboardArrowUp, null) }
                Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) { Text("$posicion", fontWeight = FontWeight.Bold) }
                IconButton(onClick = onBajar, enabled = puedeBajar, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.KeyboardArrowDown, null) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.nombre, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (item.esNuevo) AssistChip(onClick = {}, label = { Text("Nuevo") })
                }
                Text(item.direccion, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Row(Modifier.padding(top = 5.dp)) {
                    item.diasAtraso?.let { Text("Atraso: $it d.  ", style = MaterialTheme.typography.labelSmall) }
                    item.pagoRequerido?.let { Text("Req.: $${"%,.0f".format(it)}", style = MaterialTheme.typography.labelSmall) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = onVisitado, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) { Text(if (visitado) "Visitado" else "Marcar visitado") }
                    OutlinedButton(onClick = onMatriz, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) { Text("Matriz") }
                }
            }
        }
    }
}