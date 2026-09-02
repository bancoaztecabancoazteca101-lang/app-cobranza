package com.example.matrizapp
import android.Manifest
import android.net.Uri
import android.os.Build
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
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La barra de estado del sistema (arriba del todo) no la pinta Compose: hay que
        // fijarla a mano o si no queda con el morado por defecto del tema base Material3.
        window.statusBarColor = android.graphics.Color.parseColor("#1565C0")
        val container = (application as MainApplication).container
        val factory = ViewModelFactory(container)
        val crashFile = java.io.File(filesDir, "crash_log.txt")
        val previousCrash = if (crashFile.exists()) crashFile.readText().also { crashFile.delete() } else null
        setContent {
            val colorSchemeAzul = lightColorScheme(
                primary = Color(0xFF1565C0),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFD2E4FF),
                onPrimaryContainer = Color(0xFF001D36),
                inversePrimary = Color(0xFFA0CAFD),
                secondary = Color(0xFF3A608F),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFD3E4FF),
                onSecondaryContainer = Color(0xFF001D36),
                tertiary = Color(0xFF2E5F6B),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFD0E7F0),
                onTertiaryContainer = Color(0xFF001F26),
                background = Color(0xFFF3F7FD),
                onBackground = Color(0xFF1A1C1E),
                surface = Color(0xFFEAF1FB),
                onSurface = Color(0xFF1A1C1E),
                surfaceVariant = Color(0xFFDDE3EA),
                onSurfaceVariant = Color(0xFF41474D),
                outline = Color(0xFF71787E),
                outlineVariant = Color(0xFFC1C7CE),
                inverseSurface = Color(0xFF2E3133),
                inverseOnSurface = Color(0xFFF0F0F3),
                scrim = Color.Black
            )
            MaterialTheme(colorScheme = colorSchemeAzul) {
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
                val sem6Vm: Sem6ViewModel = viewModel(factory = factory)
                val smsVm: SmsViewModel = viewModel(factory = factory)
                val callVm: CallViewModel = viewModel(factory = factory)
                val bloqueVm: BloqueHorarioViewModel = viewModel(factory = factory)
                val plantillaVm: PlantillaSmsViewModel = viewModel(factory = factory)
                // searchInput es lo que el usuario teclea (se actualiza al instante, sin costo,
                // porque no dispara el filtrado). searchQuery es la versión "debounced" que se
                // pasa a las pantallas y sí dispara el filtrado de las listas; se actualiza ~180ms
                // después de que el usuario deja de teclear, para que escribir se sienta fluido
                // aunque la lista o la búsqueda por foto (OCR) hayan crecido.
                var searchInput by remember { mutableStateOf("") }
                var searchQuery by remember { mutableStateOf("") }
                LaunchedEffect(searchInput) {
                    delay(180)
                    searchQuery = searchInput
                }
                var searchActive by remember { mutableStateOf(false) }
                var buscandoPorFoto by remember { mutableStateOf(false) }
                var mostrarSelectorFotoBusqueda by remember { mutableStateOf(false) }
                var fotoBusquedaUri by remember { mutableStateOf<Uri?>(null) }
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
                // Antes solo sincronizaba una vez al iniciar sesión -- si el equipo dejaba la
                // app abierta en segundo plano y volvía más tarde, o esperaba a ver datos
                // nuevos sin tocar el botón manual, no se refrescaba solo. Ahora también
                // sincroniza cada vez que la app vuelve a primer plano, y cada 3 minutos
                // mientras sigue abierta y con sesión iniciada.
                val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, signedIn) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && signedIn) refreshData()
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                LaunchedEffect(signedIn) {
                    if (!signedIn) return@LaunchedEffect
                    while (true) {
                        delay(3 * 60 * 1000L)
                        refreshData()
                    }
                }
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
                // Buscar con foto: OCR local (ML Kit) sobre una foto tomada o elegida de galería,
                // intenta detectar el nombre del cliente en la imagen y lo usa como búsqueda.
                fun procesarFotoBusqueda(uri: Uri?) {
                    if (uri == null) return
                    buscandoPorFoto = true
                    coroutineScope.launch {
                        val nombre = extraerNombreDeImagen(this@MainActivity, uri)
                        buscandoPorFoto = false
                        if (nombre.isNullOrBlank()) {
                            Toast.makeText(this@MainActivity, "No se detectó un nombre en la foto, intenta con otra más clara", Toast.LENGTH_LONG).show()
                        } else {
                            searchActive = true
                            searchInput = nombre
                            searchQuery = nombre
                        }
                    }
                }
                val ocrTakePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                    if (success) procesarFotoBusqueda(fotoBusquedaUri)
                }
                val ocrPickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    procesarFotoBusqueda(uri)
                }
                if (mostrarSelectorFotoBusqueda) {
                    AlertDialog(
                        onDismissRequest = { mostrarSelectorFotoBusqueda = false },
                        title = { Text("Buscar con foto") },
                        text = { Text("Toma una foto o elige una de la galería. Se leerá el texto para buscar por nombre.") },
                        confirmButton = {
                            TextButton(onClick = {
                                mostrarSelectorFotoBusqueda = false
                                val photoFile = File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "busqueda_${System.currentTimeMillis()}.jpg")
                                val uri = FileProvider.getUriForFile(this@MainActivity, "com.example.matrizapp.fileprovider", photoFile)
                                fotoBusquedaUri = uri
                                ocrTakePictureLauncher.launch(uri)
                            }) { Text("Cámara") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                mostrarSelectorFotoBusqueda = false
                                ocrPickImageLauncher.launch("image/*")
                            }) { Text("Galería") }
                        }
                    )
                }
                LaunchedEffect(Unit) {
                    val permisos = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permisos.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permLauncher.launch(permisos.toTypedArray())
                }
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val lastSyncLabel = if (isRefreshing) "Sincronizando…" else "Lista"
                val navBackStackEntryForDrawer by navController.currentBackStackEntryAsState()
                val currentRouteForDrawer = navBackStackEntryForDrawer?.destination?.route ?: Screen.Matriz.route
                LaunchedEffect(currentRouteForDrawer) { searchInput = ""; searchQuery = ""; searchActive = false }
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
                                        value = searchInput,
                                        onValueChange = { searchInput = it },
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
                                    if (searchActive) { searchActive = false; searchInput = ""; searchQuery = "" }
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
                                    if (buscandoPorFoto) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                                    } else {
                                        IconButton(onClick = { mostrarSelectorFotoBusqueda = true }) {
                                            Icon(Icons.Default.CameraAlt, contentDescription = "Buscar con foto")
                                        }
                                    }
                                    if (searchInput.isNotEmpty()) {
                                        IconButton(onClick = { searchInput = ""; searchQuery = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = "Limpiar búsqueda")
                                        }
                                    }
                                } else {
                                    IconButton(onClick = { searchActive = true }) {
                                        Icon(Icons.Default.Search, contentDescription = "Buscar")
                                    }
                                    // En las pantallas con orden (Filtro Fecha, Sem6, Solicitud) el botón de
                                    // ordenar ocupa el lugar del ícono de sincronizar, para no empujar la
                                    // lista hacia abajo con una fila extra. La sincronización en background
                                    // sigue funcionando igual; en el resto de pantallas el ícono de sync
                                    // sigue disponible como antes.
                                    when (currentRouteForDrawer) {
                                        Screen.Matriz.route -> {
                                            val orden by matrizVm.orden.collectAsState()
                                            OrdenSelectorButton(orden = orden, onOrdenChange = { o, loc -> matrizVm.setOrden(o, loc) })
                                        }
                                        Screen.FiltroFecha.route -> {
                                            val orden by filtroVm.orden.collectAsState()
                                            OrdenSelectorButton(orden = orden, onOrdenChange = { o, loc -> filtroVm.setOrden(o, loc) })
                                        }
                                        Screen.Sem6.route -> {
                                            val orden by sem6Vm.orden.collectAsState()
                                            OrdenSelectorButton(orden = orden, onOrdenChange = { o, loc -> sem6Vm.setOrden(o, loc) })
                                        }
                                        Screen.Solicitud.route -> {
                                            val orden by solicitudVm.orden.collectAsState()
                                            OrdenSelectorButton(orden = orden, onOrdenChange = { o, loc -> solicitudVm.setOrden(o, loc) })
                                        }
                                        else -> {
                                            IconButton(onClick = { refreshData() }) {
                                                if (isRefreshing) {
                                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                                } else {
                                                    Icon(Icons.Default.Sync, contentDescription = "Sincronizar")
                                                }
                                            }
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
                        composable(Screen.FiltroFecha.route) { FiltroFechaScreen(filtroVm, container.notificacionesHelper, searchQuery) }
                        composable(Screen.Filtrar.route) { FiltrarScreen(filtrarVm, searchQuery) }
                        composable(Screen.Control.route) { ControlScreen(controlVm) }
                        composable(Screen.Ubi.route) { UbiScreen(matrizVm) }
                        composable(Screen.Sem6.route) { Sem6Screen(sem6Vm, searchQuery) }
                        composable(Screen.Sms.route) { SmsScreen(smsVm) }
                        composable(Screen.Llamadas.route) { CallScreen(callVm) }
                        composable(Screen.BloquesLlamada.route) { BloqueHorarioScreen(bloqueVm) }
                        composable(Screen.PlantillasSms.route) { PlantillaSmsScreen(plantillaVm) }
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
    object Sem6 : Screen("sem6", "Semana 6", Icons.Default.Visibility)
    object Sms : Screen("sms", "SMS", Icons.Default.Send)
    object Llamadas : Screen("llamadas", "Llamadas", Icons.Default.Call)
    object BloquesLlamada : Screen("bloques_llamada", "Bloques de horario", Icons.Default.Schedule)
    object PlantillasSms : Screen("plantillas_sms", "Plantillas de SMS", Icons.Default.Message)
}