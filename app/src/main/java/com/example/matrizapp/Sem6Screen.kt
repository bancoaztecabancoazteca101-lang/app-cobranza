package com.example.matrizapp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sem6Screen(viewModel: Sem6ViewModel, searchQuery: String = "") {
    val allItems by viewModel.items.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val lastUpdated by viewModel.lastUpdated.collectAsState()
    val isFromCache by viewModel.isFromCache.collectAsState()

    val items = remember(allItems, searchQuery) {
        if (searchQuery.isBlank()) allItems else allItems.filter { item ->
            val q = searchQuery.trim()
            item.nombre.contains(q, ignoreCase = true) ||
                item.cu.contains(q, ignoreCase = true) ||
                item.id.contains(q, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(ClayBackground)) {
        // Barra de estado: última actualización + botón de refrescar
        Surface(color = ClayPrimaryContainer, tonalElevation = 0.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentSem6SheetName().replace("Cont-Sem-", "Semana "),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = ClayPrimary
                    )
                    Text(
                        text = when {
                            isLoading -> "Actualizando…"
                            lastUpdated != null -> {
                                val df = SimpleDateFormat("HH:mm", Locale.getDefault())
                                val prefijo = if (isFromCache) "Último dato guardado: " else "Actualizado: "
                                prefijo + df.format(Date(lastUpdated!!))
                            }
                            else -> "Sin datos aún"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ClayOnSurface
                    )
                }
                IconButton(onClick = { viewModel.cargar() }, enabled = !isLoading) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar", tint = ClayPrimary)
                    }
                }
            }
        }

        error?.let { msg ->
            Surface(color = Color(0xFFFFEBEE)) {
                Text(
                    text = "No se pudo actualizar: $msg. Mostrando el último dato disponible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC62828),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        if (items.isEmpty() && !isLoading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Sin cuentas registradas esta semana", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(items, key = { it.id }) { item -> Sem6ItemCard(item) }
            }
        }
    }
}

@Composable
fun Sem6ItemCard(item: Sem6Item) {
    ClayCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.nombre, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Sem: ${item.sem}  ·  Req: ${item.req}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Text("CU: ${item.cu}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                if (item.ultimaFechaVisita.isNotBlank()) {
                    Text("Última vez: ${item.ultimaFechaVisita}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            Surface(
                color = ClayPrimaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, tint = ClayPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${item.visitas}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ClayPrimary)
                }
            }
        }
    }
}
