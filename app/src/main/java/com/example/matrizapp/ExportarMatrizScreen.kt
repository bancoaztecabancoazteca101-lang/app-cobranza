package com.example.matrizapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import kotlin.math.min

/**
 * Pantalla independiente para construir una selección de registros de Matriz y exportarla.
 *
 * La foto no es la fuente de datos del Excel: sirve únicamente para obtener un texto de búsqueda.
 * El registro exportado siempre sale de MatrizEntity, evitando perder columnas por OCR.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportarMatrizScreen(viewModel: MatrizViewModel) {
    val context = LocalContext.current
    val todosLosRegistros by viewModel.matrizList.collectAsState()
    val scope = rememberCoroutineScope()

    var textoBusqueda by remember { mutableStateOf("") }
    var seleccionados by remember { mutableStateOf<List<MatrizEntity>>(emptyList()) }
    var mostrandoResultados by remember { mutableStateOf(false) }
    var buscandoPorFoto by remember { mutableStateOf(false) }
    var generandoExcel by remember { mutableStateOf(false) }
    var mostrarConfirmacionLimpiar by remember { mutableStateOf(false) }

    val resultados = remember(todosLosRegistros, textoBusqueda, seleccionados) {
        val q = normalizarNombreParaExportacion(textoBusqueda)
        if (q.isBlank()) {
            emptyList()
        } else {
            todosLosRegistros
                .asSequence()
                .filter { registro ->
                    val nombre = normalizarNombreParaExportacion(registro.nombre)
                    val tt = normalizarNombreParaExportacion(registro.numTT)
                    val cu = normalizarNombreParaExportacion(registro.folioP)
                    nombre.contains(q) || tt.contains(q) || cu.contains(q)
                }
                .filterNot { candidato -> seleccionados.any { it.id == candidato.id } }
                .take(30)
                .toList()
        }
    }

    val selectorFoto = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        buscandoPorFoto = true
        scope.launch {
            val nombreDetectado = extraerNombreDeImagen(context, uri)
            buscandoPorFoto = false
            if (nombreDetectado.isNullOrBlank()) {
                Toast.makeText(
                    context,
                    "No se detectó un nombre. Puedes buscarlo manualmente.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                textoBusqueda = nombreDetectado
                mostrandoResultados = true
            }
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = { Text("Exportar Matriz") },
                actions = {
                    IconButton(onClick = { selectorFoto.launch("image/*") }) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Buscar desde foto")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            Text(
                "Selecciona los clientes que quieres incluir. La información exportada se toma completa desde Matriz.",
                style = MaterialTheme.typography.bodyMedium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textoBusqueda,
                    onValueChange = {
                        textoBusqueda = it
                        mostrandoResultados = it.isNotBlank()
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Nombre, TT o CU") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (textoBusqueda.isNotBlank()) {
                            IconButton(onClick = { textoBusqueda = ""; mostrandoResultados = false }) {
                                Icon(Icons.Default.Close, contentDescription = "Limpiar")
                            }
                        }
                    }
                )
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = { selectorFoto.launch("image/*") },
                    enabled = !buscandoPorFoto
                ) {
                    if (buscandoPorFoto) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Leer foto")
                    }
                }
            }

            if (mostrandoResultados && textoBusqueda.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("Coincidencias", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        if (resultados.isEmpty()) {
                            Text("Sin coincidencias. Prueba otro texto o revisa la ortografía.")
                        } else {
                            resultados.forEach { registro ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(registro.nombre, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "TT: ${registro.numTT}  ·  CU: ${registro.folioP.orEmpty()}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    IconButton(onClick = {
                                        seleccionados = seleccionados + registro
                                        textoBusqueda = ""
                                        mostrandoResultados = false
                                    }) {
                                        Icon(Icons.Default.Add, contentDescription = "Agregar")
                                    }
                                }
                                Divider()
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Seleccionados: ${seleccionados.size}",
                    style = MaterialTheme.typography.titleMedium
                )
                if (seleccionados.isNotEmpty()) {
                    TextButton(onClick = { mostrarConfirmacionLimpiar = true }) {
                        Text("Limpiar")
                    }
                }
            }

            Divider()

            if (seleccionados.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Aún no hay clientes seleccionados.")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Puedes buscarlos manualmente o leer una foto.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(seleccionados, key = { it.id }) { registro ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(registro.nombre, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "TT: ${registro.numTT}  ·  CU: ${registro.folioP.orEmpty()}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                IconButton(onClick = {
                                    seleccionados = seleccionados.filterNot { it.id == registro.id }
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Quitar")
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    generandoExcel = true
                    scope.launch {
                        try {
                            val archivo = generarExcelMatriz(context, seleccionados)
                            abrirArchivoExcel(context, archivo)
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                "No se pudo generar el Excel: ${e.message ?: "error desconocido"}",
                                Toast.LENGTH_LONG
                            ).show()
                        } finally {
                            generandoExcel = false
                        }
                    }
                },
                enabled = seleccionados.isNotEmpty() && !generandoExcel,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (generandoExcel) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Generando…")
                } else {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Generar Excel (${seleccionados.size})")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (mostrarConfirmacionLimpiar) {
        AlertDialog(
            onDismissRequest = { mostrarConfirmacionLimpiar = false },
            title = { Text("Limpiar selección") },
            text = { Text("Se quitarán todos los clientes acumulados. Los datos de Matriz no se modificarán.") },
            confirmButton = {
                TextButton(onClick = {
                    seleccionados = emptyList()
                    mostrarConfirmacionLimpiar = false
                }) { Text("Limpiar") }
            },
            dismissButton = {
                TextButton(onClick = { mostrarConfirmacionLimpiar = false }) { Text("Cancelar") }
            }
        )
    }
}

private fun normalizarNombreParaExportacion(valor: String?): String =
    java.text.Normalizer.normalize(valor.orEmpty(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .uppercase(Locale.ROOT)
        .replace("Ñ", "N")
        .replace(Regex("[^A-Z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun abrirArchivoExcel(context: Context, archivo: File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "com.example.matrizapp.fileprovider",
        archivo
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Abrir Excel"))
    } catch (_: Exception) {
        Toast.makeText(context, "Excel generado: ${archivo.name}", Toast.LENGTH_LONG).show()
    }
}

/**
 * Genera un XLSX mínimo válido usando OOXML directamente, sin agregar una dependencia pesada.
 * Se incluyen todas las columnas reales de MatrizEntity que corresponden a la hoja Matriz.
 */
