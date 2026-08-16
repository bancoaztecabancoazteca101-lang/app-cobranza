package com.example.matrizapp
import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MainApplication).container
        val factory = ViewModelFactory(container)
        val crashFile = java.io.File(filesDir, "crash_log.txt")
        val previousCrash = if (crashFile.exists()) crashFile.readText().also { crashFile.delete() } else null
        setContent {
            MaterialTheme {
                var crashLog by remember { mutableStateOf(previousCrash) }
                crashLog?.let { text ->
                    AlertDialog(
                        onDismissRequest = { crashLog = null },
                        title = { Text("La app se cerró inesperadamente") },
                        text = {
                            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                                SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
                            }
                        },
                        confirmButton = { TextButton(onClick = { crashLog = null }) { Text("Cerrar") } }
                    )
                }
                var signedIn by remember { mutableStateOf(hasSignedInAccount(this)) }
                if (!signedIn) {
                    LoginScreen(onSignedIn = { signedIn = true })
                    return@MaterialTheme
                }
                val navController = rememberNavController()
                val coroutineScope = rememberCoroutineScope()
                val matrizVm: MatrizViewModel = viewModel(factory = factory)
                val paseVm: PaseCarteraViewModel = viewModel(factory = factory)
                val solicitudVm: SolicitudViewModel = viewModel(factory = factory)
                val filtroVm: FiltroFechaViewModel = viewModel(factory = factory)
                val filtrarVm: FiltrarViewModel = viewModel(factory = factory)
                val controlVm: ControlViewModel = viewModel(factory = factory)
                var searchQuery by remember { mutableStateOf("") }
                var searchActive by remember { mutableStateOf(false) }
                var isRefreshing by remember { mutableStateOf(false) }
                var syncError by remember { mutableStateOf<String?>(null) }
                fun refreshData() {
                    if (isRefreshing) return
                    isRefreshing = true
                    coroutineScope.launch {
                        try {
                            container.repository.refreshAll()
                        } catch (e: Exception) {
                            syncError = e.stackTraceToString()
                        }
                        isRefreshing = false
                    }
                }
                LaunchedEffect(signedIn) { refreshData() }
                syncError?.let { errorText ->
                    AlertDialog(
                        onDismissRequest = { syncError = null },
                        title = { Text("Error al sincronizar") },
                        text = {
                            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                                SelectionContainer { Text(errorText, style = MaterialTheme.typography.bodySmall) }
                            }
                        },
                        confirmButton = { TextButton(onClick = { syncError = null }) { Text("Cerrar") } }
                    )
                }
                val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    if (!it.values.all { p -> p }) Toast.makeText(this, "Permisos necesarios", Toast.LENGTH_SHORT).show()
                }
                LaunchedEffect(Unit) {
                    permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION))
                }
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val lastSyncLabel = if (isRefreshing) "Sincronizando…" else "Lista"
                val navBackStackEntryForDrawer by navController.currentBackStackEntryAsState()
                val currentRouteForDrawer = navBackStackEntryForDrawer?.destination?.route ?: Screen.Matriz.route
                LaunchedEffect(currentRouteForDrawer) { searchQuery = ""; searchActive = false }
                AppNavigationDrawer(
                    currentRoute = currentRouteForDrawer,
                    lastSyncTime = lastSyncLabel,
                    isSyncing = isRefreshing,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true; restoreState = true
                        }
                    },
                    onSyncClick = { refreshData() },
                    drawerState = drawerState
                ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                if (searchActive) {
                                    val focusRequester = remember { FocusRequester() }
                                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                                    TextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        placeholder = { Text("Buscar en esta pantalla...") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent,
                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent
                                        )
                                    )
                                } else {
                                    Text("")
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = {
                                    if (searchActive) { searchActive = false; searchQuery = "" }
                                    else coroutineScope.launch { drawerState.open() }
                                }) {
                                    Icon(
                                        if (searchActive) Icons.Default.ArrowBack else Icons.Default.Menu,
                                        contentDescription = if (searchActive) "Cerrar búsqueda" else "Menú"
                                    )
                                }
                            },
                            actions = {
                                if (searchActive) {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = "Limpiar búsqueda")
                                        }
                                    }
                                } else {
                                    IconButton(onClick = { searchActive = true }) {
                                        Icon(Icons.Default.Search, contentDescription = "Buscar")
                                    }
                                    IconButton(onClick = { refreshData() }) {
                                        if (isRefreshing) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Default.Sync, contentDescription = "Sincronizar")
                                        }
                                    }
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    NavHost(navController, Screen.Matriz.route, Modifier.padding(innerPadding)) {
                        composable(Screen.Matriz.route) { MatrizScreen(matrizVm, searchQuery) }
                        composable(Screen.PaseCartera.route) { PaseCarteraScreen(paseVm, searchQuery) }
                        composable(Screen.Solicitud.route) { SolicitudScreen(solicitudVm, searchQuery) }
                        composable(Screen.FiltroFecha.route) { FiltroFechaScreen(filtroVm, searchQuery) }
                        composable(Screen.Filtrar.route) { FiltrarScreen(filtrarVm, searchQuery) }
                        composable(Screen.Control.route) { ControlScreen(controlVm) }
                        composable(Screen.Ubi.route) { UbiScreen(matrizVm) }
                    }
                }
                }
            }
        }
    }
}
sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Matriz : Screen("matriz", "Matriz", Icons.Default.TableChart)
    object PaseCartera : Screen("pase", "Pase", Icons.Default.AccountBalanceWallet)
    object Solicitud : Screen("solicitud", "Solicitud", Icons.Default.Assignment)
    object FiltroFecha : Screen("filtro", "Filtro Fecha", Icons.Default.DateRange)
    object Filtrar : Screen("filtrar", "Filtrar", Icons.Default.FilterAlt)
    object Control : Screen("control", "Control", Icons.Default.BarChart)
    object Ubi : Screen("ubi", "Ubi", Icons.Default.Map)
}