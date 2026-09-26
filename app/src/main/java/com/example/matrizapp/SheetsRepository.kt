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
    private val rutaIADao: RutaIADao,
    private val canalPagoDao: CanalPagoDao
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
                DimensionRange().setSheetId(sheetId).setDimension("ROWS")
                    .setStartIndex(rowIndex - 1).setEndIndex(rowIndex)
            )
        )
        sheetsService.spreadsheets().batchUpdate(
            Constants.SPREADSHEET_ID,
            BatchUpdateSpreadsheetRequest().setRequests(listOf(deleteRequest))
        ).execute()
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
            .setValueInputOption("USER_ENTERED").setInsertDataOption("INSERT_ROWS").execute()
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
                nombre = nombre, sem = row.getOrNull(1)?.toString()?.trim() ?: "", req = row.getOrNull(2)?.toString()?.trim() ?: "",
                id = row.getOrNull(3)?.toString()?.trim() ?: "", cu = row.getOrNull(4)?.toString()?.trim() ?: "",
                ubicacion = row.getOrNull(5)?.toString()?.trim() ?: "", imagenUrl = row.getOrNull(6)?.toString()?.trim(),
                colonia = row.getOrNull(8)?.toString()?.trim() ?: "", visitas = row.getOrNull(9)?.toString()?.trim()?.toIntOrNull() ?: 0,
                ultimaFechaVisita = row.getOrNull(10)?.toString()?.trim() ?: "", numTT = row.getOrNull(11)?.toString()?.trim() ?: "",
                seContiene = row.getOrNull(12)?.toString()?.trim() ?: "", susceptible = row.getOrNull(13)?.toString()?.trim() ?: "",
                observaciones = row.getOrNull(14)?.toString()?.trim() ?: "", capital = row.getOrNull(15)?.toString()?.trim() ?: ""
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

    /** Agrega un registro nuevo a la hoja "Cont-Sem-NN" de la semana indicada (antes esta hoja
     * era de solo lectura, poblada solo por el script de Apps Script; Diego pidió poder agregar
     * registros desde la app también). Genera un ID nuevo y regresa el Sem6Item creado. */
    suspend fun appendSem6Row(
        nombre: String, sem: String, req: String, cu: String, colonia: String, ubicacion: String, numTT: String,
        sheetName: String = currentSem6SheetName()
    ): Sem6Item = withContext(Dispatchers.IO) {
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        val fechaHora = java.text.SimpleDateFormat("d/M/yyyy HH:mm", java.util.Locale("es", "MX")).format(java.util.Date())
        // Orden de columnas A-P igual al que lee fetchSem6Data: nombre, sem, req, id, cu,
        // ubicacion, imagenUrl(vacío), (H sin usar), colonia, visitas(0), ultimaFechaVisita,
        // numTT, seContiene, susceptible, observaciones, capital (estos últimos 4 vacíos).
        appendRow(sheetName, listOf(nombre, sem, req, id, cu, ubicacion, "", "", colonia, 0, fechaHora, numTT, "", "", "", ""))
        Sem6Item(
            nombre = nombre, sem = sem, req = req, id = id, cu = cu, imagenUrl = null, colonia = colonia,
            visitas = 0, ultimaFechaVisita = fechaHora, numTT = numTT, ubicacion = ubicacion
        )
    }

    /** Elimina un registro de la hoja "Cont-Sem-NN" de la semana indicada, por su ID (columna D). */
    suspend fun deleteSem6Row(id: String, sheetName: String = currentSem6SheetName()): Boolean = withContext(Dispatchers.IO) {
        val realName = resolveSheetName(sheetName)
        deleteRowById(realName, id, "D")
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

    /** Catálogo local de sucursales/lugares de pago (ver CanalPagoEntity) -- la hoja la llena un
     * script de Apps Script (AppsScript/SincronizarCanalesPago.gs) que consulta Overpass desde
     * el servidor de Google, no desde el teléfono, así que no depende de que la red del celular
     * en campo deje llegar a esos dominios. Esta función solo LEE la hoja y reemplaza la copia
     * local en Room -- no crea la hoja (eso lo hace el script de Apps Script la primera vez que
     * corre) ni escribe nada de vuelta. */
    suspend fun sincronizarCatalogoCanalesPago(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val yaExiste = getRealSheetTitles().values.any { it.equals(Constants.SHEET_CANALES_PAGO, ignoreCase = true) }
            if (!yaExiste) return@withContext Result.failure(IllegalStateException("La hoja '${Constants.SHEET_CANALES_PAGO}' todavía no existe -- falta correr el script de Apps Script al menos una vez."))
            val rows = fetchRows(Constants.SHEET_CANALES_PAGO, lastCol = "F")
            val items = rows.mapNotNull { row ->
                val id = cell(row, 0) ?: return@mapNotNull null
                val nombre = cell(row, 1) ?: return@mapNotNull null
                val lat = cell(row, 4)?.toDoubleOrNull() ?: return@mapNotNull null
                val lng = cell(row, 5)?.toDoubleOrNull() ?: return@mapNotNull null
                CanalPagoEntity(id = id, nombre = nombre, empresa = cell(row, 2), direccion = cell(row, 3), lat = lat, lng = lng)
            }
            canalPagoDao.deleteAll()
            canalPagoDao.insertAll(items)
            Result.success(items.size)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun catalogoCanalesPagoLocal(): List<CanalPagoEntity> = canalPagoDao.getAll()
    suspend fun catalogoCanalesPagoCount(): Int = canalPagoDao.count()
    suspend fun catalogoCanalesPagoDesactualizado(maxEdadMs: Long): Boolean {
        val masViejo = canalPagoDao.oldestSync() ?: return true
        return (System.currentTimeMillis() - masViejo) > maxEdadMs
    }

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
            sheetsService.spreadsheets().values().clear(Constants.SPREADSHEET_ID, "'$realName'!A2:O", com.google.api.services.sheets.v4.model.ClearValuesRequest()).execute()
        } catch (e: Exception) { }
        // Columna O = SaldoAtraso (agregada para poder sincronizar la ruta entre dispositivos sin
        // perder ese dato). Se escribe el encabezado siempre porque la hoja ya existente solo llegaba a N.
        try {
            sheetsService.spreadsheets().values().update(Constants.SPREADSHEET_ID, "'$realName'!O1", ValueRange().setValues(listOf(listOf("SaldoAtraso")))).setValueInputOption("USER_ENTERED").execute()
        } catch (e: Exception) { }
        if (items.isEmpty()) return@withContext
        val filas = items.map { item ->
            listOf(item.id, item.nombre, item.cu ?: "", item.direccion, item.coloniaCp ?: "", item.diasAtraso?.toString() ?: "", item.pagoRequerido?.toString() ?: "", item.lat?.toString() ?: "", item.lng?.toString() ?: "", item.orden.toString(), if (item.esNuevo) "TRUE" else "FALSE", item.cuMatrizMatch ?: "", DateUtils.toSheetsSerial(item.fechaDia), item.estado, item.saldoAtraso?.toString() ?: "")
        }
        val body = ValueRange().setValues(filas)
        sheetsService.spreadsheets().values().append(Constants.SPREADSHEET_ID, "'$realName'!A1", body)
            .setValueInputOption("USER_ENTERED").setInsertDataOption("INSERT_ROWS").execute()
        Unit
    }


    /** Descarga la ruta vigente de la hoja "Ruta IA" a Room, para que un segundo dispositivo vea la
     * ruta que generó otro (antes solo se subía, nunca se leía de vuelta). Reglas:
     * - Si este teléfono tiene cambios sin subir (isDirty), primero los sube y NO descarga: lo local gana.
     * - Si no hay cambios locales, la hoja es la verdad: se insertan/actualizan las filas y se borran
     *   las locales que ya no estén (otra persona limpió la ruta o el borrado de las 4 AM).
     * - Si la hoja no existe todavía, no hace nada. Devuelve cuántas paradas quedaron en local. */
    suspend fun refreshRutaIA(): Int = withContext(Dispatchers.IO) {
        val yaExiste = getRealSheetTitles().values.any { it.equals(Constants.SHEET_RUTA_IA, ignoreCase = true) }
        if (!yaExiste) return@withContext rutaIADao.getAll().first().size
        if (rutaIADao.getDirtyItems().isNotEmpty()) {
            val locales = rutaIADao.getAll().first()
            reemplazarRutaIAEnSheet(locales)
            locales.forEach { rutaIADao.markAsClean(it.id) }
            return@withContext locales.size
        }
        val realName = resolveSheetName(Constants.SHEET_RUTA_IA)
        val respuesta = sheetsService.spreadsheets().values().get(Constants.SPREADSHEET_ID, "'$realName'!A2:O")
            .setValueRenderOption("UNFORMATTED_VALUE").execute()
        val filas = respuesta.getValues() ?: emptyList()
        fun texto(row: List<Any>, i: Int): String? = row.getOrNull(i)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        fun numero(row: List<Any>, i: Int): Double? = row.getOrNull(i)?.toString()?.trim()?.replace(",", ".")?.toDoubleOrNull()
        val locales = rutaIADao.getAll().first().associateBy { it.id }
        val items = filas.mapNotNull { row ->
            val id = texto(row, 0) ?: return@mapNotNull null
            val nombre = texto(row, 1) ?: return@mapNotNull null
            val serial = numero(row, 12)
            // Se redondea al minuto: el serial de Sheets es un double y el millis resultante puede
            // quedar unos ms antes de la medianoche real.
            val fechaDia = serial?.let { ((DateUtils.sheetsSerialToEpochMillis(it) + 30_000L) / 60_000L) * 60_000L } ?: System.currentTimeMillis()
            RutaIAEntity(
                id = id, nombre = nombre, cu = texto(row, 2), direccion = texto(row, 3) ?: "",
                coloniaCp = texto(row, 4), diasAtraso = numero(row, 5)?.toInt(), pagoRequerido = numero(row, 6),
                saldoAtraso = numero(row, 14), lat = numero(row, 7), lng = numero(row, 8),
                orden = numero(row, 9)?.toInt() ?: 0,
                esNuevo = !(texto(row, 10)?.equals("FALSE", ignoreCase = true) ?: false),
                cuMatrizMatch = texto(row, 11), fechaDia = fechaDia, estado = texto(row, 13) ?: "Pendiente",
                fotoOrigenUrl = locales[id]?.fotoOrigenUrl, isDirty = false
            )
        }
        if (items.isEmpty()) {
            rutaIADao.deleteAll()
        } else {
            rutaIADao.insertAll(items)
            rutaIADao.deleteNotIn(items.map { it.id })
        }
        items.size
    }

    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        try { refreshMatriz() } catch (e: Exception) { errors.add("Matriz: ${e.message}") }
        try { copiarPaseDesdeMatriz() } catch (e: Exception) { errors.add("Pase: ${e.message}") }
        try { refreshSolicitud() } catch (e: Exception) { errors.add("Solicitud: ${e.message}") }
        try { refreshFiltroFecha() } catch (e: Exception) { errors.add("Filtro Fecha: ${e.message}") }
        try { refreshFiltrar() } catch (e: Exception) { errors.add("Filtrar: ${e.message}") }
        try { refreshControl() } catch (e: Exception) { errors.add("Control: ${e.message}") }
        try { refreshRutaIA() } catch (e: Exception) { errors.add("Ruta IA: ${e.message}") }
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
        val idsExistentes = matrizDao.getAllMatriz().first().map { it.id }.toSet()
        val rows = fetchRows(Constants.SHEET_MATRIZ)
        val nuevos = mutableListOf<MatrizEntity>()
        for (row in rows) {
            val id = cell(row, Constants.MatrizCols.ID) ?: continue
            if (id in dirtyIds) continue
            val nombre = cell(row, Constants.MatrizCols.NOMBRE) ?: ""
            if (nombre.contains("Pase semana", ignoreCase = true) || nombre.isBlank()) continue
            val fechaSerial = DateUtils.parseCellDateToEpochMillis(cell(row, Constants.MatrizCols.FECHA))
            val semana = cell(row, Constants.MatrizCols.SEMANA) ?: ""
            val requisito = cell(row, Constants.MatrizCols.REQUISITO) ?: ""
            val numTT = cell(row, Constants.MatrizCols.NUMTT) ?: ""
            val ref1 = cell(row, Constants.MatrizCols.REF1) ?: ""
            val ref2 = cell(row, Constants.MatrizCols.REF2) ?: ""
            val observaciones = cell(row, Constants.MatrizCols.OBSERVACIONES)
            val estado = cell(row, Constants.MatrizCols.ESTADO) ?: ""
            val ubicacion = cell(row, Constants.MatrizCols.UBICACION)
            val imagenUrl = cell(row, Constants.MatrizCols.IMAGEN)
            val imagenUrl2 = cell(row, Constants.MatrizCols.IMAGEN2)
            val hora = cell(row, Constants.MatrizCols.HORA)
            val ruta = cell(row, Constants.MatrizCols.RUTA)
            val folioP = cell(row, Constants.MatrizCols.FOLIOP)
            if (id in idsExistentes) {
                // UPDATE parcial -- ver el comentario en MatrizDao.actualizarDesdeSheet(): nunca
                // toca descuentoPago/descuentoAhorro, así que no hay carrera posible con un
                // guardado local de la oferta de descuento que ocurra mientras este pull corre.
                matrizDao.actualizarDesdeSheet(id, nombre, semana, requisito, numTT, ref1, ref2, observaciones, estado, ubicacion, imagenUrl, imagenUrl2, fechaSerial, hora, ruta, folioP)
            } else {
                // Registro nuevo que recién llega del Sheet (aún no existía en Room) -- no puede
                // tener oferta local capturada todavía, descuentoPago/descuentoAhorro quedan en
                // null (default del constructor).
                nuevos.add(MatrizEntity(
                    id = id, nombre = nombre, semana = semana, requisito = requisito, numTT = numTT,
                    ref1 = ref1, ref2 = ref2, observaciones = observaciones, estado = estado, ubicacion = ubicacion,
                    imagenUrl = imagenUrl, imagenUrl2 = imagenUrl2, fecha = fechaSerial, hora = hora, ruta = ruta, folioP = folioP
                ))
            }
        }
        if (nuevos.isNotEmpty()) matrizDao.insertAll(nuevos)
    }

    private suspend fun copiarPaseDesdeMatriz() {
        val registros = matrizDao.getAllMatriz().first()
        val yaCopiados = paseDao.getOrigenesYaCopiados().toSet()
        val nuevos = registros.filter { it.estado.equals("PASE", ignoreCase = true) && estaEnSemanaActual(it.fecha) }
            .filter { it.id !in yaCopiados }
            .map { m ->
                PaseEntity(
                    id = java.util.UUID.randomUUID().toString().replace("-", "").take(12), nombre = m.nombre, semana = m.semana, requisito = m.requisito, numTT = m.numTT,
                    ref1 = m.ref1, ref2 = m.ref2, observaciones = m.observaciones, estado = m.estado, ubicacion = m.ubicacion, imagenUrl = m.imagenUrl, imagenUrl2 = m.imagenUrl2,
                    fecha = m.fecha, hora = m.hora, ruta = m.ruta, folioP = m.folioP, origenMatrizId = m.id
                )
            }
        nuevos.forEach { paseDao.insertar(it) }
    }

    private suspend fun refreshSolicitud() {
        val dirtyIds = solicitudDao.getDirtyItems().map { it.id }.toSet()
        val rows = fetchRows(Constants.SHEET_SOLICITUD)
        val items = rows.mapNotNull { row ->
            val id = cell(row, Constants.SolicitudCols.ID) ?: return@mapNotNull null
            if (id in dirtyIds) return@mapNotNull null
            SolicitudEntity(
                id = id, nombre = cell(row, Constants.SolicitudCols.NOMBRE) ?: "", numero = cell(row, Constants.SolicitudCols.NUMERO), sucursal = cell(row, Constants.SolicitudCols.SUCURSAL),
                ubicacionRaw = cell(row, Constants.SolicitudCols.UBICACION), imageUrl = cell(row, Constants.SolicitudCols.IMAGEN), imageUrl2 = cell(row, Constants.SolicitudCols.IMAGEN2),
                nombreRef1 = cell(row, Constants.SolicitudCols.NOMBRE_REF1), ref1 = cell(row, Constants.SolicitudCols.REF1), nombreRef2 = cell(row, Constants.SolicitudCols.NOMBRE_REF2), ref2 = cell(row, Constants.SolicitudCols.REF2),
                observaciones = cell(row, Constants.SolicitudCols.OBSERVACIONES), audioUrl = cell(row, Constants.SolicitudCols.AUDIO), estado = cell(row, Constants.SolicitudCols.ESTADO) ?: "",
                imageUrl3 = cell(row, Constants.SolicitudCols.IMAGEN3), imageUrl4 = cell(row, Constants.SolicitudCols.IMAGEN4), gestorAsignado = cell(row, Constants.SolicitudCols.GESTOR) ?: "Flores",
                fechaHora = DateUtils.parseCellDateToEpochMillis(cell(row, Constants.SolicitudCols.FECHA_HORA))
            )
        }
        if (items.isNotEmpty()) solicitudDao.insertAll(items)
    }

    private suspend fun refreshFiltroFecha() {
        val dirtyIds = filtroDao.getDirtyItems().map { it.id }.toSet()
        val rows = fetchRows(Constants.SHEET_FILTRO)
        val items = rows.mapNotNull { row ->
            val id = cell(row, Constants.FiltroCols.ID) ?: return@mapNotNull null
            if (id in dirtyIds) return@mapNotNull null
            val fechaMillis = DateUtils.parseCellDateToEpochMillis(cell(row, Constants.FiltroCols.FECHA)) ?: return@mapNotNull null
            FiltroFechaEntity(id = id, nombre = cell(row, Constants.FiltroCols.NOMBRE) ?: "", estado = cell(row, Constants.FiltroCols.ESTADO) ?: "", observaciones = cell(row, Constants.FiltroCols.OBSERVACIONES), numTT = cell(row, Constants.FiltroCols.NUMTT) ?: "", fecha = fechaMillis, hora = cell(row, Constants.FiltroCols.HORA), imagenUrl = cell(row, Constants.FiltroCols.IMAGEN), ref1 = cell(row, Constants.FiltroCols.REF1), ref2 = cell(row, Constants.FiltroCols.REF2), ubicacion = cell(row, Constants.FiltroCols.UBICACION), req = cell(row, Constants.FiltroCols.REQ))
        }
        filtroDao.deleteAllClean()
        if (items.isNotEmpty()) filtroDao.insertAll(items)
    }

    private suspend fun refreshFiltrar() {
        val rows = fetchRows(Constants.SHEET_FILTRAR, lastCol = "AB")
        val items = rows.mapNotNull { row ->
            val id = cell(row, Constants.FiltrarCols.ID) ?: return@mapNotNull null
            val nombre = cell(row, Constants.FiltrarCols.NOMBRE) ?: ""
            val refsTexto = Constants.FiltrarCols.REF_PAIRS.mapNotNull { (nIdx, rIdx) ->
                val n = cell(row, nIdx); val r = cell(row, rIdx)
                if (n != null || r != null) "${n ?: ""}: ${r ?: ""}" else null
            }.joinToString("\n").takeIf { it.isNotBlank() }
            val fechaMillis = DateUtils.parseCellDateToEpochMillis(cell(row, Constants.FiltrarCols.FECHA))
            FiltrarEntity(id = id, nombre = nombre, semana = cell(row, Constants.FiltrarCols.SEMANA) ?: "", requerido = cell(row, Constants.FiltrarCols.REQUERIDO) ?: "", numTT = cell(row, Constants.FiltrarCols.NUMTT) ?: "", referencias = refsTexto, observaciones = cell(row, Constants.FiltrarCols.OBSERVACIONES), estado = cell(row, Constants.FiltrarCols.ESTADO) ?: "", ubicacion = cell(row, Constants.FiltrarCols.UBICACION), imagen = cell(row, Constants.FiltrarCols.IMAGEN), fecha = fechaMillis, hora = cell(row, Constants.FiltrarCols.HORA))
        }
        if (items.isNotEmpty()) filtrarDao.insertAll(items)
    }

    private suspend fun refreshControl() {
        val rows = fetchRows(Constants.SHEET_CONTROL, lastCol = "F")
        val items = rows.mapNotNull { row ->
            val semana = cell(row, Constants.ControlCols.SEMANA) ?: return@mapNotNull null
            val requeridoStr = row.drop(1).mapNotNull { it?.toString()?.trim() }
                .firstOrNull { it.replace(",", "").replace("$", "").toDoubleOrNull() != null } ?: "0"
            ControlEntity(semana = semana, requerido = requeridoStr)
        }
        controlDao.deleteAll()
        if (items.isNotEmpty()) controlDao.insertAll(items)
    }
}