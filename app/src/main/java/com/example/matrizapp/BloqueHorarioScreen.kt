@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.matrizapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val AZUL_ACENTO = Color(0xFF1565C0)
private val FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun BloqueHorarioScreen(viewModel: BloqueHorarioViewModel) {
    val bloques by viewModel.bloques.collectAsState()

    var mostrarDialogoNuevo by remember { mutableStateOf(false) }
    var bloqueEnEdicion by remember { mutableStateOf<BloqueHorarioEntity?>(null) }
    var bloqueAEliminar by remember { mutableStateOf<BloqueHorarioEntity?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { mostrarDialogoNuevo = true },
                containerColor = AZUL_ACENTO
            ) {
                Icon(Icons.Default.Add, contentDescription = "Agregar bloque", tint = Color.White)
            }
        }
    ) { padding ->
        if (bloques.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Sin bloques configurados. Agrega el primero con el botón +.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(bloques, key = { it.id }) { bloque ->
                    BloqueCard(
                        bloque = bloque,
                        onToggleActivo = { viewModel.toggleActivo(bloque) },
                        onEditar = { bloqueEnEdicion = bloque },
                        onEliminar = { bloqueAEliminar = bloque }
                    )
                }
            }
        }
    }

    if (mostrarDialogoNuevo) {
        SelectorHoraDialog(
            horaInicial = LocalTime.of(10, 30),
            titulo = "Nuevo bloque",
            onConfirmar = { hora ->
                viewModel.agregarBloque(hora)
                mostrarDialogoNuevo = false
            },
            onCancelar = { mostrarDialogoNuevo = false }
        )
    }

    bloqueEnEdicion?.let { bloque ->
        SelectorHoraDialog(
            horaInicial = bloque.toLocalTime(),
            titulo = "Editar hora del bloque",
            onConfirmar = { nuevaHora ->
                viewModel.editarHora(bloque, nuevaHora)
                bloqueEnEdicion = null
            },
            onCancelar = { bloqueEnEdicion = null }
        )
    }

    bloqueAEliminar?.let { bloque ->
        AlertDialog(
            onDismissRequest = { bloqueAEliminar = null },
            title = { Text("Eliminar bloque") },
            text = { Text("Se eliminará el bloque de las ${bloque.toLocalTime().format(FORMATO_HORA)} y su alarma programada. ¿Continuar?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.eliminarBloque(bloque)
                    bloqueAEliminar = null
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { bloqueAEliminar = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun BloqueCard(
    bloque: BloqueHorarioEntity,
    onToggleActivo: () -> Unit,
    onEditar: () -> Unit,
    onEliminar: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (bloque.activo) Color(0xFFE3F2FD) else Color(0xFFF5F5F5)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = bloque.toLocalTime().format(FORMATO_HORA),
                style = MaterialTheme.typography.headlineSmall,
                color = if (bloque.activo) AZUL_ACENTO else Color.Gray
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = bloque.activo, onCheckedChange = { onToggleActivo() })
                IconButton(onClick = onEditar) {
                    Icon(Icons.Default.Edit, contentDescription = "Editar hora")
                }
                IconButton(onClick = onEliminar) {
                    Icon(Icons.Default.Delete, contentDescription = "Eliminar bloque")
                }
            }
        }
    }
}

@Composable
private fun SelectorHoraDialog(
    horaInicial: LocalTime,
    titulo: String,
    onConfirmar: (LocalTime) -> Unit,
    onCancelar: () -> Unit
) {
    val estado = rememberTimePickerState(
        initialHour = horaInicial.hour,
        initialMinute = horaInicial.minute,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text(titulo) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = estado)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirmar(LocalTime.of(estado.hour, estado.minute))
            }) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onCancelar) { Text("Cancelar") }
        }
    )
}
