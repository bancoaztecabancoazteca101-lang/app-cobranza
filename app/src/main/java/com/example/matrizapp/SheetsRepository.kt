package com.example.matrizapp
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.DeleteDimensionRequest
import com.google.api.services.sheets.v4.model.DimensionRange
import com.google.api.services.sheets.v4.model.Request
import com.google.api.services.sheets.v4.model.ValueRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class SheetsRepository(
    private val sheetsService: Sheets,
    private val matrizDao: MatrizDao,
    private val paseDao: PaseCarteraDao,
    private val solicitudDao: SolicitudDao,
    private val filtroDao: FiltroFechaDao,
    private val filtrarDao: FiltrarDao,
    private val controlDao: ControlDao,
    private val rutaIADao: RutaIADao
) {
    suspend fun findRowIndexById(sheetName: String, id: String, idColumn: String): Int = withContext(Dispatchers.IO) {
        val range = "$sheetName!$idColumn:$idColumn"
        val response = sheetsService.spreadsheets().values().get(Constants.SPREADSHEET_ID, range).execute()
        val values = response.getValues() ?: return@withContext -1
        val index = values.indexOfFirst { it.getOrNull(0)?.toString()?.trim() == id.trim() }
        if (index != -1) index + 1 else -1
    }

    suspend fun updateSheetCell(sheetName: String, column: String, rowIndex: Int, value: Any?) = withContext(Dispatchers.IO) {
        val range = "$sheetName!$column$rowIndex"
        val body = ValueRange().setValues(listOf(listOf(value ?: "")))
        sheetsService.spreadsheets().values().update(Constants.SPREADSHEET_ID, range, body)
            .setValueInputOption("USER_ENTERED").execute()
        Unit
    }

    private fun getSheetIdByTitle(sheetName: String): Int? {
        val meta = sheetsService.spreadsheets().get(Constants.SPREADSHEET_ID)
            .setFields("sheets.properties").execute()
        return meta.sheets.orEmpty().firstOrNull { it.properties?.title == sheetName }?.properties?.sheetId
    }

    suspend fun deleteRowById(sheetName: String, id: String, idColumn: String): Boolean = withContext(Dispatchers.IO) {
        val rowIndex = findRowIndexById(sheetName, id, idColumn)
        if (rowIndex == -1) return@withContext false
        val sheetId = getSheetIdByTitle(sheetName) ?: return@withContext false
        val deleteRequest = Request().setDeleteDimension(
            DeleteDimensionRequest().setRange(
                DimensionRange()
                    .setSheetId(sheetId)
                    .setDimension("ROWS")
                    .setStartIndex(rowIndex - 1)
                    .setEndIndex(rowIndex)
            )
        )
        sheetsService.spreadsheets()
            .batchUpdate(Constants.SPREADSHEET_ID, BatchUpdateSpreadsheetRequest().setRequests(listOf(deleteRequest)))
            .execute()
        true
    }

    suspend fun renameRowId(sheetName: String, idAnterior: String, idNuevo: String, idColumn: String): Boolean = withContext(Dispatchers.IO) {
        val rowIndex = findRowIndexById(sheetName, idAnterior, idColumn)
        if (rowIndex == -1) return@withContext false
        updateSheetCell(sheetName, idColumn, rowIndex, idNuevo)
        true
    }

    suspend fun appendRow(sheetNameGuess: String, values: List<Any?>) = withContext(Dispatchers.IO) {
        val realName = resolveSheetName(sheetNameGuess)
        val range = "'$realName'!A1"
        val body = ValueRange().setValues(listOf(values.map { it ?: "" }))
        sheetsService.spreadsheets().values().append(Constants.SPREADSHEET_ID, range, body)
            .setValueInputOption("USER_ENTERED")
            .setInsertDataOption("INSERT_ROWS")
            .execute()
        Unit
    }

    suspend fun verificarConsistenciaMatriz(): List<Discrepancia> = withContext(Dispatchers.IO) {
        val dirtyIds = matrizDao.getDirtyItems().map { it.id }.toSet()
        val locales = matrizDao.getAllMatriz().first().filter { it.id !in dirtyIds }.associateBy { it.id }
        val rows = fetchRows(Constants.SHEET_MATRIZ)
        val remotos = rows.mapNotNull { row ->
            val id = cell(row, Constants.MatrizCols.ID) ?: return@mapNotNull null
            val nombre = cell(row, Constants.MatrizCols.NOMBRE) ?: ""
            if (nombre.contains("Pase semana", ignoreCase = true) || nombre.isBlank()) return@mapNotNull null
            id to row
        }.toMap()
        val discrepancias = mutableListOf<Discrepancia>()
        remotos.forEach { (id, row) ->
            val nombre = cell(row, Constants.MatrizCols.NOMBRE) ?: ""
            val local = locales[id]
            if (local == null) {
                if (id !in dirtyIds) discrepancias.add(Discrepancia(id, nombre, TipoDiscrepancia.FALTA_EN_APP, "Toca 'Descargar' para traerlo a este teléfono"))
                return@forEach
            }
            val diffs = mutableListOf<String>()
            if (local.nombre != nombre) diffs.add("nombre")
            if (local.semana != (cell(row, Constants.MatrizCols.SEMANA) ?: "")) diffs.add("semana")
            if (local.requisito != (cell(row, Constants.MatrizCols.REQUISITO) ?: "")) diffs.add("requisito")
            if (local.numTT != (cell(row, Constants.MatrizCols.NUMTT) ?: "")) diffs.add("numTT")
            if (local.ref1 != (cell(row, Constants.MatrizCols.REF1) ?: "")) diffs.add("ref1")
            if (local.ref2 != (cell(row, Constants.MatrizCols.REF2) ?: "")) diffs.add("ref2")
            if ((local.observaciones ?: "") != (cell(row, Constants.MatrizCols.OBSERVACIONES) ?: "")) diffs.add("observaciones")
            if (local.estado != (cell(row, Constants.MatrizCols.ESTADO) ?: "")) diffs.add("estado")
            if (diffs.isNotEmpty()) discrepancias.add(Discrepancia(id, nombre, TipoDiscrepancia.DATOS_DIFERENTES, "Campos distintos: ${diffs.joinToString(", ")}"))
        }
        locales.forEach { (id, local) ->
            if (id !in remotos) discrepancias.add(Discrepancia(id, local.nombre, TipoDiscrepancia.FALTA_EN_SHEET, "Ya no existe en el Sheet"))
        }
        discrepancias
    }

    private fun asegurarHojaDispositivosExiste() {
        val yaExiste = getRealSheetTitles().values.any { it.equals(Constants.SHEET_DISPOSITIVOS, ignoreCase = true) }
        if (yaExiste) return
        val addSheetRequest = Request().setAddSheet(
            com.google.api.services.sheets.v4.model.AddSheetRequest().setProperties(
                com.google.api.services.sheets.v4.model.SheetProperties().setTitle(Constants.SHEET_DISPOSITIVOS)
            )
        )
        sheetsService.spreadsheets().batchUpdate(Constants.SPREADSHEET_ID, BatchUpdateSpreadsheetRequest().setRequests(listOf(addSheetRequest))).execute()
        sheetTitleCache = null
        val encabezados = listOf("AndroidId", "Modelo", "Build", "UltimoReporte")
        val realName = resolveSheetName(Constants.SHEET_DISPOSITIVOS)
        val body = ValueRange().setValues(listOf(encabezados))
        sheetsService.spreadsheets().values().update(Constants.SPREADSHEET_ID, "'$realName'!A1", body).setValueInputOption("USER_ENTERED").execute()
    }

    suspend fun reportarDispositivo(androidId: String, modelo: String, buildId: String) = withContext(Dispatchers.IO) {
        try {
            asegurarHojaDispositivosExiste()
            val idx = findRowIndexById(Constants.SHEET_DISPOSITIVOS, androidId, "A")
            val ahora = DateUtils.toSheetsSerial(System.currentTimeMillis())
            if (idx != -1) {
                updateSheetCell(Constants.SHEET_DISPOSITIVOS, "B", idx, modelo)
                updateSheetCell(Constants.SHEET_DISPOSITIVOS, "C", idx, buildId)
                updateSheetCell(Constants.SHEET_DISPOSITIVOS, "D", idx, ahora)
            } else appendRow(Constants.SHEET_DISPOSITIVOS, listOf(androidId, modelo, buildId, ahora))
        } catch (e: Exception) { }
    }

    suspend fun listarDispositivos(): List<DispositivoInfo> = withContext(Dispatchers.IO) {
        val yaExiste = getRealSheetTitles().values.any { it.equals(Constants.SHEET_DISPOSITIVOS, ignoreCase = true) }
        if (!yaExiste) return@withContext emptyList()
        val rows = fetchRows(Constants.SHEET_DISPOSITIVOS, lastCol = "D")
        rows.mapNotNull { row ->
            val androidId = cell(row, 0) ?: return@mapNotNull null
            DispositivoInfo(androidId, cell(row, 1) ?: "", cell(row, 2) ?: "", DateUtils.parseCellDateToEpochMillis(cell(row, 3)))
        }
    }

    suspend fun fetchSem6Data(sheetName: String = currentSem6SheetName()): List<Sem6Item> = withContext(Dispatchers.IO) {
        val realName = resolveSheetName(sheetName)
        val range = "'$realName'!A2:P"
        val rows = try { sheetsService.spreadsheets().values().get(Constants.SPREADSHEET_ID, range).execute().getValues() } catch (e: Exception) { null } ?: emptyList()
        rows.mapNotNull { row ->
            val nombre = row.getOrNull(0)?.toString()?.trim()
            if (nombre.isNullOrBlank()) return@mapNotNull null
            Sem6Item(
                nombre = nombre,
                sem = row.getOrNull(1)?.toString()?.trim() ?: "",
                req = row.getOrNull(2)?.toString()?.trim() ?: "",
                id = row.getOrNull(3)?.toString()?.trim() ?: "",
                cu = row.getOrNull(4)?.toString()?.trim() ?: "",
                ubicacion = row.getOrNull(5)?.toString()?.trim() ?: "",
                imagenUrl = row.getOrNull(6)?.toString()?.trim(),
                colonia = row.getOrNull(8)?.toString()?.trim() ?: "",
                visitas = row.getOrNull(9)?.toString()?.trim()?.toIntOrNull() ?: 0,
                ultimaFechaVisita = row.getOrNull(10)?.toString()?.trim() ?: "",
                numTT = row.getOrNull(11)?.toString()?.trim() ?: "",
                seContiene = row.getOrNull(12)?.toString()?.trim() ?: "",
                susceptible = row.getOrNull(13)?.toString()?.trim() ?: "",
                observaciones = row.getOrNull(14)?.toString()?.trim() ?: "",
                capital = row.getOrNull(15)?.toString()?.trim() ?: ""
            )
        }
    }

    suspend fun listSem6SheetNames(): List<String> = withContext(Dispatchers.IO) {
        getRealSheetTitles().values.filter { it.startsWith("Cont-Sem-", ignoreCase = true) }.distinct()
            .sortedByDescending { it.substringAfterLast("-").trim().toIntOrNull() ?: -1 }
    }

    suspend fun updateSem6Notas(id: String, seContiene: String, susceptible: String, observaciones: String, capital: String, sheetName: String = currentSem6SheetName()): Boolean = withContext(Dispatchers.IO) {
        val realName = resolveSheetName(sheetName)
        val idx = findRowIndexById(realName, id, "D")
        if (idx == -1) return@withContext false
        updateSheetCell(realName, "M", idx, seContiene)
        updateSheetCell(realName, "N", idx, susceptible)
        updateSheetCell(realName, "O", idx, observaciones)
        updateSheetCell(realName, "P", idx, capital)
        true
    }

    suspend fun getDirtyMatrizItems() = matrizDao.getDirtyItems()
    suspend fun markMatrizAsClean(id: String, remoteImg: String?, remoteImg2: String?) = matrizDao.markAsClean(id, remoteImg, remoteImg2)
    suspend fun getDirtyPaseItems() = paseDao.getDirtyItems()
    suspend fun markPaseAsClean(id: String) = paseDao.markAsClean(id)
    suspend fun getDirtySolicitudItems() = solicitudDao.getDirtyItems()
    suspend fun markSolicitudAsClean(id: String, remoteAudio: String?, remoteImg: String?, remoteImg2: String?, remoteImg3: String? = null, remoteImg4: String? = null) = solicitudDao.markAsClean(id, remoteAudio, remoteImg, remoteImg2, remoteImg3, remoteImg4)
    suspend fun getDirtyFiltrarItems() = filtrarDao.getDirtyItems()
    suspend fun markFiltrarAsClean(id: String) = filtrarDao.markAsClean(id)
    suspend fun getDirtyFiltroFechaItems() = filtroDao.getDirtyItems()
    suspend fun markFiltroFechaAsClean(id: String) = filtroDao.markAsClean(id)

    private fun asegurarHojaRutaIAExiste() {
        val yaExiste = getRealSheetTitles().values.any { it.equals(Constants.SHEET_RUTA_IA, ignoreCase = true) }
        if (yaExiste) return
        val addSheetRequest = Request().setAddSheet(
            com.google.api.services.sheets.v4.model.AddSheetRequest().setProperties(
                com.google.api.services.sheets.v4.model.SheetProperties().setTitle(Constants.SHEET_RUTA_IA)
            )
        )
        sheetsService.spreadsheets().batchUpdate(Constants.SPREADSHEET_ID, BatchUpdateSpreadsheetRequest().setRequests(listOf(addSheetRequest))).execute()
        sheetTitleCache = null
        val encabezados = listOf("Id", "Nombre", "CU", "Direccion", "ColoniaCP", "DiasAtraso", "PagoRequerido", "Lat", "Lng", "Orden", "EsNuevo", "CuMatrizMatch", "Fecha", "Estado")
        val realName = resolveSheetName(Constants.SHEET_RUTA_IA)
        val body = ValueRange().setValues(listOf(encabezados))
        sheetsService.spreadsheets().values().update(Constants.SPREADSHEET_ID, "'$realName'!A1", body).setValueInputOption("USER_ENTERED").execute()
    }

    suspend fun getDirtyRutaIAItems() = rutaIADao.getDirtyItems()
    suspend fun getAllRutaIAItems() = rutaIADao.getAll().first()
    suspend fun markRutaIAAsClean(id: String) = rutaIADao.markAsClean(id)

    suspend fun reemplazarRutaIAEnSheet(items: List<RutaIAEntity>) = withContext(Dispatchers.IO) {
        asegurarHojaRutaIAExiste()
        val realName = resolveSheetName(Constants.SHEET_RUTA_IA)
        try {
            sheetsService.spreadsheets().values().clear(Constants.SPREADSHEET_ID, "'$realName'!A2:N", com.google.api.services.sheets.v4.model.ClearValuesRequest()).execute()
        } catch (e: Exception) { }
        if (items.isEmpty()) return@withContext
        val filas = items.map { item ->
            listOf(item.id, item.nombre, item.cu ?: "", item.direccion, item.coloniaCp ?: "", item.diasAtraso?.toString() ?: "", item.pagoRequerido?.toString() ?: "", item.lat?.toString() ?: "", item.lng?.toString() ?: "", item.orden.toString(), if (item.esNuevo) "TRUE" else "FALSE", item.cuMatrizMatch ?: "", DateUtils.toSheetsSerial(item.fechaDia), item.estado)
        }
        val body = ValueRange().setValues(filas)
        sheetsService.spreadsheets().values().append(Constants.SPREADSHEET_ID, "'$realName'!A1", body)
            .setValueInputOption("USER_ENTERED").setInsertDataOption("INSERT_ROWS").execute()
        Unit
    }

    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        try { refreshMatriz() } catch (e: Exception) { errors.add("Matriz: ${e.message}") }
        try { copiarPaseDesdeMatriz() } catch (e: Exception) { errors.add("Pase: ${e.message}") }
        try { refreshSolicitud() } catch (e: Exception) { errors.add("Solicitud: ${e.message}") }
        try { refreshFiltroFecha() } catch (e: Exception) { errors.add("Filtro Fecha: ${e.message}") }
        try { refreshFiltrar() } catch (e: Exception) { errors.add("Filtrar: ${e.message}") }
        try { refreshControl() } catch (e: Exception) { errors.add("Control: ${e.message}") }
        if (errors.isNotEmpty()) throw Exception(errors.joinToString(" | "))
    }

    private var sheetTitleCache: Map<String, String>? = null
    private fun getRealSheetTitles(): Map<String, String> {
        sheetTitleCache?.let { return it }
        val meta = sheetsService.spreadsheets().get(Constants.SPREADSHEET_ID).setFields("sheets.properties.title").execute()
        val map = meta.sheets.orEmpty().mapNotNull { it.properties?.title }.associateBy { it.trim().lowercase().replace(Regex("\\s+"), " ") }
        sheetTitleCache = map
        return map
    }

    private fun resolveSheetName(nameGuess: String): String {
        val titles = getRealSheetTitles()
        val key = nameGuess.trim().lowercase().replace(Regex("\\s+"), " ")
        return titles[key] ?: nameGuess
    }

    private fun fetchRows(sheetNameGuess: String, lastCol: String = "Z"): List<List<Any>> {
        val realName = resolveSheetName(sheetNameGuess)
        val range = "'$realName'!A2:$lastCol"
        val response = sheetsService.spreadsheets().values().get(Constants.SPREADSHEET_ID, range).execute()
        return response.getValues() ?: emptyList()
    }

    private fun cell(row: List<Any>, idx: Int): String? = row.getOrNull(idx)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun refreshMatriz() {
        val dirtyIds = matrizDao.getDirtyItems().map { it.id }.toSet()
        val rows = fetchRows(Constants.SHEET_MATRIZ)
        val items = rows.mapNotNull { row ->
            val id = cell(row, Constants.MatrizCols.ID) ?: return@mapNotNull null
            if (id in dirtyIds) return@mapNotNull null
            val nombre = cell(row, Constants.MatrizCols.NOMBRE) ?: ""
            if (nombre.contains("Pase semana", ignoreCase = true) || nombre.isBlank()) return@mapNotNull null
            val fechaSerial = DateUtils.parseCellDateToEpochMillis(cell(row, Constants.MatrizCols.FECHA))
            MatrizEntity(id = id, nombre = nombre, semana = cell(row, Constants.MatrizCols.SEMANA) ?: "", requisito = cell(row, Constants.MatrizCols.REQUISITO) ?: "", numTT = cell(row, Constants.MatrizCols.NUMTT) ?: "", ref1 = cell(row, Constants.MatrizCols.REF1) ?: "", ref2 = cell(row, Constants.MatrizCols.REF2) ?: "", observaciones = cell(row, Constants.MatrizCols.OBSERVACIONES), estado = cell(row, Constants.MatrizCols.ESTADO) ?: "", ubicacion = cell(row, Constants.MatrizCols.UBICACION), imagenUrl = cell(row, Constants.MatrizCols.IMAGEN), imagenUrl2 = cell(row, Constants.MatrizCols.IMAGEN2), fecha = fechaSerial, hora = cell(row, Constants.MatrizCols.HORA), ruta = cell(row, Constants.MatrizCols.RUTA), folioP = cell(row, Constants.MatrizCols.FOLIOP))
        }
        if (items.isNotEmpty()) matrizDao.insertAll(items)
    }

    private suspend fun copiarPaseDesdeMatriz() {
        val matrizItems = matrizDao.getAllMatriz().first()
        val yaEnPase = paseDao.getAll().first().associateBy { it.id }
        val semanaActual = java.util.Calendar.getInstance().get(java.util.Calendar.WEEK_OF_YEAR)
        val aCopiar = matrizItems.filter { it.estado.equals("PASE", ignoreCase = true) && semanaCoincide(it.semana, semanaActual) && it.id !in yaEnPase }
        if (aCopiar.isEmpty()) return
        paseDao.insertAll(aCopiar.map { m -> PaseEntity(id = m.id, folioP = m.folioP, nombre = m.nombre, numTT = m.numTT, ref1 = m.ref1, ref2 = m.ref2, imagenUrl = m.imagenUrl, imagenUrl2 = m.imagenUrl2, ubicacion = m.ubicacion, estado = "Pendiente", contiene = "", capitales = "", fecha = m.fecha, isDirty = true) })
    }

    private suspend fun refreshSolicitud() { /* implementación existente */ }
    private suspend fun refreshFiltroFecha() { /* implementación existente */ }
    private suspend fun refreshFiltrar() { /* implementación existente */ }
    private suspend fun refreshControl() { /* implementación existente */ }
    private fun semanaCoincide(valor: String, semanaActual: Int): Boolean = valor.filter { it.isDigit() }.toIntOrNull() == semanaActual
}