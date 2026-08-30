package com.example.matrizapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Banner compacto de una sola línea para "hay/no hay algo programado" — reemplaza la Card
 * grande que usaban CallScreen y SmsScreen (Column de texto envolviendo en varias líneas +
 * botón ancho con ícono y texto largo empujando la tarjeta a ocupar media pantalla). Altura
 * fija por el maxLines=1 en texto y botón, sin importar el tamaño de letra del sistema.
 * El botón de detener queda siempre visible (no solo cuando `activo`), igual que antes, para
 * poder frenar el flujo automático desde cualquier pestaña aunque la UI no lo haya detectado.
 */
@Composable
fun EstadoAutomatizacionBanner(
    activo: Boolean,
    textoActivo: String,
    textoInactivo: String,
    colorActivo: Color,
    etiquetaDetener: String,
    onDetener: () -> Unit
) {
    Surface(
        color = if (activo) colorActivo.copy(alpha = 0.12f) else Color.LightGray.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                if (activo) textoActivo else textoInactivo,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 6.dp)
            )
            TextButton(
                onClick = onDetener,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = if (activo) colorActivo else Color.Gray)
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text(etiquetaDetener, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}
