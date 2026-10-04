package com.example.matrizapp

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigationDrawer(
    currentRoute: String,
    lastSyncTime: String,
    isSyncing: Boolean,
    onNavigate: (String) -> Unit,
    onSyncClick: () -> Unit,
    drawerState: DrawerState,
    backfillCuEnProgreso: Boolean = false,
    onBackfillCuClick: () -> Unit = {},
    ticketPagoEnProgreso: Boolean = false,
    onTicketFotoSeleccionada: (android.net.Uri) -> Unit = {},
    notificacionesAppCount: Int = 0,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var mostrarSelectorTicket by remember { mutableStateOf(false) }
    var ticketFotoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val ticketTakePictureLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { success -> if (success) ticketFotoUri?.let(onTicketFotoSeleccionada) }
    val ticketPickImageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(onTicketFotoSeleccionada) }

    val itemColors = NavigationDrawerItemDefaults.colors(
        unselectedContainerColor = Color.Transparent,
        selectedContainerColor = ClayPrimaryContainer,
        unselectedTextColor = ClayOnSurface,
        selectedTextColor = ClayOnSurface,
        unselectedIconColor = ClayPrimary,
        selectedIconColor = ClayPrimary
    )
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = ClayBackground,
                drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                modifier = Modifier.width(300.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ClayPrimaryContainer)
                        .padding(20.dp)
                ) {
                    Text(
                        text = "Matriz App",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = ClayPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = ClaySurface,
                        shadowElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(ClayGreenSuccess))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = "Datos: $lastSyncTime", style = MaterialTheme.typography.bodySmall, color = ClayOnSurface)
                            }
                            IconButton(onClick = onSyncClick, enabled = !isSyncing) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Sincronizar", tint = ClayPrimary)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(text = "FLUJO DE TRABAJO", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        NavigationDrawerItem(icon = { Icon(Icons.Default.TableChart, contentDescription = null) }, label = { Text("Matriz") }, selected = currentRoute == "matriz", onClick = { onNavigate("matriz"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)
                        if (backfillCuEnProgreso) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 28.dp).size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(
                                onClick = { onBackfillCuClick() },
                                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                            ) { Icon(Icons.Default.DocumentScanner, contentDescription = "Recuperar CU faltantes", tint = ClayPrimary) }
                        }
                    }
                    NavigationDrawerItem(icon = { Icon(Icons.Default.Assignment, contentDescription = null) }, label = { Text("Pase") }, selected = currentRoute == "pase", onClick = { onNavigate("pase"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)
                    NavigationDrawerItem(icon = { Icon(Icons.Default.Description, contentDescription = null) }, label = { Text("Solicitud") }, selected = currentRoute == "solicitud", onClick = { onNavigate("solicitud"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)
                    NavigationDrawerItem(icon = { Icon(Icons.Default.Route, contentDescription = null) }, label = { Text("Ruta IA") }, selected = currentRoute == "ruta_ia", onClick = { onNavigate("ruta_ia"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)
                    NavigationDrawerItem(icon = { Icon(Icons.Default.FileDownload, contentDescription = null) }, label = { Text("Exportar Matriz") }, selected = currentRoute == "exportar_matriz", onClick = { onNavigate("exportar_matriz"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)
                    NavigationDrawerItem(icon = { Icon(Icons.Default.Backup, contentDescription = null) }, label = { Text("Backup") }, selected = currentRoute == "backup", onClick = { onNavigate("backup"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)

                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(text = "HERRAMIENTAS", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        NavigationDrawerItem(icon = { Icon(Icons.Default.FilterList, contentDescription = null) }, label = { Text("Filtro Fecha") }, selected = currentRoute == "filtro", onClick = { onNavigate("filtro"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)
                        if (ticketPagoEnProgreso) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 28.dp).size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(
                                onClick = { mostrarSelectorTicket = true },
                                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                            ) { Icon(Icons.Default.DocumentScanner, contentDescription = "Escanear ticket de cobranza", tint = ClayPrimary) }
                        }
                    }
                    NavigationDrawerItem(icon = { Icon(Icons.Default.CalendarViewWeek, contentDescription = null) }, label = { Text("Filtro Semanal") }, selected = currentRoute == "filtro_semanal", onClick = { onNavigate("filtro_semanal"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)
                    NavigationDrawerItem(icon = { Icon(Icons.Default.Tune, contentDescription = null) }, label = { Text("Filtrar") }, selected = currentRoute == "filtrar", onClick = { onNavigate("filtrar"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)
                    NavigationDrawerItem(icon = { Icon(Icons.Default.BarChart, contentDescription = null) }, label = { Text("Control") }, selected = currentRoute == "control", onClick = { onNavigate("control"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)
                    NavigationDrawerItem(icon = { Icon(Icons.Default.Map, contentDescription = null) }, label = { Text("Ubi") }, selected = currentRoute == "ubi", onClick = { onNavigate("ubi"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)
                    NavigationDrawerItem(icon = { Icon(Icons.Default.Map, contentDescription = null) }, label = { Text("Mapa") }, selected = currentRoute == "mapa", onClick = { onNavigate("mapa"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Devices, contentDescription = null) },
                        label = { Text("Dispositivos") },
                        selected = false,
                        onClick = {
                            context.startActivity(Intent(context, NotificacionesDispositivosActivity::class.java))
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        colors = itemColors
                    )

                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(text = "CONSULTA", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
                    NavigationDrawerItem(icon = { Icon(Icons.Default.Visibility, contentDescription = null) }, label = { Text("Semana 6") }, selected = currentRoute == "sem6", onClick = { onNavigate("sem6"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)
                    NavigationDrawerItem(icon = { Icon(Icons.Default.Send, contentDescription = null) }, label = { Text("SMS") }, selected = currentRoute == "sms", onClick = { onNavigate("sms"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)
                    NavigationDrawerItem(icon = { Icon(Icons.Default.Call, contentDescription = null) }, label = { Text("Llamadas") }, selected = currentRoute == "llamadas", onClick = { onNavigate("llamadas"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)
                    NavigationDrawerItem(icon = { Icon(Icons.Default.Schedule, contentDescription = null) }, label = { Text("Bloques de horario") }, selected = currentRoute == "bloques_llamada", onClick = { onNavigate("bloques_llamada"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)
                    NavigationDrawerItem(icon = { Icon(Icons.Default.Message, contentDescription = null) }, label = { Text("Plantillas de SMS") }, selected = currentRoute == "plantillas_sms", onClick = { onNavigate("plantillas_sms"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)
                    NavigationDrawerItem(icon = { Icon(Icons.Default.BugReport, contentDescription = null) }, label = { Text("Diagnóstico") }, selected = currentRoute == "diagnostico", onClick = { onNavigate("diagnostico"); scope.launch { drawerState.close() } }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), colors = itemColors)
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                        label = { Text("Notificaciones") },
                        badge = { if (notificacionesAppCount > 0) Badge { Text("$notificacionesAppCount") } },
                        selected = currentRoute == "notificaciones_app",
                        onClick = { onNavigate("notificaciones_app"); scope.launch { drawerState.close() } },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        colors = itemColors
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        },
        content = content
    )
    if (mostrarSelectorTicket) {
        AlertDialog(
            onDismissRequest = { mostrarSelectorTicket = false },
            title = { Text("Escanear ticket de cobranza") },
            text = { Text("Toma una foto o elige una de la galería. Se leerá el nombre y el monto pagado para marcar el registro de hoy como Pagado.") },
            confirmButton = {
                TextButton(onClick = {
                    mostrarSelectorTicket = false
                    val photoFile = java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "ticket_${System.currentTimeMillis()}.jpg")
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.example.matrizapp.fileprovider", photoFile)
                    ticketFotoUri = uri
                    ticketTakePictureLauncher.launch(uri)
                }) { Text("Cámara") }
            },
            dismissButton = {
                TextButton(onClick = { mostrarSelectorTicket = false; ticketPickImageLauncher.launch("image/*") }) { Text("Galería") }
            }
        )
    }
}
