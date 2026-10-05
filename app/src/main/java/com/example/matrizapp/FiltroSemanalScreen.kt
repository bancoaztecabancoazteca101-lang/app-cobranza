package com.example.matrizapp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.*

private val DIAS_CORTOS = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")
private val DIAS_LARGOS = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")

@Composable
fun FiltroSemanalScreen(viewModel: FiltroSemanalViewModel, notificacionesHelper: NotificacionesHelper, searchQuery: String = "") {
    val semana by viewModel.semana.collectAsState()
    val dia by viewModel.dia.collectAsState()
    val context = LocalContext.current
    val df = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val fmt = remember { DateTimeFormatter.ofPattern("dd/MM") }
    var itemToView by remember { mutableStateOf<MatrizEntity?>(null) }

    val actual = semana[dia.coerceIn(0, semana.lastIndex)]
    val items = remember(actual, searchQuery) {
        if (searchQuery.isBlank()) actual.visitas else actual.visitas.filter {
            val q = searchQuery.trim()
            coincideBusqueda(it.nombre, q) || coincideBusqueda(it.numTT, q) || coincideBusqueda(it.observaciones, q) || coincideBusqueda(it.estado, q)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Semana del ${semana.first().fecha.format(fmt)} al ${semana.last().fecha.format(fmt)}",
            style = MaterialTheme.typography.labelMedium, color = Color.Gray,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)
        )
        // ScrollableTabRow: con 7 días (lunes a domingo) ya no caben todos en el ancho de la pantalla; las
        // pestañas se deslizan hacia los lados y la seleccionada se mantiene a la vista (el domingo queda al final).
        ScrollableTabRow(selectedTabIndex = dia.coerceIn(0, semana.lastIndex), edgePadding = 8.dp) {
            semana.forEachIndexed { i, d ->
                Tab(
                    selected = dia == i,
                    onClick = { viewModel.setDia(i) },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${DIAS_CORTOS[i]} ${d.fecha.dayOfMonth}", style = MaterialTheme.typography.labelLarge)
                            Text("${d.visitas.size}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Sin visitas el ${DIAS_LARGOS[dia.coerceIn(0, semana.lastIndex)]} ${actual.fecha.format(fmt)}", color = Color.Gray)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { item -> FiltroItemCard(item, df, viewModel.driveHelper) { itemToView = item } }
            }
        }
    }
    itemToView?.let { item ->
        FiltroFechaDetailDialog(item, df, viewModel.driveHelper, onDismiss = { itemToView = null },
            onGuardarEstadoYHora = { id, estado, hora ->
                viewModel.guardarEstadoYHora(id, estado, hora, notificacionesHelper) { mensaje ->
                    if (mensaje != null) android.widget.Toast.makeText(context, mensaje, android.widget.Toast.LENGTH_LONG).show()
                }
            })
    }
}
