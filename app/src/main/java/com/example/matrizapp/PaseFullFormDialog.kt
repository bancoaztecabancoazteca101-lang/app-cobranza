package com.example.matrizapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Editor completo específico de Pase.
 * Mantiene los campos habituales del registro y agrega CONTIENE y CAPITALES
 * dentro del mismo formulario de edición, porque ambos pertenecen únicamente a Pase.
 */
@Composable
fun PaseFullFormDialog(
    item: PaseEntity,
    onDismiss: () -> Unit,
    onSave: (
        id: String,
        nombre: String,
        semana: String,
        requisito: String,
        numTT: String,
        ref1: String,
        ref2: String,
        observaciones: String,
        estado: String,
        ubicacion: String,
        fecha: Long?,
        hora: String,
        ruta: String,
        folioP: String,
        contiene: String?,
        capitales: String?
    ) -> Unit
) {
    var id by remember(item.id) { mutableStateOf(item.id) }
    var nombre by remember(item.id) { mutableStateOf(item.nombre) }
    var semana by remember(item.id) { mutableStateOf(item.semana) }
    var requisito by remember(item.id) { mutableStateOf(item.requisito) }
    var numTT by remember(item.id) { mutableStateOf(item.numTT) }
    var ref1 by remember(item.id) { mutableStateOf(item.ref1) }
    var ref2 by remember(item.id) { mutableStateOf(item.ref2) }
    var observaciones by remember(item.id) { mutableStateOf(item.observaciones ?: "") }
    var estado by remember(item.id) { mutableStateOf(item.estado) }
    var ubicacion by remember(item.id) { mutableStateOf(item.ubicacion ?: "") }
    var hora by remember(item.id) { mutableStateOf(item.hora ?: "") }
    var ruta by remember(item.id) { mutableStateOf(item.ruta ?: "") }
    var folioP by remember(item.id) { mutableStateOf(item.folioP ?: "") }
    var contiene by remember(item.id) { mutableStateOf(item.contiene ?: "") }
    var capitales by remember(item.id) { mutableStateOf(item.capitales ?: "") }

    val fechaTextoInicial = remember(item.id, item.fecha) {
        item.fecha?.let { SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(it)) } ?: ""
    }
    var fechaTexto by remember(item.id, item.fecha) { mutableStateOf(fechaTextoInicial) }

    fun parseFecha(texto: String): Long? {
        if (texto.isBlank()) return null
        return try {
            SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).parse(texto)?.time
                ?: SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).parse(texto)?.time
        } catch (_: Exception) {
            item.fecha
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar registro") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(value = nombre, onValueChange = { nombre = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = semana, onValueChange = { semana = it }, label = { Text("Sem") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = requisito, onValueChange = { requisito = it }, label = { Text("Req") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = numTT, onValueChange = { numTT = it }, label = { Text("Num TT") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(value = ref1, onValueChange = { ref1 = it }, label = { Text("Ref 1") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(value = ref2, onValueChange = { ref2 = it }, label = { Text("Ref 2") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))

                Spacer(Modifier.height(2.dp))
                OutlinedTextField(value = fechaTexto, onValueChange = { fechaTexto = it }, label = { Text("Fecha y Hora") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = ruta, onValueChange = { ruta = it }, label = { Text("Ruta") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = folioP, onValueChange = { folioP = it }, label = { Text("CU") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = hora, onValueChange = { hora = it }, label = { Text("Hora") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("ID") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = observaciones, onValueChange = { observaciones = it }, label = { Text("Observaciones") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(value = estado, onValueChange = { estado = it }, label = { Text("Status") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = ubicacion, onValueChange = { ubicacion = it }, label = { Text("Ubicación") }, modifier = Modifier.fillMaxWidth())

                Spacer(Modifier.height(4.dp))
                Text("Datos de Pase")
                OutlinedTextField(
                    value = contiene,
                    onValueChange = { contiene = it },
                    label = { Text("Se Contiene") },
                    prefix = { Text("$ ") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = capitales,
                    onValueChange = { capitales = it },
                    label = { Text("Capitales") },
                    prefix = { Text("$ ") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Text("Estos dos campos se guardan en Pase y no modifican Matriz.")
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    id,
                    nombre,
                    semana,
                    requisito,
                    numTT,
                    ref1,
                    ref2,
                    observaciones,
                    estado,
                    ubicacion,
                    parseFecha(fechaTexto),
                    hora,
                    ruta,
                    folioP,
                    contiene.ifBlank { null },
                    capitales.ifBlank { null }
                )
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
