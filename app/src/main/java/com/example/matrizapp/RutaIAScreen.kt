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

    val importarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.importarJson(uri, estrategia) { exito, mensaje, advertencias ->
            if (exito) {
                val texto = buildString {
                    append(mensaje ?: "Ruta generada")
                    if (advertencias.isNotEmpty()) append("\nAdvertencias: ${advertencias.size}")
                }
                Toast.makeText(context, texto, Toast.LENGTH_LONG).show()
            } else Toast.makeText(context, mensaje ?: "No se pudo importar", Toast.LENGTH_LONG).show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Ruta IA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (ruta.isEmpty()) "Sin ruta cargada" else "${ruta.size} paradas", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        OutlinedTextField(
                            value = estrategia.etiqueta,
                            onValueChange = {}, readOnly = true,
                            label = { Text("Cómo ordenar") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(selectorEstrategia) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(expanded = selectorEstrategia, onDismissRequest = { selectorEstrategia = false }) {
                            EstrategiaRutaIA.values().forEach { opcion ->
                                DropdownMenuItem(text = { Text(opcion.etiqueta) }, onClick = { estrategia = opcion; selectorEstrategia = false })
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when (estrategia) {
                            EstrategiaRutaIA.INTELIGENTE -> "GPS determina la primera parada; después cada parada busca la siguiente cercana. Atraso y requerido ayudan a desempatar."
                            EstrategiaRutaIA.MAYOR_ATRASO -> "Prioriza los mayores días de atraso y usa cercanía para resolver el orden."
                            EstrategiaRutaIA.MAYOR_REQUERIDO -> "Prioriza mayor requerido/saldo y usa cercanía para resolver el orden."
                            EstrategiaRutaIA.PRIORIDAD_COBRANZA -> "Combina días de atraso y requerido como prioridad económica."
                        }, style = MaterialTheme.typography.bodySmall, color = Color.Gray
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { importarLauncher.launch(arrayOf("application/json", "text/plain", "text/*")) },
                        modifier = Modifier.fillMaxWidth(), enabled = !procesando
                    ) {
                        Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Importar JSON de Gemini")
                    }
                }
            }

            if (ruta.isEmpty() && !procesando) {
                Box(Modifier.fillMaxSize().weight(1f), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Default.Route, null, tint = Color.Gray, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Sube a Gemini las fotos de Clientes de cobranza, guarda el JSON y después impórtalo aquí.", textAlign = TextAlign.Center, color = Color.Gray)
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(ruta, key = { _, item -> item.id }) { index, item ->
                        RutaIANuevaCard(
                            item = item,
                            posicion = index + 1,
                            puedeSubir = index > 0,
                            puedeBajar = index < ruta.lastIndex,
                            onVisitado = { viewModel.alternarVisitado(item) },
                            onSubir = { viewModel.moverManualmente(item.id, -1) },
                            onBajar = { viewModel.moverManualmente(item.id, 1) },
                            onMatriz = {
                                if (item.cuMatrizMatch == null) Toast.makeText(context, "Cliente nuevo o sin coincidencia en Matriz", Toast.LENGTH_SHORT).show()
                                else scope.launch { viewModel.buscarMatrizPorId(item.cuMatrizMatch)?.let { Toast.makeText(context, "Coincide en Matriz: ${it.nombre}", Toast.LENGTH_SHORT).show() } }
                            }
                        )
                    }
                }
            }
        }

        if (procesando) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)), Alignment.Center) {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text(progreso.ifBlank { "Procesando..." })
                    }
                }
            }
        }
    }

    if (mostrarAyuda) {
        AlertDialog(
            onDismissRequest = { mostrarAyuda = false },
            title = { Text("Cómo funciona") },
            text = { Text("1. Sube las fotos a Gemini.\n2. Pide el JSON con el formato de Ruta IA.\n3. Guarda el archivo.\n4. Selecciona la estrategia y pulsa Importar JSON.\n5. La app valida, cruza con Matriz, geocodifica y construye la ruta.\n\nGemini extrae los datos; la app decide el orden.") },
            confirmButton = { TextButton(onClick = { mostrarAyuda = false }) { Text("Entendido") } }
        )
    }

    if (mostrarMapa) Dialog(onDismissRequest = { mostrarMapa = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        RutaIAMapaFullScreen(items = ruta, onCerrar = { mostrarMapa = false }, onMarcadorClick = {
            Toast.makeText(context, it.nombre, Toast.LENGTH_SHORT).show()
        })
    }
}

@Composable
private fun RutaIANuevaCard(
    item: RutaIAEntity, posicion: Int, puedeSubir: Boolean, puedeBajar: Boolean,
    onVisitado: () -> Unit, onSubir: () -> Unit, onBajar: () -> Unit, onMatriz: () -> Unit
) {
    val visitado = item.estado.equals("Visitado", ignoreCase = true)
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (visitado) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surface)
    ) {
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
