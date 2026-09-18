package com.example.matrizapp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Submenú de Control: 3 opciones (Penalización, Comisión, Avance), cada una en su propia
 * pantalla. Antes "Avance" (Requerido por día/semana) vivía mezclado directo aquí; se movió a
 * AvanceScreen.kt para que las 3 opciones sean consistentes entre sí (ver ControlScreen.kt
 * previo a 18/09/2026 en el historial si se necesita comparar). */
@Composable
fun ControlScreen(
    onNavigateToPenalizacion: () -> Unit = {},
    onNavigateToComision: () -> Unit = {},
    onNavigateToAvance: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ControlMenuCard("Penalización", onNavigateToPenalizacion)
        ControlMenuCard("Comisión", onNavigateToComision)
        ControlMenuCard("Avance", onNavigateToAvance)
    }
}

@Composable
private fun ControlMenuCard(titulo: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(titulo, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}
