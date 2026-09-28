package com.example.matrizapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Paleta que sigue el modo del dispositivo. Oscuro = igual a la hoja Matriz: fondo negro,
// texto blanco, iconos de color. Son getters @Composable: solo se pueden leer dentro de composables.
@Composable @ReadOnlyComposable private fun dark(): Boolean = isSystemInDarkTheme()

val ClayBackground: Color @Composable @ReadOnlyComposable get() = if (dark()) Color(0xFF101417) else Color(0xFFF3F7FD)
val ClaySurface: Color @Composable @ReadOnlyComposable get() = if (dark()) Color(0xFF1B2126) else Color(0xFFEAF1FB)
val ClayPrimary: Color @Composable @ReadOnlyComposable get() = if (dark()) Color(0xFF64B5F6) else Color(0xFF1565C0)
val ClayPrimaryContainer: Color @Composable @ReadOnlyComposable get() = if (dark()) Color(0xFF0F2B45) else Color(0xFFD2E4FF)
val ClayOnSurface: Color @Composable @ReadOnlyComposable get() = if (dark()) Color(0xFFFFFFFF) else Color(0xFF1D1B20)
val ClayGreenSuccess: Color @Composable @ReadOnlyComposable get() = if (dark()) Color(0xFF66BB6A) else Color(0xFF2E7D32)
val ClayRedText: Color @Composable @ReadOnlyComposable get() = if (dark()) Color(0xFFEF5350) else Color(0xFFC62828)
val ClayWhatsAppGreen = Color(0xFF25D366)
val ClayMapsRed = Color(0xFFE53935)
val ClayCallBlue = Color(0xFF1976D2)
val ClaySmsTeal = Color(0xFF00897B)

// Fondos de tarjetas de color (antes pasteles fijos que en oscuro dejaban texto blanco sobre blanco)
val ClayNeutralContainer: Color @Composable @ReadOnlyComposable get() = if (dark()) Color(0xFF1B2126) else Color(0xFFF5F5F5)
val ClayBlueContainer: Color @Composable @ReadOnlyComposable get() = if (dark()) Color(0xFF12304D) else Color(0xFFE3F2FD)
val ClayGreenContainer: Color @Composable @ReadOnlyComposable get() = if (dark()) Color(0xFF14301C) else Color(0xFFE8F5E9)
val ClayYellowContainer: Color @Composable @ReadOnlyComposable get() = if (dark()) Color(0xFF3A3210) else Color(0xFFFFF3C4)
val ClayOrangeContainer: Color @Composable @ReadOnlyComposable get() = if (dark()) Color(0xFF3D2A12) else Color(0xFFFFE0B2)
val ClayRedContainer: Color @Composable @ReadOnlyComposable get() = if (dark()) Color(0xFF3A1A1C) else Color(0xFFFFEBEE)

@Composable
fun ClayCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = ClaySurface,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 12.dp),
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
        shadowElevation = 6.dp,
        tonalElevation = 4.dp,
        onClick = onClick ?: {}
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            content = content
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onMenuClick: () -> Unit,
    onOpenFilterSheet: () -> Unit,
    currentScreenName: String,
    showFilterAndSearch: Boolean = true
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        color = ClaySurface,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Abrir Menú",
                    tint = ClayPrimary
                )
            }

            if (showFilterAndSearch) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Buscar...", fontSize = 14.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent
                    ),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Limpiar", tint = Color.Gray)
                            }
                        }
                    }
                )

                IconButton(onClick = onOpenFilterSheet) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = "Filtros Avanzados",
                        tint = ClayPrimary
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentScreenName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ClayOnSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(ClayGreenSuccess)
                    )
                }
            }
        }
    }
}

@Composable
fun CustomerClayCard(
    nombre: String,
    tt: String?,
    ref1: String?,
    ref2: String?,
    observaciones: String?,
    status: String?,
    fecha: String?,
    ubicacion: String?,
    audioPath: String?,
    onPlayAudio: (() -> Unit)?,
    onEditClick: () -> Unit,
    context: Context
) {
    ClayCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = nombre,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ClayOnSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onEditClick) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Editar Registro",
                    tint = ClayPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (!tt.isNullOrBlank()) {
            Text(text = "TT: $tt", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        if (!ref1.isNullOrBlank()) {
            Text(text = "Ref 1: $ref1", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        if (!ref2.isNullOrBlank()) {
            Text(text = "Ref 2: $ref2", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        if (!observaciones.isNullOrBlank()) {
            Text(text = "Obs: $observaciones", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        if (!status.isNullOrBlank()) {
            Text(text = "Estatus: $status", style = MaterialTheme.typography.labelMedium, color = ClayPrimary)
        }
        if (!fecha.isNullOrBlank()) {
            Text(text = "Fecha: $fecha", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Smart CTAs Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Audio Playback CTA (Only if audio recorded)
            if (!audioPath.isNullOrBlank() && onPlayAudio != null) {
                IconButton(onClick = onPlayAudio) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir Audio", tint = ClayPrimary)
                }
            }

            // Telephone CTAs (Only rendered if TT exists)
            if (!tt.isNullOrBlank()) {
                IconButton(onClick = {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$tt"))
                    context.startActivity(intent)
                }) {
                    Icon(Icons.Default.Phone, contentDescription = "Llamar", tint = ClayCallBlue)
                }

                IconButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("sms:$tt"))
                    context.startActivity(intent)
                }) {
                    Icon(Icons.Default.Message, contentDescription = "Enviar SMS", tint = ClaySmsTeal)
                }

                IconButton(onClick = {
                    val url = "https://api.whatsapp.com/send?phone=$tt"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                }) {
                    Icon(Icons.Default.Chat, contentDescription = "Enviar WhatsApp", tint = ClayWhatsAppGreen)
                }
            }

            // Maps CTA (Only rendered if location data exists)
            if (!ubicacion.isNullOrBlank() && ubicacion != "N/A") {
                IconButton(onClick = {
                    val gmmIntentUri = Uri.parse("geo:0,0?q=${Uri.encode(ubicacion)}")
                    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                    mapIntent.setPackage("com.google.android.apps.maps")
                    context.startActivity(mapIntent)
                }) {
                    Icon(Icons.Default.Map, contentDescription = "Abrir Google Maps", tint = ClayMapsRed)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSideSheet(
    isOpen: Boolean,
    onClose: () -> Unit,
    filterValues: Map<String, String>,
    onFilterValueChange: (String, String) -> Unit,
    onClearFilters: () -> Unit,
    onApplyFilters: () -> Unit
) {
    if (isOpen) {
        ModalBottomSheet(
            onDismissRequest = onClose,
            containerColor = ClayBackground,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Filtros Avanzados",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = ClayOnSurface
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp))

                val filterFields = listOf(
                    "Nombre", "Num TT", "Ref.(1)", "Ref(2)",
                    "Observaciones", "Status", "Fecha", "Id"
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filterFields) { field ->
                        OutlinedTextField(
                            value = filterValues[field] ?: "",
                            onValueChange = { newValue -> onFilterValueChange(field, newValue) },
                            label = { Text(field) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = {
                        onClearFilters()
                    }) {
                        Text("CLEAR", color = Color.Red, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            onApplyFilters()
                            onClose()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ClayPrimary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("DONE", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
