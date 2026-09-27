package com.example.matrizapp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Una alerta de la app (crash, error de sincronización, etc.), con la hora en que ocurrió. */
data class NotificacionApp(val mensaje: String, val hora: String)

/** Historial de alertas de la app. Antes se mostraban como AlertDialog intrusivos de pantalla
 * completa; ahora aparecen como un banner descartable en la parte superior (ver MainActivity)
 * y además quedan aquí para poder revisarlas después de cerrar el banner. El historial vive
 * en memoria mientras la app está abierta (lista de MainActivity) -- no persiste en Room, así
 * que se pierde al cerrar la app por completo. Si Diego quiere que sobreviva reinicios, hace
 * falta una tabla nueva (migración de Room). */
@Composable
fun NotificacionesAppScreen(notificaciones: List<NotificacionApp>, onLimpiar: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Notificaciones", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (notificaciones.isNotEmpty()) TextButton(onClick = onLimpiar) { Text("Limpiar") }
        }
        Spacer(Modifier.height(8.dp))
        if (notificaciones.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.NotificationsOff, contentDescription = null, tint = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    Text("Sin notificaciones", color = Color.Gray)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(notificaciones) { n ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(n.hora, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Spacer(Modifier.height(4.dp))
                            Text(n.mensaje, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
