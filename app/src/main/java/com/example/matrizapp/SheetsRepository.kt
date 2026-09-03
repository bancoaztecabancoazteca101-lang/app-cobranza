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

    /** Busca el sheetId numerico (requerido por la API para batchUpdate/borrado de filas)
     * a partir del nombre visible de la hoja. */
    private fun getSheetIdByTitle(sheetName: String): Int? {
        val meta = sheetsService.spreadsheets().get(Constants.SPREADSHEET_ID)
            .setFields("sheets.properties").execute()
        return meta.sheets.orEmpty().firstOrNull { it.properties?.title == sheetName }?.properties?.sheetId
    }

    /** Elimina por completo la fila de un registro (identificado por su Id) en la hoja indicada.
     * Devuelve true si se encontro y borro la fila, false si no se encontro el Id. */
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

    /** Renombra el ID (columna M en Matriz) de una fila ya existente en el Sheet, buscándola
     * por su ID anterior. Usado cuando el usuario edita a mano el ID autogenerado (p. ej. para
     * evitar que choque con uno que ya generó AppSheet). Devuelve false si la fila con el ID
     * anterior no existe todavía en el Sheet (registro nuevo aún no sincronizado: no hay nada
     * que renombrar remotamente, el ID nuevo se usará directamente en el próximo push). */
    suspend fun renameRowId(sheetName: String, idAnterior: String, idNuevo: String, idColumn: String): Boolean = withContext(Dispatchers.IO) {
        val rowIndex = findRowIndexById(sheetName, idAnterior, idColumn)
        if (rowIndex == -1) return@withContext false
        updateSheetCell(sheetName, idColumn, rowIndex, idNuevo)
        true
    }

    /** Agrega una fila nueva al final de la hoja (para registros creados desde la app). */
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

    /** Trae en vivo la hoja de la semana actual "Cont-Sem-NN" (Nombre, Sem, Req, Id, CU,
     * Imagen, Imagen 2, Colonia, Visitas, UltimaFechaVisita, NumTT, Ubicacion). No se guarda
     * en Room: es solo lectura y son pocos datos. El orden de columnas debe coincidir EXACTO
     * con "encabezadosDestino" en guardarRegistroSemana6 (Apps Script).
     * Si la hoja de esta semana aún no existe (ej. lunes muy temprano, antes de la primera
     * corrida del trigger de Apps Script), regresa lista vacía en vez de fallar.
     * NumTT/Ubicacion (columnas K, L) son opcionales: si el script de Apps Script aún no las
     * manda, llegan vacías y simplemente no se muestran los botones de llamar/GPS. */
    suspend fun fetchSem6Data(sheetName: String = currentSem6SheetName()): List<Sem6Item> = withContext(Dispatchers.IO) {
        val realName = resolveSheetName(sheetName)
        val range = "'$realName'!A2:P"
        val rows = try {
            sheetsService.spreadsheets().values().get(Constants.SPREADSHEET_ID, range).execute().getValues()
        } catch (e: Exception) {
            null // hoja de esta semana todavía no existe, o error de red puntual
        } ?: emptyList()

        rows.mapNotNull { row ->
            val nombre = row.getOrNull(0)?.toString()?.trim()
            if (nombre.isNullOrBlank()) return@mapNotNull null
            Sem6Item(
                nombre = nombre,
                sem = row.getOrNull(1)?.toString()?.trim() ?: "",
                req = row.getOrNull(2)?.toString()?.trim() ?: "",
                id = row.getOrNull(3)?.toString()?.trim() ?: "",
                cu = row.getOrNull(4)?.toString()?.trim() ?: "",
                // Mapeo real de Cont-Sem-NN (guardarRegistroSemana6): A Nombre,B Sem,C Req,D Id,E CU,
                // F Ubicacion,G Imagen,H Imagen 2 (no se usa aqui),I Colonia,J Visitas,K UltimaFechaVisita,L NumTT
                ubicacion = row.getOrNull(5)?.toString()?.trim() ?: "",
                imagenUrl = row.getOrNull(6)?.toString()?.trim(),
                colonia = row.getOrNull(8)?.toString()?.trim() ?: "",
                visitas = row.getOrNull(9)?.toString()?.trim()?.toIntOrNull() ?: 0,
                ultimaFechaVisita = row.getOrNull(10)?.toString()?.trim() ?: "",
                numTT = row.getOrNull(11)?.toString()?.trim() ?: "",
                // Notas editables desde la app: M Se Contiene, N Susceptible, O Observaciones
                seContiene = row.getOrNull(12)?.toString()?.trim() ?: "",
                susceptible = row.getOrNull(13)?.toString()?.trim() ?: "",
                observaciones = row.getOrNull(14)?.toString()?.trim() ?: "",
                capital = row.getOrNull(15)?.toString()?.trim() ?: ""
            )
        }
    }

    /** Lista los nombres reales de todas las hojas "Cont-Sem-NN" que ya existen en el
     * Spreadsheet (una por semana, las crea Apps Script), más reciente primero. Así el
     * selector de semana en la app solo muestra semanas que de verdad tienen datos, sin
     * necesidad de adivinar cuántas semanas atrás hay que ofrecer. */
    suspend fun listSem6SheetNames(): List<String> = withContext(Dispatchers.IO) {
        getRealSheetTitles().values
            .filter { it.startsWith("Cont-Sem-", ignoreCase = true) }
            .distinct()
            .sortedByDescending { it.substringAfterLast("-").trim().toIntOrNull() ?: -1 }
    }

    /** Guarda las notas editables de Semana 6 (Se Contiene, Susceptible, Observaciones, Capital)
     * directo en Sheets: columnas M, N, O, P de la hoja "Cont-Sem-NN" indicada (por defecto la
     * semana actual, pero se puede pasar una semana pasada si el usuario la está viendo). Se
     * escribe de inmediato (sin cola local) porque son pocos registros y el usuario espera
     * confirmación en el momento. Devuelve false si no encontró el Id en esa hoja.
     */
    suspend fun updateSem6Notas(
        id: String, seContiene: String, susceptible: String, observaciones: String, capital: String,
        sheetName: String = currentSem6SheetName()
    ): Boolean =
        withContext(Dispatchers.IO) {
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

    /** Crea la hoja "Ruta IA" con sus encabezados si todavía no existe -- así Diego no tiene
     * que crearla a mano en el Spreadsheet antes del primer uso. Invalida el cache de nombres
     * de hoja tras crearla para que resolveSheetName la encuentre de inmediato. */
    private fun asegurarHojaRutaIAExiste() {
        val yaExiste = getRealSheetTitles().values.any { it.equals(Constants.SHEET_RUTA_IA, ignoreCase = true) }
        if (yaExiste) return
        val addSheetRequest = Request().setAddSheet(
            com.google.api.services.sheets.v4.model.AddSheetRequest().setProperties(
                com.google.api.services.sheets.v4.model.SheetProperties().setTitle(Constants.SHEET_RUTA_IA)
            )
        )
        sheetsService.spreadsheets()
            .batchUpdate(Constants.SPREADSHEET_ID, BatchUpdateSpreadsheetRequest().setRequests(listOf(addSheetRequest)))
            .execute()
        sheetTitleCache = null
        val encabezados = listOf(
            "Id", "Nombre", "CU", "Direccion", "ColoniaCP", "DiasAtraso", "PagoRequerido",
            "Lat", "Lng", "Orden", "EsNuevo", "CuMatrizMatch", "Fecha", "Estado"
        )
        val realName = resolveSheetName(Constants.SHEET_RUTA_IA)
        val body = ValueRange().setValues(listOf(encabezados))
        sheetsService.spreadsheets().values().update(Constants.SPREADSHEET_ID, "'$realName'!A1", body)
            .setValueInputOption("USER_ENTERED").execute()
    }

    suspend fun getDirtyRutaIAItems() = rutaIADao.getDirtyItems()
    suspend fun markRutaIAAsClean(id: String) = rutaIADao.markAsClean(id)

    /** Reemplaza por completo el contenido de la hoja "Ruta IA": borra las filas de datos
     * existentes (A2:N) y sube el lote actual en un solo batch. Se llama justo después de
     * procesar un lote nuevo de fotos, para que la hoja siempre refleje la ruta del día
     * actual sin acumular lotes/días anteriores -- misma idea que la limpieza automática de
     * madrugada (AppsScript/RutaIA.gs), pero disparada al momento desde la app. Push-only:
     * esta hoja nunca se lee de vuelta hacia Room. */
    suspend fun reemplazarRutaIAEnSheet(items: List<RutaIAEntity>) = withContext(Dispatchers.IO) {
        asegurarHojaRutaIAExiste()
        val realName = resolveSheetName(Constants.SHEET_RUTA_IA)
        try {
            sheetsService.spreadsheets().values().clear(
                Constants.SPREADSHEET_ID, "'$realName'!A2:N", com.google.api.services.sheets.v4.model.ClearValuesRequest()
            ).execute()
        } catch (e: Exception) { /* hoja vacía o aún sin filas: no pasa nada, el append de abajo la llena igual */ }
        if (items.isEmpty()) return@withContext
        val filas = items.map { item ->
            listOf(
                item.id, item.nombre, item.cu ?: "", item.direccion, item.coloniaCp ?: "",
                item.diasAtraso?.toString() ?: "", item.pagoRequerido?.toString() ?: "",
                item.lat?.toString() ?: "", item.lng?.toString() ?: "", item.orden.toString(),
                if (item.esNuevo) "TRUE" else "FALSE", item.cuMatrizMatch ?: "",
                DateUtils.toSheetsSerial(item.fechaDia), item.estado
            )
        }
        val body = ValueRange().setValues(filas)
        sheetsService.spreadsheets().values().append(Constants.SPREADSHEET_ID, "'$realName'!A1", body)
            .setValueInputOption("USER_ENTERED")
            .setInsertDataOption("INSERT_ROWS")
            .execute()
        Unit
    }

    /** Trae los datos actuales del Spreadsheet hacia la base local (Room). */
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

    /** Consulta a Google los nombres REALES de las hojas y arma un mapa normalizado -> nombre exacto.
     * Evita fallos por caracteres invisibles/espacios que no se ven al copiar el nombre a mano. */
    private fun getRealSheetTitles(): Map<String, String> {
        sheetTitleCache?.let { return it }
        val meta = sheetsService.spreadsheets().get(Constants.SPREADSHEET_ID)
            .setFields("sheets.properties.title").execute()
        val map = meta.sheets.orEmpty().mapNotNull { it.properties?.title }
            .associateBy { it.trim().lowercase().replace(Regex("\\s+"), " ") }
        sheetTitleCache = map
        return map
    }

    /** Traduce un nombre "adivinado" de hoja al nombre exacto que Google tiene registrado. */
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
        // No pisar registros que aún tienen cambios locales pendientes de subir (isDirty=1):
        // el pull trae lo que YA está en el Sheet, que todavía no incluye ese cambio, y un
        // insertAll con REPLACE sobrescribiría (perdería) la foto/estado local sin subir.
        val dirtyIds = matrizDao.getDirtyItems().map { it.id }.toSet()
        val rows = fetchRows(Constants.SHEET_MATRIZ)
        val items = rows.mapNotNull { row ->
            val id = cell(row, Constants.MatrizCols.ID) ?: return@mapNotNull null
            if (id in dirtyIds) return@mapNotNull null
            val nombre = cell(row, Constants.MatrizCols.NOMBRE) ?: ""
            if (nombre.contains("Pase semana", ignoreCase = true) || nombre.isBlank()) return@mapNotNull null
            val fechaSerial = DateUtils.parseCellDateToEpochMillis(cell(row, Constants.MatrizCols.FECHA))
            MatrizEntity(
                id = id, nombre = nombre,
                semana = cell(row, Constants.MatrizCols.SEMANA) ?: "",
                requisito = cell(row, Constants.MatrizCols.REQUISITO) ?: "",
                numTT = cell(row, Constants.MatrizCols.NUMTT) ?: "",
                ref1 = cell(row, Constants.MatrizCols.REF1) ?: "",
                ref2 = cell(row, Constants.MatrizCols.REF2) ?: "",
                observaciones = cell(row, Constants.MatrizCols.OBSERVACIONES),
                estado = cell(row, Constants.MatrizCols.ESTADO) ?: "",
                ubicacion = cell(row, Constants.MatrizCols.UBICACION),
                imagenUrl = cell(row, Constants.MatrizCols.IMAGEN),
                imagenUrl2 = cell(row, Constants.MatrizCols.IMAGEN2),
                fecha = fechaSerial,
                hora = cell(row, Constants.MatrizCols.HORA),
                ruta = cell(row, Constants.MatrizCols.RUTA),
                folioP = cell(row, Constants.MatrizCols.FOLIOP)
            )
        }
        if (items.isNotEmpty()) matrizDao.insertAll(items)
    }

    /** Copia a Pase (local, Room) los registros de Matriz cuyo status es "PASE" y cuya fecha
     * cae en la semana actual, una sola vez por registro (no vuelve a tocar una copia ya
     * hecha, ni sobrescribe cambios locales en Pase). Reemplaza el pull viejo desde la hoja
     * "Pase de Cartera" -- esa hoja nunca se llenaba porque dependía de un Apps Script aparte;
     * ahora Matriz "pasa" los datos directo dentro de la app, sin intermediario. */
    private suspend fun copiarPaseDesdeMatriz() {
        val registros = matrizDao.getAllMatriz().first()
        val yaCopiados = paseDao.getOrigenesYaCopiados().toSet()
        val nuevos = registros
            .filter { it.estado.equals("PASE", ignoreCase = true) && estaEnSemanaActual(it.fecha) }
            .filter { it.id !in yaCopiados }
            .map { m ->
                PaseEntity(
                    id = java.util.UUID.randomUUID().toString().replace("-", "").take(12),
                    nombre = m.nombre, semana = m.semana, requisito = m.requisito, numTT = m.numTT,
                    ref1 = m.ref1, ref2 = m.ref2, observaciones = m.observaciones, estado = m.estado,
                    ubicacion = m.ubicacion, imagenUrl = m.imagenUrl, imagenUrl2 = m.imagenUrl2,
                    fecha = m.fecha, hora = m.hora, ruta = m.ruta, folioP = m.folioP,
                    origenMatrizId = m.id
                )
            }
        nuevos.forEach { paseDao.insertar(it) }
    }

    private suspend fun refreshSolicitud() {
        // Mismo motivo que en refreshMatriz: si el registro tiene audio/foto/estado grabado
        // localmente y aún no se sube (isDirty=1), no lo pisamos con lo que trae el Sheet
        // (que todavía está vacío en esa columna) o se pierde el audio/foto antes de subirse.
        val dirtyIds = solicitudDao.getDirtyItems().map { it.id }.toSet()
        val rows = fetchRows(Constants.SHEET_SOLICITUD)
        val items = rows.mapNotNull { row ->
            val id = cell(row, Constants.SolicitudCols.ID) ?: return@mapNotNull null
            if (id in dirtyIds) return@mapNotNull null
            SolicitudEntity(
                id = id,
                nombre = cell(row, Constants.SolicitudCols.NOMBRE) ?: "",
                numero = cell(row, Constants.SolicitudCols.NUMERO),
                sucursal = cell(row, Constants.SolicitudCols.SUCURSAL),
                ubicacionRaw = cell(row, Constants.SolicitudCols.UBICACION),
                imageUrl = cell(row, Constants.SolicitudCols.IMAGEN),
                imageUrl2 = cell(row, Constants.SolicitudCols.IMAGEN2),
                nombreRef1 = cell(row, Constants.SolicitudCols.NOMBRE_REF1),
                ref1 = cell(row, Constants.SolicitudCols.REF1),
                nombreRef2 = cell(row, Constants.SolicitudCols.NOMBRE_REF2),
                ref2 = cell(row, Constants.SolicitudCols.REF2),
                observaciones = cell(row, Constants.SolicitudCols.OBSERVACIONES),
                audioUrl = cell(row, Constants.SolicitudCols.AUDIO),
                estado = cell(row, Constants.SolicitudCols.ESTADO) ?: "",
                imageUrl3 = cell(row, Constants.SolicitudCols.IMAGEN3),
                imageUrl4 = cell(row, Constants.SolicitudCols.IMAGEN4),
                gestorAsignado = cell(row, Constants.SolicitudCols.GESTOR) ?: "Flores",
                fechaHora = DateUtils.parseCellDateToEpochMillis(cell(row, Constants.SolicitudCols.FECHA_HORA))
            )
        }
        if (items.isNotEmpty()) solicitudDao.insertAll(items)
    }

    private suspend fun refreshFiltroFecha() {
        // Mismo motivo que en refreshMatriz/refreshSolicitud: si el status se cambió localmente
        // desde la app y todavía no se sube (isDirty=1), no lo pisamos con lo que trae el Sheet
        // (que aún no tiene ese cambio) ni lo borramos con el deleteAll de abajo.
        val dirtyIds = filtroDao.getDirtyItems().map { it.id }.toSet()
        val rows = fetchRows(Constants.SHEET_FILTRO)
        val items = rows.mapNotNull { row ->
            val id = cell(row, Constants.FiltroCols.ID) ?: return@mapNotNull null
            if (id in dirtyIds) return@mapNotNull null
            val fechaMillis = DateUtils.parseCellDateToEpochMillis(cell(row, Constants.FiltroCols.FECHA)) ?: return@mapNotNull null
            FiltroFechaEntity(
                id = id,
                nombre = cell(row, Constants.FiltroCols.NOMBRE) ?: "",
                estado = cell(row, Constants.FiltroCols.ESTADO) ?: "",
                observaciones = cell(row, Constants.FiltroCols.OBSERVACIONES),
                numTT = cell(row, Constants.FiltroCols.NUMTT) ?: "",
                fecha = fechaMillis,
                hora = cell(row, Constants.FiltroCols.HORA),
                imagenUrl = cell(row, Constants.FiltroCols.IMAGEN),
                ref1 = cell(row, Constants.FiltroCols.REF1),
                ref2 = cell(row, Constants.FiltroCols.REF2),
                ubicacion = cell(row, Constants.FiltroCols.UBICACION),
                req = cell(row, Constants.FiltroCols.REQ)
            )
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
            FiltrarEntity(
                id = id, nombre = nombre,
                semana = cell(row, Constants.FiltrarCols.SEMANA) ?: "",
                requerido = cell(row, Constants.FiltrarCols.REQUERIDO) ?: "",
                numTT = cell(row, Constants.FiltrarCols.NUMTT) ?: "",
                referencias = refsTexto,
                observaciones = cell(row, Constants.FiltrarCols.OBSERVACIONES),
                estado = cell(row, Constants.FiltrarCols.ESTADO) ?: "",
                ubicacion = cell(row, Constants.FiltrarCols.UBICACION),
                imagen = cell(row, Constants.FiltrarCols.IMAGEN),
                fecha = fechaMillis,
                hora = cell(row, Constants.FiltrarCols.HORA)
            )
        }
        if (items.isNotEmpty()) filtrarDao.insertAll(items)
    }

    private suspend fun refreshControl() {
        val rows = fetchRows(Constants.SHEET_CONTROL, lastCol = "F")
        val items = rows.mapNotNull { row ->
            val semana = cell(row, Constants.ControlCols.SEMANA) ?: return@mapNotNull null
            val requeridoStr = row.drop(1)
                .mapNotNull { it?.toString()?.trim() }
                .firstOrNull { it.replace(",", "").replace("$", "").toDoubleOrNull() != null }
                ?: "0"
            ControlEntity(semana = semana, requerido = requeridoStr)
        }
        controlDao.deleteAll()
        if (items.isNotEmpty()) controlDao.insertAll(items)
    }
}