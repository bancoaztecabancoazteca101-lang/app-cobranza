package com.example.matrizapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Tarjeta para activar "Matriz en la nube" en ESTE teléfono (se usa desde Diagnóstico). */
@Composable
fun MatrizNubeCard() {
    val context = LocalContext.current
    val nube = remember { (context.applicationContext as MainApplication).container.matrizNube }
    val scope = rememberCoroutineScope()
    var activa by remember { mutableStateOf(nube.habilitado) }
    var trabajando by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Matriz en la nube", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (activa) "Activa en este teléfono: Matriz se guarda y se lee de la base central (Firestore)."
                else "Apagada: Matriz sigue sincronizando con Google Sheets como antes.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            if (!activa) {
                Text(
                    "Actívala primero en el teléfono que tiene los datos completos de Matriz. Sube lo que falte en la nube " +
                        "y desde entonces la nube manda.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
            }
            Button(
                enabled = !trabajando,
                onClick = {
                    trabajando = true; mensaje = null
                    scope.launch {
                        try {
                            if (!activa) {
                                val r = nube.activar()
                                activa = true
                                mensaje = "Listo: ${r.subidos} subidos a la nube, ${r.adoptados} ya existían, ${r.borradosLocales} borrados en la nube."
                            } else {
                                val n = nube.traerCambios()
                                mensaje = "Sincronizado ($n documentos revisados)."
                            }
                            nube.arrancar()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            mensaje = "No se pudo: ${e.message}"
                        } finally {
                            trabajando = false
                        }
                    }
                }
            ) {
                if (trabajando) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (activa) "Sincronizar ahora" else "Activar Matriz en la nube")
            }
            mensaje?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
