package com.example.matrizapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private data class FotoPendiente(val textoOcr: String, val candidatos: List<MatrizEntity>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportarMatrizScreen(viewModel: MatrizViewModel) {
    val context = LocalContext.current
    val todosLosRegistros by viewModel.matrizList.collectAsState()
    val scope = rememberCoroutineScope()
    var textoBusqueda by remember { mutableStateOf("") }
    var seleccionados by remember { mutableStateOf<List<MatrizEntity>>(emptyList()) }
    var mostrandoResultados by remember { mutableStateOf(false) }
    var procesandoFotos by remember { mutableStateOf(false) }
    var progresoFotos by remember { mutableStateOf("") }
    var generandoExcel by remember { mutableStateOf(false) }
    var mostrarConfirmacionLimpiar by remember { mutableStateOf(false) }
    var pendientesRevision by remember { mutableStateOf<List<FotoPendiente>>(emptyList()) }

    val resultados = remember(todosLosRegistros, textoBusqueda, seleccionados) {
        buscarCoincidenciasExportacion(todosLosRegistros, textoBusqueda, seleccionados)
    }

    val selectorFotos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        procesandoFotos = true
        scope.launch {
            var agregadosAutomaticamente = 0
            var sinResultado = 0
            val nuevosPendientes = mutableListOf<FotoPendiente>()
            uris.forEachIndexed { index, uri ->
                progresoFotos = "Procesando foto ${index + 1} de ${uris.size}…"
                val nombreDetectado = extraerNombreDeImagen(context, uri)
                if (nombreDetectado.isNullOrBlank()) {
                    sinResultado++
                } else {
                    val candidatos = buscarCoincidenciasExportacion(todosLosRegistros, nombreDetectado, seleccionados)
                    val nombreNormalizado = normalizarNombreParaExportacion(nombreDetectado)
                    val exactas = candidatos.filter { normalizarNombreParaExportacion(it.nombre) == nombreNormalizado }
                    if (exactas.size == 1) {
                        val coincidencia = exactas.first()
                        if (seleccionados.none { it.id == coincidencia.id }) {
                            seleccionados = seleccionados + coincidencia
                            agregadosAutomaticamente++
                        }
                    } else if (candidatos.isNotEmpty()) {
                        nuevosPendientes += FotoPendiente(nombreDetectado, candidatos.take(10))
                    } else sinResultado++
                }
            }
            pendientesRevision = pendientesRevision + nuevosPendientes
            procesandoFotos = false
            progresoFotos = ""
            val mensaje = buildString {
                append("Fotos procesadas: ${uris.size}. Agregados automáticamente: $agregadosAutomaticamente.")
                if (nuevosPendientes.isNotEmpty()) append(" Para revisar: ${nuevosPendientes.size}.")
                if (sinResultado > 0) append(" Sin coincidencia: $sinResultado.")
            }
            Toast.makeText(context, mensaje, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(topBar = { SmallTopAppBar(title = { Text("Exportar Matriz") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.height(4.dp))
            Text("Selecciona los clientes que quieres incluir. La información exportada se toma completa desde Matriz.", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = textoBusqueda,
                    onValueChange = { textoBusqueda = it; mostrandoResultados = it.isNotBlank() },
                    modifier = Modifier.weight(1f), singleLine = true,
                    label = { Text("Nombre, TT o CU") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (textoBusqueda.isNotBlank()) IconButton(onClick = { textoBusqueda = ""; mostrandoResultados = false }) { Icon(Icons.Default.Close, contentDescription = "Limpiar") }
                    }
                )
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = { selectorFotos.launch("image/*") }, enabled = !procesandoFotos) {
                    if (procesandoFotos) CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.CameraAlt, contentDescription = "Agregar fotos")
                }
            }
            if (procesandoFotos && progresoFotos.isNotBlank()) Text(progresoFotos, style = MaterialTheme.typography.bodySmall)

            if (mostrandoResultados && textoBusqueda.isNotBlank()) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("Coincidencias", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        if (resultados.isEmpty()) Text("Sin coincidencias. Prueba otro texto o revisa la ortografía.")
                        else resultados.forEach { registro ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(registro.nombre, style = MaterialTheme.typography.bodyLarge)
                                    Text("TT: ${registro.numTT}  ·  CU: ${registro.folioP.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = {
                                    if (seleccionados.none { it.id == registro.id }) seleccionados = seleccionados + registro
                                    textoBusqueda = ""; mostrandoResultados = false
                                }) { Icon(Icons.Default.Add, contentDescription = "Agregar") }
                            }
                            Divider()
                        }
                    }
                }
            }

            if (pendientesRevision.isNotEmpty()) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("Revisar fotos (${pendientesRevision.size})", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        pendientesRevision.forEachIndexed { index, pendiente ->
                            Text("OCR: ${pendiente.textoOcr}", style = MaterialTheme.typography.bodySmall)
                            pendiente.candidatos.forEach { candidato ->
                                TextButton(onClick = {
                                    if (seleccionados.none { it.id == candidato.id }) seleccionados = seleccionados + candidato
                                    pendientesRevision = pendientesRevision.toMutableList().also { it.removeAt(index) }
                                }) { Text("${candidato.nombre}  ·  TT ${candidato.numTT}") }
                            }
                            Divider()
                        }
                        TextButton(onClick = { pendientesRevision = emptyList() }) { Text("Cerrar revisiones") }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Seleccionados: ${seleccionados.size}", style = MaterialTheme.typography.titleMedium)
                if (seleccionados.isNotEmpty()) TextButton(onClick = { mostrarConfirmacionLimpiar = true }) { Text("Limpiar") }
            }
            Divider()
            if (seleccionados.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("Aún no hay clientes seleccionados.")
                    Spacer(Modifier.height(8.dp))
                    Text("Puedes buscarlos manualmente o agregar una o varias fotos.", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(seleccionados, key = { it.id }) { registro ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(registro.nombre, style = MaterialTheme.typography.titleSmall)
                                    Text("TT: ${registro.numTT}  ·  CU: ${registro.folioP.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = { seleccionados = seleccionados.filterNot { it.id == registro.id } }) { Icon(Icons.Default.Delete, contentDescription = "Quitar") }
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
                            val archivo = generarExcelMatrizConImagenes(context, seleccionados)
                            abrirArchivoExcel(context, archivo)
                        } catch (e: Exception) {
                            Toast.makeText(context, "No se pudo generar el Excel: ${e.message ?: "error desconocido"}", Toast.LENGTH_LONG).show()
                        } finally { generandoExcel = false }
                    }
                },
                enabled = seleccionados.isNotEmpty() && !generandoExcel,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (generandoExcel) {
                    CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text("Generando…")
                } else {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp)); Text("Generar Excel (${seleccionados.size})")
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
            confirmButton = { TextButton(onClick = { seleccionados = emptyList(); mostrarConfirmacionLimpiar = false }) { Text("Limpiar") } },
            dismissButton = { TextButton(onClick = { mostrarConfirmacionLimpiar = false }) { Text("Cancelar") } }
        )
    }
}

