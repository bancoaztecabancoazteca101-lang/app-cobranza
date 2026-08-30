package com.example.matrizapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val AZUL_ACENTO_PLANTILLAS = Color(0xFF1565C0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantillaSmsScreen(viewModel: PlantillaSmsViewModel) {
    val plantillas by viewModel.plantillas.collectAsState()
    var tipoSeleccionado by remember { mutableStateOf("TT") }
    var semanaSeleccionada by remember { mutableStateOf(1) }

    val filtradas = plantillas
        .filter { it.tipo == tipoSeleccionado && it.semana == semanaSeleccionada }
        .sortedBy { it.slot }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = tipoSeleccionado == "TT", onClick = { tipoSeleccionado = "TT" }, label = { Text("Titular (TT)") })
                FilterChip(selected = tipoSeleccionado == "REF", onClick = { tipoSeleccionado = "REF" }, label = { Text("Referencias") })
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (s in 1..5) {
                    FilterChip(selected = semanaSeleccionada == s, onClick = { semanaSeleccionada = s }, label = { Text("Sem $s") })
                }
            }
            Text(
                if (tipoSeleccionado == "TT") "Usa {nombre} y {monto} — se rellenan solos al enviar el SMS"
                else "Usa {nombre} — se rellena solo al enviar el SMS",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Text(
                "Se rota entre las 6 variantes en cada contacto sucesivo al mismo cliente. Deja una en blanco para que no se use.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtradas, key = { it.id }) { plantilla ->
                    PlantillaCard(
                        plantilla = plantilla,
                        onGuardar = { texto -> viewModel.guardarTexto(plantilla.id, texto) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlantillaCard(plantilla: PlantillaSmsEntity, onGuardar: (String) -> Unit) {
    var texto by remember(plantilla.id, plantilla.texto) { mutableStateOf(plantilla.texto) }
    val editado = texto != plantilla.texto

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (plantilla.texto.isBlank()) Color(0xFFF5F5F5) else Color(0xFFE3F2FD))
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (plantilla.texto.isBlank()) "Variante ${plantilla.slot} (desactivada, en blanco)" else "Variante ${plantilla.slot}",
                style = MaterialTheme.typography.labelLarge,
                color = if (plantilla.texto.isBlank()) Color.Gray else AZUL_ACENTO_PLANTILLAS
            )
            OutlinedTextField(
                value = texto,
                onValueChange = { texto = it },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                minLines = 2
            )
            if (editado) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { texto = plantilla.texto }) { Text("Cancelar") }
                    Button(onClick = { onGuardar(texto) }) { Text("Guardar") }
                }
            }
        }
    }
}
