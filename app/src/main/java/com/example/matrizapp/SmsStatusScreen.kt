package com.example.matrizapp

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun SmsStatusScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val container = (context.applicationContext as MainApplication).container
    val registros by container.database.matrizDao().getAllMatriz().collectAsState(initial = emptyList())

    val prefs = remember {
        context.getSharedPreferences(SmsStatusLocalConfig.PREFS, Context.MODE_PRIVATE)
    }
    var enabledStatuses by remember {
        mutableStateOf(SmsStatusLocalConfig.getEnabledStatuses(context))
    }
    var senderEnabled by remember {
        mutableStateOf(SmsStatusLocalConfig.isSenderEnabled(context))
    }
    var lineas by remember { mutableStateOf(SmsHelper.lineasActivas(context)) }
    var subId by remember {
        mutableStateOf(SmsStatusLocalConfig.getSubscriptionId(context))
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        lineas = SmsHelper.lineasActivas(context)
    }

    val statuses = remember(registros) {
        (registros.mapNotNull { it.estado.trim().takeIf { s -> s.isNotBlank() } } + "RETORNO")
            .map { it.uppercase() }
            .distinct()
            .sorted()
    }

    LaunchedEffect(Unit) {
        if (!SmsHelper.tienePermisos(context)) {
            permLauncher.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE))
        }
        SmsStatusWorker.programarPeriodicamente(context)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Regresar")
            }
            Icon(Icons.Default.Sms, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("SMS por Status APP", style = MaterialTheme.typography.titleLarge)
        }

        Text(
            "Esta función trabaja 100% dentro de este teléfono. Solo el dispositivo donde actives este interruptor enviará SMS por Status.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 48.dp, bottom = 12.dp)
        )

        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Este dispositivo envía SMS por Status", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (senderEnabled) "ACTIVO en este teléfono" else "Desactivado",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = senderEnabled,
                    onCheckedChange = { value ->
                        senderEnabled = value
                        SmsStatusLocalConfig.setSenderEnabled(context, value)
                        SmsStatusWorker.programarAhora(context)
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Status que disparan SMS", style = MaterialTheme.typography.titleMedium)
        Text(
            "Activa uno o varios. El SMS se envía al número del Titular (NumTT) usando la plantilla TT de su semana.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(statuses) { status ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(status, style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = status in enabledStatuses,
                            enabled = senderEnabled,
                            onCheckedChange = { value ->
                                val next = enabledStatuses.toMutableSet()
                                if (value) next.add(status) else next.remove(status)
                                enabledStatuses = next
                                SmsStatusLocalConfig.setStatus(context, status, value)
                                SmsStatusWorker.programarAhora(context)
                            }
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("Línea para SMS por Status", style = MaterialTheme.typography.titleMedium)
                Text(
                    "La línea se guarda solo en este teléfono.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (lineas.isEmpty()) {
                    OutlinedButton(
                        onClick = {
                            permLauncher.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE))
                        }
                    ) {
                        Text("Conceder permisos / actualizar SIM")
                    }
                } else {
                    lineas.forEach { linea ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = subId == linea.subscriptionId,
                                onClick = {
                                    subId = linea.subscriptionId
                                    prefs.edit().putInt("subscriptionId", linea.subscriptionId).apply()
                                }
                            )
                            Text(linea.etiqueta)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Importante: en esta versión no hay servidor que coordine los teléfonos. Mantén activado este interruptor solamente en el teléfono que quieras usar para SMS por Status.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