private fun buscarCoincidenciasExportacion(registros: List<MatrizEntity>, texto: String, seleccionados: List<MatrizEntity>): List<MatrizEntity> {
    val q = normalizarNombreParaExportacion(texto)
    if (q.isBlank()) return emptyList()
    return registros.asSequence()
        .filter { r -> normalizarNombreParaExportacion(r.nombre).contains(q) || normalizarNombreParaExportacion(r.numTT).contains(q) || normalizarNombreParaExportacion(r.folioP).contains(q) }
        .filterNot { r -> seleccionados.any { it.id == r.id } }
        .take(30).toList()
}

private fun normalizarNombreParaExportacion(valor: String?): String =
    java.text.Normalizer.normalize(valor.orEmpty(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .uppercase(Locale.ROOT).replace("Ñ", "N")
        .replace(Regex("[^A-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

private fun abrirArchivoExcel(context: Context, archivo: File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.example.matrizapp.fileprovider", archivo)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try { context.startActivity(Intent.createChooser(intent, "Abrir Excel")) }
    catch (_: Exception) { Toast.makeText(context, "Excel generado: ${archivo.name}", Toast.LENGTH_LONG).show() }
}

private suspend fun generarExcelMatrizConImagenes(context: Context, registros: List<MatrizEntity>): File = withContext(Dispatchers.IO) {
    val carpeta = File(context.cacheDir, "exportaciones").apply { mkdirs() }
    val archivo = File(carpeta, "matriz_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.xlsx")
    val temp = File(context.cacheDir, "exportaciones_temp").apply { mkdirs() }
    val driveHelper = (context.applicationContext as MainApplication).container.driveHelper

    data class ImagenExportada(val archivo: File, val extension: String, val zipName: String, val fila: Int, val columna: Int)
    val imagenes = mutableListOf<ImagenExportada>()
    var secuencia = 0

    suspend fun prepararImagen(raw: String?, fila: Int, columna: Int): ImagenExportada? {
        if (raw.isNullOrBlank()) return null
        val destino = File(temp, "img_${System.currentTimeMillis()}_${secuencia++}.tmp")
        val ok = try {
            when {
                raw.startsWith("content://") -> {
                    context.contentResolver.openInputStream(Uri.parse(raw))?.use { input -> FileOutputStream(destino).use { output -> input.copyTo(output) } }
                    destino.exists() && destino.length() > 0L
                }
                raw.startsWith("http://") || raw.startsWith("https://") -> driveHelper.downloadFile(raw, destino)
                raw.contains("/") -> driveHelper.downloadByRelativePath(raw, destino)
                else -> false
            }
        } catch (_: Exception) { false }
        if (!ok || !destino.exists() || destino.length() == 0L) { destino.delete(); return null }
        val ext = if (raw.lowercase(Locale.ROOT).contains(".png")) "png" else "jpg"
        val zipName = "xl/media/image${imagenes.size + 1}.$ext"
        return ImagenExportada(destino, ext, zipName, fila, columna)
    }

    registros.forEachIndexed { index, r ->
        val fila = index + 1
        prepararImagen(r.imagenUrl, fila, 9)?.let { imagenes += it }
        prepararImagen(r.imagenUrl2, fila, 10)?.let { imagenes += it }
    }

    FileOutputStream(archivo).use { fos -> ZipOutputStream(fos).use { zip ->
        fun entry(nombre: String, contenido: String) { zip.putNextEntry(ZipEntry(nombre)); zip.write(contenido.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
        fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        fun colName(n: Int): String { var x=n+1; var out=""; while(x>0){ val r=(x-1)%26; out=('A'.code+r).toChar()+out; x=(x-1)/26 }; return out }
        fun cell(col: String, row: Int, value: String): String = "<c r=\"$col$row\" t=\"inlineStr\"><is><t>${esc(value)}</t></is></c>"

        val headers = listOf("Nombre","Sem","Req","NumTT","Ref1","Ref2","Obs","Estado","Ubicación","Img","Img2","Fecha","Id","Hora","Ruta","CU")
        val rows = StringBuilder("<row r=\"1\">")
        headers.forEachIndexed { i,h -> rows.append(cell(colName(i),1,h)) }
        rows.append("</row>")
        registros.forEachIndexed { index,r ->
            val row=index+2
            val fecha=r.fecha?.let{SimpleDateFormat("dd/MM/yyyy", Locale("es","MX")).format(Date(it))}.orEmpty()
            val values=listOf(r.nombre,r.semana,r.requisito,r.numTT,r.ref1,r.ref2,r.observaciones.orEmpty(),r.estado,r.ubicacion.orEmpty(),if(!r.imagenUrl.isNullOrBlank())"Imagen incrustada" else "",if(!r.imagenUrl2.isNullOrBlank())"Imagen incrustada" else "",fecha,r.id,r.hora.orEmpty(),r.ruta.orEmpty(),r.folioP.orEmpty())
            rows.append("<row r=\"$row\" ht=\"95\" customHeight=\"1\">")
            values.forEachIndexed { i,v -> rows.append(cell(colName(i),row,v)) }
            rows.append("</row>")
        }

        val contentTypes="<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"jpg\" ContentType=\"image/jpeg\"/><Default Extension=\"png\" ContentType=\"image/png\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/drawings/drawing1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/></Types>"
        entry("[Content_Types].xml",contentTypes)
        entry("_rels/.rels","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
        entry("xl/workbook.xml","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Matriz Exportada\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
        entry("xl/_rels/workbook.xml.rels","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>")
        entry("xl/worksheets/sheet1.xml","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheetData>$rows</sheetData>${if(imagenes.isNotEmpty())"<drawing r=\"rId1\"/>" else ""}</worksheet>")

        if(imagenes.isNotEmpty()){
            entry("xl/worksheets/_rels/sheet1.xml.rels","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\" Target=\"../drawings/drawing1.xml\"/></Relationships>")
            val drawing=StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
            val rels=StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            imagenes.forEachIndexed{i,img->
                val rid="rId${i+1}"
                drawing.append("<xdr:twoCellAnchor><xdr:from><xdr:col>${img.columna}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${img.fila}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:to><xdr:col>${img.columna+1}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${img.fila+1}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to><xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"${i+1}\" name=\"Imagen ${i+1}\"/><xdr:cNvPicPr/></xdr:nvPicPr><xdr:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:twoCellAnchor>")
                rels.append("<Relationship Id=\"$rid\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/image${i+1}.${img.extension}\"/>")
            }
            drawing.append("</xdr:wsDr>"); rels.append("</Relationships>")
            entry("xl/drawings/drawing1.xml",drawing.toString())
            entry("xl/drawings/_rels/drawing1.xml.rels",rels.toString())
            imagenes.forEach{img->zip.putNextEntry(ZipEntry(img.zipName));img.archivo.inputStream().use{input->input.copyTo(zip)};zip.closeEntry()}
        }
    }}
    imagenes.forEach{it.archivo.delete()}
    archivo
}
