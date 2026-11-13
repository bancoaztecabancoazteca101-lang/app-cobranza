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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

private const val FIREBASE_WEB_CLIENT_ID =
    "542876060274-8r4jl5t1s3gaulc6i9ofbiu5bd3sckm8.apps.googleusercontent.com"

/**
 * Inicio de sesión Google conservando los scopes operativos de Sheets y Drive.
 * El ID token de Google se intercambia por una sesión Firebase Authentication.
 */
@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val firebaseAuth = remember { FirebaseAuth.getInstance() }

    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(FIREBASE_WEB_CLIENT_ID)
            .requestScopes(
                com.google.android.gms.common.api.Scope(SheetsScopes.SPREADSHEETS),
                com.google.android.gms.common.api.Scope(DriveScopes.DRIVE)
            )
            .build()
    }
    val googleSignInClient: GoogleSignInClient =
        remember { GoogleSignIn.getClient(context, gso) }

    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            loading = false
            error = "Inicio de sesión cancelado."
        } else {
            try {
                val googleTask = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = googleTask.getResult(ApiException::class.java)
                val idToken = account.idToken
                if (idToken.isNullOrBlank()) {
                    loading = false
                    error = "Google no entregó el ID token. Verifica el cliente OAuth Web de Firebase."
                } else {
                    val credential = GoogleAuthProvider.getCredential(idToken, null)
                    firebaseAuth.signInWithCredential(credential)
                        .addOnCompleteListener { authTask ->
                            loading = false
                            if (authTask.isSuccessful && authTask.result?.user != null) {
                                error = null
                                onSignedIn()
                            } else {
                                error = "Firebase Authentication rechazó el acceso: " +
                                    (authTask.exception?.localizedMessage ?: "error desconocido")
                            }
                        }
                }
            } catch (e: ApiException) {
                loading = false
                error = "Error de Google Sign-In (código ${e.statusCode}). Verifica conexión y configuración OAuth."
            } catch (e: Exception) {
                loading = false
                error = "No se pudo completar la autenticación: ${e.localizedMessage ?: "error desconocido"}"
            }
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
            "Inicia sesión con Google para acceder a Matriz y sus servicios sincronizados.",
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
            Text(if (loading) "Autenticando..." else "Iniciar sesión con Google")
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

/**
 * El acceso a la app exige sesión Firebase y conserva la comprobación de scopes
 * Google que todavía necesita SheetsRepository durante la transición.
 */
fun hasSignedInAccount(context: android.content.Context): Boolean {
    if (FirebaseAuth.getInstance().currentUser == null) return false
    val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
    val requiredScopes = setOf(
        com.google.android.gms.common.api.Scope(SheetsScopes.SPREADSHEETS),
        com.google.android.gms.common.api.Scope(DriveScopes.DRIVE)
    )
    return GoogleSignIn.hasPermissions(account, *requiredScopes.toTypedArray())
}
