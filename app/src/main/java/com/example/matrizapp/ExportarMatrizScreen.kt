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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    val todos by viewModel.matrizList.collectAsState()
    val scope = rememberCoroutineScope()
    var busqueda by remember { mutableStateOf("") }
    var seleccionados by remember { mutableStateOf<List<MatrizEntity>>(emptyList()) }
    var mostrarResultados by remember { mutableStateOf(false) }
    var procesando by remember { mutableStateOf(false) }
    var progreso by remember { mutableStateOf("") }
    var generando by remember { mutableStateOf(false) }
    var limpiar by remember { mutableStateOf(false) }
    var pendientes by remember { mutableStateOf<List<FotoPendiente>>(emptyList()) }

    val resultados = remember(todos, busqueda, seleccionados) { buscarCoincidenciasExportacion(todos, busqueda, seleccionados) }
    val selector = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        procesando = true
        scope.launch {
            var auto = 0
            var sin = 0
            val nuevos = mutableListOf<FotoPendiente>()
            uris.forEachIndexed { i, uri ->
                progreso = "Procesando foto ${i + 1} de ${uris.size}…"
                val texto = extraerNombreDeImagen(context, uri)
                if (texto.isNullOrBlank()) { sin++ } else {
                    val candidatos = buscarCoincidenciasExportacion(todos, texto, seleccionados)
                    val normal = normalizarNombreParaExportacion(texto)
                    val exactas = candidatos.filter { normalizarNombreParaExportacion(it.nombre) == normal }
                    if (exactas.size == 1) {
                        val r = exactas.first()
                        if (seleccionados.none { it.id == r.id }) { seleccionados += r; auto++ }
                    } else if (candidatos.isNotEmpty()) nuevos += FotoPendiente(texto, candidatos.take(10)) else sin++
                }
            }
            pendientes += nuevos
            procesando = false
            progreso = ""
            Toast.makeText(context, "Fotos procesadas: ${uris.size}. Agregados: $auto. Revisar: ${nuevos.size}. Sin coincidencia: $sin.", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(topBar = { SmallTopAppBar(title = { Text("Exportar Matriz") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.height(4.dp))
            Text("Selecciona los clientes que quieres incluir. La información exportada se toma completa desde Matriz.", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = busqueda, onValueChange = { busqueda = it; mostrarResultados = it.isNotBlank() }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Nombre, TT o CU") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (busqueda.isNotBlank()) IconButton({ busqueda = ""; mostrarResultados = false }) { Icon(Icons.Default.Close, "Limpiar") } })
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = { selector.launch("image/*") }, enabled = !procesando) { if (procesando) CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.CameraAlt, "Agregar fotos") }
            }
            if (progreso.isNotBlank()) Text(progreso, style = MaterialTheme.typography.bodySmall)

            if (mostrarResultados && busqueda.isNotBlank()) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("Coincidencias", style = MaterialTheme.typography.titleSmall)
                        resultados.forEach { r ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(r.nombre); Text("TT: ${r.numTT} · CU: ${r.folioP.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
                                IconButton({ if (seleccionados.none { it.id == r.id }) seleccionados += r; busqueda = ""; mostrarResultados = false }) { Icon(Icons.Default.Add, "Agregar") }
                            }
                            Divider()
                        }
                        if (resultados.isEmpty()) Text("Sin coincidencias. Prueba otro texto.")
                    }
                }
            }

            if (pendientes.isNotEmpty()) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("Revisar fotos (${pendientes.size})", style = MaterialTheme.typography.titleSmall)
                        Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                            pendientes.forEachIndexed { index, p ->
                                Text("OCR: ${p.textoOcr}", style = MaterialTheme.typography.bodySmall)
                                p.candidatos.forEach { r -> TextButton({ if (seleccionados.none { it.id == r.id }) seleccionados += r; pendientes = pendientes.toMutableList().also { it.removeAt(index) } }) { Text("${r.nombre} · TT ${r.numTT}") } }
                                Divider()
                            }
                        }
                        TextButton({ pendientes = emptyList() }) { Text("Cerrar revisiones") }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Seleccionados: ${seleccionados.size}", style = MaterialTheme.typography.titleMedium)
                if (seleccionados.isNotEmpty()) TextButton({ limpiar = true }) { Text("Limpiar") }
            }
            Divider()
            if (seleccionados.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("Aún no hay clientes seleccionados."); Text("Puedes buscarlos manualmente o agregar fotos.", style = MaterialTheme.typography.bodySmall) }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(seleccionados, key = { it.id }) { r -> Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(r.nombre, style = MaterialTheme.typography.titleSmall); Text("TT: ${r.numTT} · CU: ${r.folioP.orEmpty()}", style = MaterialTheme.typography.bodySmall) }; IconButton({ seleccionados = seleccionados.filterNot { it.id == r.id } }) { Icon(Icons.Default.Delete, "Quitar") } } } }
                }
            }
            Button(onClick = {
                generando = true
                scope.launch {
                    try {
                        val resultado = generarExcelMatrizConUrls(context, seleccionados)
                        abrirArchivoExcel(context, resultado.archivo)
                        Toast.makeText(context, "Excel generado. Enlaces de imagen: ${resultado.urlsEncontradas}/${resultado.totalFuentes}.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) { Toast.makeText(context, "No se pudo generar el Excel: ${e.message ?: "error"}", Toast.LENGTH_LONG).show() }
                    finally { generando = false }
                }
            }, enabled = seleccionados.isNotEmpty() && !generando, modifier = Modifier.fillMaxWidth()) {
                if (generando) { CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Generando…") } else { Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Generar Excel (${seleccionados.size})") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (limpiar) AlertDialog(onDismissRequest = { limpiar = false }, title = { Text("Limpiar selección") }, text = { Text("Se quitarán todos los clientes acumulados. Matriz no se modifica.") }, confirmButton = { TextButton({ seleccionados = emptyList(); limpiar = false }) { Text("Limpiar") } }, dismissButton = { TextButton({ limpiar = false }) { Text("Cancelar") } })
}

private fun buscarCoincidenciasExportacion(registros: List<MatrizEntity>, texto: String, seleccionados: List<MatrizEntity>): List<MatrizEntity> {
    val q = normalizarNombreParaExportacion(texto)
    if (q.isBlank()) return emptyList()
    return registros.asSequence().filter { r -> normalizarNombreParaExportacion(r.nombre).contains(q) || normalizarNombreParaExportacion(r.numTT).contains(q) || normalizarNombreParaExportacion(r.folioP).contains(q) }.filterNot { r -> seleccionados.any { it.id == r.id } }.take(30).toList()
}

private fun normalizarNombreParaExportacion(valor: String?): String = java.text.Normalizer.normalize(valor.orEmpty(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").uppercase(Locale.ROOT).replace("Ñ", "N").replace(Regex("[^A-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

private fun abrirArchivoExcel(context: Context, archivo: File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.example.matrizapp.fileprovider", archivo)
    val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    try { context.startActivity(Intent.createChooser(intent, "Abrir Excel")) } catch (_: Exception) { Toast.makeText(context, "Excel generado: ${archivo.name}", Toast.LENGTH_LONG).show() }
}

private data class ResultadoExcel(val archivo: File, val totalFuentes: Int, val urlsEncontradas: Int)

private suspend fun generarExcelMatrizConUrls(context: Context, registros: List<MatrizEntity>): ResultadoExcel = withContext(Dispatchers.IO) {
    val carpeta = File(context.cacheDir, "exportaciones").apply { mkdirs() }
    val archivo = File(carpeta, "matriz_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.xlsx")
    val driveHelper = (context.applicationContext as MainApplication).container.driveHelper
    data class Enlace(val fila: Int, val columna: Int, val url: String)
    val enlaces = mutableListOf<Enlace>()
    var totalFuentes = 0

    suspend fun resolver(raw: String?, fila: Int, columna: Int) {
        if (raw.isNullOrBlank()) return
        totalFuentes++
        driveHelper.findImageUrl(raw)?.let { enlaces += Enlace(fila, columna, it) }
    }
    registros.forEachIndexed { index, r -> val fila = index + 2; resolver(r.imagenUrl, fila, 9); resolver(r.imagenUrl2, fila, 10) }

    FileOutputStream(archivo).use { fos -> ZipOutputStream(fos).use { zip ->
        fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        fun col(n: Int): String { var x=n+1; var o=""; while(x>0){ val r=(x-1)%26; o=('A'.code+r).toChar()+o; x=(x-1)/26 }; return o }
        val enlacePorCelda = enlaces.associateBy { "${it.fila}:${it.columna}" }
        fun cell(c: String, row: Int, value: String) = "<c r=\"$c$row\" t=\"inlineStr\"><is><t>${esc(value)}</t></is></c>"
        val headers = listOf("Nombre","Sem","Req","NumTT","Ref1","Ref2","Obs","Estado","Ubicación","Img","Img2","Fecha","Id","Hora","Ruta","CU")
        val rows = StringBuilder("<row r=\"1\">")
        headers.forEachIndexed { i,h -> rows.append(cell(col(i),1,h)) }; rows.append("</row>")
        registros.forEachIndexed { i,r ->
            val row=i+2
            val fecha=r.fecha?.let { SimpleDateFormat("dd/MM/yyyy", Locale("es","MX")).format(Date(it)) }.orEmpty()
            val vals=listOf(r.nombre,r.semana,r.requisito,r.numTT,r.ref1,r.ref2,r.observaciones.orEmpty(),r.estado,r.ubicacion.orEmpty(),"","",fecha,r.id,r.hora.orEmpty(),r.ruta.orEmpty(),r.folioP.orEmpty())
            rows.append("<row r=\"$row\">")
            vals.forEachIndexed { c,v -> val link=enlacePorCelda["$row:$c"]; rows.append(cell(col(c),row,if(link!=null) "Ver imagen" else v)) }
            rows.append("</row>")
        }
        val linksXml=StringBuilder()
        val relsXml=StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        enlaces.forEachIndexed { i,e -> val rid="rId${i+1}"; linksXml.append("<hyperlink ref=\"${col(e.columna)}${e.fila}\" r:id=\"$rid\"/>"); relsXml.append("<Relationship Id=\"$rid\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"${esc(e.url)}\" TargetMode=\"External\"/>") }
        relsXml.append("</Relationships>")
        val sheetRels=if(enlaces.isNotEmpty()) "<hyperlinks>$linksXml</hyperlinks>" else ""
        val sheet="<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheetData>$rows</sheetData>$sheetRels</worksheet>"
        val ct="<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>"
        entry("[Content_Types].xml",ct)
        entry("_rels/.rels","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
        entry("xl/workbook.xml","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Matriz Exportada\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
        entry("xl/_rels/workbook.xml.rels","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>")
        entry("xl/worksheets/sheet1.xml",sheet)
        if(enlaces.isNotEmpty()) entry("xl/worksheets/_rels/sheet1.xml.rels",relsXml.toString())
    }}
    ResultadoExcel(archivo,totalFuentes,enlaces.size)
}