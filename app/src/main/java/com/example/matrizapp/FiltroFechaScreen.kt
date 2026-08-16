package com.example.matrizapp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
fun FiltroFechaScreen(viewModel: FiltroFechaViewModel, searchQuery: String = "") {
    val allItems by viewModel.filteredList.collectAsState()
    val df = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    var itemToView by remember { mutableStateOf<FiltroFechaEntity?>(null) }

    val items = remember(allItems, searchQuery) {
        if (searchQuery.isBlank()) allItems else allItems.filter { item ->
            val q = searchQuery.trim()
            item.nombre.contains(q, ignoreCase = true) ||
                item.numTT.contains(q, ignoreCase = true) ||
                item.observaciones?.contains(q, ignoreCase = true) == true ||
                item.estado.contains(q, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (items.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Sin registros", color = Color.Gray) }
        else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item -> FiltroItemCard(item, df, onClick = { itemToView = item }) }
        }
    }

    itemToView?.let { item ->
        FiltroFechaDetailDialog(item = item, df = df, onDismiss = { itemToView = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltroItemCard(item: FiltroFechaEntity, df: SimpleDateFormat, onClick: () -> Unit) {
    val esRetorno = item.estado.contains("retorno", ignoreCase = true)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (esRetorno) Color(0xFFBDBDBD) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.nombre, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusBadge(item.estado)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("TT: ${item.numTT}", style = MaterialTheme.typography.bodySmall)
                Text(df.format(Date(item.fecha)), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(4.dp))
            ContactActionsRow(numTT = item.numTT)
        }
    }
}

@Composable
fun FiltroFechaDetailDialog(item: FiltroFechaEntity, df: SimpleDateFormat, onDismiss: () -> Unit) {
    // Filtro Fecha solo permite consultar el registro, no editarlo.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.nombre) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Num TT: ${item.numTT}")
                Text("Status: ${item.estado}")
                Text("Fecha: ${df.format(Date(item.fecha))}")
                item.hora?.let { Text("Hora: $it") }
                Text("Observaciones: ${item.observaciones ?: "Sin observaciones"}")
                Spacer(modifier = Modifier.height(8.dp))
                ContactActionsRow(numTT = item.numTT)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyDatePickerDialog(onDateSelected: (Long) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState()
    DatePickerDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onDateSelected(it) } }) { Text("OK") } }) {
        DatePicker(state = state)
    }
}
