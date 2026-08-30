package com.example.matrizapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()

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
                        color = Color.White,
                        shadowElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(ClayGreenSuccess)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Datos: $lastSyncTime",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ClayOnSurface
                                )
                            }
                            IconButton(onClick = onSyncClick, enabled = !isSyncing) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Sincronizar",
                                    tint = ClayPrimary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {

                Text(
                    text = "FLUJO DE TRABAJO",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.TableChart, contentDescription = null) },
                    label = { Text("Matriz") },
                    selected = currentRoute == "matriz",
                    onClick = {
                        onNavigate("matriz")
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Assignment, contentDescription = null) },
                    label = { Text("Pase") },
                    selected = currentRoute == "pase",
                    onClick = {
                        onNavigate("pase")
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Description, contentDescription = null) },
                    label = { Text("Solicitud") },
                    selected = currentRoute == "solicitud",
                    onClick = {
                        onNavigate("solicitud")
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "HERRAMIENTAS",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.FilterList, contentDescription = null) },
                    label = { Text("Filtro Fecha") },
                    selected = currentRoute == "filtro",
                    onClick = {
                        onNavigate("filtro")
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("Filtrar") },
                    selected = currentRoute == "filtrar",
                    onClick = {
                        onNavigate("filtrar")
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                    label = { Text("Control") },
                    selected = currentRoute == "control",
                    onClick = {
                        onNavigate("control")
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Map, contentDescription = null) },
                    label = { Text("Ubi") },
                    selected = currentRoute == "ubi",
                    onClick = {
                        onNavigate("ubi")
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "CONSULTA",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Visibility, contentDescription = null) },
                    label = { Text("Semana 6") },
                    selected = currentRoute == "sem6",
                    onClick = {
                        onNavigate("sem6")
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Send, contentDescription = null) },
                    label = { Text("SMS") },
                    selected = currentRoute == "sms",
                    onClick = {
                        onNavigate("sms")
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Call, contentDescription = null) },
                    label = { Text("Llamadas") },
                    selected = currentRoute == "llamadas",
                    onClick = {
                        onNavigate("llamadas")
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Schedule, contentDescription = null) },
                    label = { Text("Bloques de horario") },
                    selected = currentRoute == "bloques_llamada",
                    onClick = {
                        onNavigate("bloques_llamada")
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Message, contentDescription = null) },
                    label = { Text("Plantillas de SMS") },
                    selected = currentRoute == "plantillas_sms",
                    onClick = {
                        onNavigate("plantillas_sms")
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                Spacer(modifier = Modifier.height(8.dp))
                }
            }
        },
        content = content
    )
}
