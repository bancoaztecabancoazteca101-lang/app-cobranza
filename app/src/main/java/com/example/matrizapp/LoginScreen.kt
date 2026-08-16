package com.example.matrizapp

import android.app.Activity.RESULT_OK
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.android.gms.common.Scopes

/**
 * Pantalla de inicio de sesión con Google.
 * Se muestra solo si no hay una cuenta de Google ya conectada con los permisos
 * necesarios para leer/escribir en Sheets y Drive.
 */
@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val context = LocalContext.current

    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                com.google.android.gms.common.api.Scope(SheetsScopes.SPREADSHEETS),
                com.google.android.gms.common.api.Scope(DriveScopes.DRIVE)
            )
            .build()
    }
    val googleSignInClient: GoogleSignInClient = remember { GoogleSignIn.getClient(context, gso) }

    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        loading = false
        if (result.resultCode == RESULT_OK) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                task.getResult(ApiException::class.java)
                error = null
                onSignedIn()
            } catch (e: ApiException) {
                error = "Error al iniciar sesión (código ${e.statusCode}). Verifica tu conexión e inténtalo de nuevo."
            }
        } else {
            error = "Inicio de sesión cancelado."
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.CloudDone, contentDescription = null, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(24.dp))
        Text("Matriz App", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Inicia sesión con tu cuenta de Google para sincronizar con la hoja de cálculo.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                loading = true
                error = null
                launcher.launch(googleSignInClient.signInIntent)
            },
            enabled = !loading
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Iniciar sesión con Google")
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

/** Devuelve true si ya hay una cuenta de Google con los scopes necesarios. */
fun hasSignedInAccount(context: android.content.Context): Boolean {
    val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
    val requiredScopes = setOf(
        com.google.android.gms.common.api.Scope(SheetsScopes.SPREADSHEETS),
        com.google.android.gms.common.api.Scope(DriveScopes.DRIVE)
    )
    return GoogleSignIn.hasPermissions(account, *requiredScopes.toTypedArray())
}