private fun generarExcelMatriz(context: Context, registros: List<MatrizEntity>): File {
    val carpeta = File(context.cacheDir, "exportaciones").apply { mkdirs() }
    val nombre = "matriz_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.xlsx"
    val archivo = File(carpeta, nombre)

    ZipOutputStream(FileOutputStream(archivo)).use { zip ->
        escribirEntrada(zip, "[Content_Types].xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
              <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            </Types>
        """.trimIndent())

        escribirEntrada(zip, "_rels/.rels", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            </Relationships>
        """.trimIndent())

        escribirEntrada(zip, "xl/workbook.xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets><sheet name="Matriz" sheetId="1" r:id="rId1"/></sheets>
            </workbook>
        """.trimIndent())

        escribirEntrada(zip, "xl/_rels/workbook.xml.rels", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            </Relationships>
        """.trimIndent())

        val encabezados = listOf(
            "Nombre", "Sem", "Req", "NumTT", "Ref1", "Ref2", "Obs", "Estado",
            "Ubicación", "Img", "Img2", "Fecha", "Id", "Hora", "Ruta", "CU"
        )
        val filas = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            append(filaExcel(encabezados, 1))
            registros.forEachIndexed { index, registro ->
                val fecha = registro.fecha?.let {
                    SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(it))
                }.orEmpty()
                append(
                    filaExcel(
                        listOf(
                            registro.nombre,
                            registro.semana,
                            registro.requisito,
                            registro.numTT,
                            registro.ref1,
                            registro.ref2,
                            registro.observaciones.orEmpty(),
                            registro.estado,
                            registro.ubicacion.orEmpty(),
                            registro.imagenUrl.orEmpty(),
                            registro.imagenUrl2.orEmpty(),
                            fecha,
                            registro.id,
                            registro.hora.orEmpty(),
                            registro.ruta.orEmpty(),
                            registro.folioP.orEmpty()
                        ),
                        index + 2
                    )
                )
            }
            append("</sheetData></worksheet>")
        }
        escribirEntrada(zip, "xl/worksheets/sheet1.xml", filas)
    }

    return archivo
}

private fun filaExcel(valores: List<String>, numeroFila: Int): String = buildString {
    append("<row r=\"").append(numeroFila).append("\">")
    valores.forEachIndexed { index, valor ->
        val columna = nombreColumnaExcel(index)
        append("<c r=\"").append(columna).append(numeroFila).append("\" t=\"inlineStr\"><is><t>")
        append(escaparXml(valor))
        append("</t></is></c>")
    }
    append("</row>")
}

private fun nombreColumnaExcel(indice: Int): String {
    var n = indice + 1
    val resultado = StringBuilder()
    while (n > 0) {
        val resto = (n - 1) % 26
        resultado.insert(0, ('A'.code + resto).toChar())
        n = (n - 1) / 26
    }
    return resultado.toString()
}

private fun escaparXml(valor: String): String = buildString {
    valor.forEach { caracter ->
        when (caracter) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> {
                if (caracter == '\t' || caracter == '\n' || caracter == '\r' || caracter.code >= 32) {
                    append(caracter)
                }
            }
        }
    }
}

private fun escribirEntrada(zip: ZipOutputStream, nombre: String, contenido: String) {
    zip.putNextEntry(ZipEntry(nombre))
    zip.write(contenido.toByteArray(Charsets.UTF_8))
    zip.closeEntry()
}
