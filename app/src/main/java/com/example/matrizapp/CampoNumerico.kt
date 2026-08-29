package com.example.matrizapp

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

/**
 * Campo numérico que arregla el bug de los OutlinedTextField enlazados directo a un Int:
 * antes, al borrar el campo para escribir un número nuevo, "".toIntOrNull() daba null,
 * el ViewModel nunca se enteraba del cambio, y como el `value` seguía viniendo del Int
 * de antes, Compose "regresaba" el número viejo en cada recomposición — el campo se veía
 * trabado, imposible de vaciar.
 *
 * Aquí el texto que se ve es un estado local (String), no un espejo directo del Int. Solo
 * se avisa al ViewModel cuando el texto es un número válido, y solo se resincroniza desde
 * afuera cuando el valor externo realmente cambió (LaunchedEffect con key = valor) — así el
 * usuario puede dejar el campo vacío un momento mientras escribe sin que se le borre solo.
 */
@Composable
fun CampoNumerico(
    valor: Int,
    onValorValido: (Int) -> Unit,
    etiqueta: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minimo: Int = 0,
    maximo: Int = 9999,
    maxDigitos: Int = 4
) {
    var texto by remember { mutableStateOf(valor.toString()) }

    LaunchedEffect(valor) {
        if (texto.toIntOrNull() != valor) texto = valor.toString()
    }

    OutlinedTextField(
        value = texto,
        onValueChange = { nuevo ->
            if (nuevo.length <= maxDigitos && nuevo.all { it.isDigit() }) {
                texto = nuevo
                nuevo.toIntOrNull()?.let { v -> onValorValido(v.coerceIn(minimo, maximo)) }
            }
        },
        label = { Text(etiqueta) },
        modifier = modifier,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}
