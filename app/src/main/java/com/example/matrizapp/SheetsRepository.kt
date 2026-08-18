package com.example.matrizapp
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.ValueRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SheetsRepository(
    private val sheetsService: Sheets,
    private val matrizDao: MatrizDao,
    private val paseDao: PaseCarteraDao,
    private val solicitudDao: SolicitudDao,
    private val filtroDao: FiltroFechaDao,
    private val filtrarDao: FiltrarDao,
    private val controlDao: ControlDao
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
     * Imagen, Imagen 2, Colonia, Visitas, UltimaFechaVisita). No se guarda en Room: es solo
     * lectura y son pocos datos. El orden de columnas debe coincidir EXACTO con
     * "encabezadosDestino" en guardarRegistroSemana6 (Apps Script).
     * Si la hoja de esta semana aún no existe (ej. lunes muy temprano, antes de la primera
     * corrida del trigger de Apps Script), regresa lista vacía en vez de fallar. */
    suspend fun fetchSem6Data(): List<Sem6Item> = withContext(Dispatchers.IO) {
        val sheetNameGuess = currentSem6SheetName()
        val realName = resolveSheetName(sheetNameGuess)
        val range = "'$realName'!A2:J"
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
                // índices 5 y 6 son Imagen / Imagen 2, no se usan en esta pantalla
                colonia = row.getOrNull(7)?.toString()?.trim() ?: "",
                visitas = row.getOrNull(8)?.toString()?.trim()?.toIntOrNull() ?: 0,
                ultimaFechaVisita = row.getOrNull(9)?.toString()?.trim() ?: ""
            )
        }
    }

    suspend fun getDirtyMatrizItems() = matrizDao.getDirtyItems()
    suspend fun markMatrizAsClean(id: String, remoteImg: String?, remoteImg2: String?) = matrizDao.markAsClean(id, remoteImg, remoteImg2)

    suspend fun getDirtyPaseItems() = paseDao.getDirtyItems()
    suspend fun markPaseAsClean(id: String) = paseDao.markAsClean(id)

    suspend fun getDirtySolicitudItems() = solicitudDao.getDirtyItems()
    suspend fun markSolicitudAsClean(id: String, remoteAudio: String?, remoteImg: String?, remoteImg2: String?, remoteImg3: String? = null, remoteImg4: String? = null) = solicitudDao.markAsClean(id, remoteAudio, remoteImg, remoteImg2, remoteImg3, remoteImg4)

    suspend fun getDirtyFiltrarItems() = filtrarDao.getDirtyItems()
    suspend fun markFiltrarAsClean(id: String) = filtrarDao.markAsClean(id)

    /** Trae los datos actuales del Spreadsheet hacia la base local (Room). */
    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        try { refreshMatriz() } catch (e: Exception) { errors.add("Matriz: ${e.message}") }
        try { refreshPase() } catch (e: Exception) { errors.add("Pase: ${e.message}") }
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

    private suspend fun refreshPase() {
        val dirtyIds = paseDao.getDirtyItems().map { it.id }.toSet()
        val rows = fetchRows(Constants.SHEET_PASE)
        val items = rows.mapNotNull { row ->
            val id = cell(row, Constants.PaseCols.ID) ?: return@mapNotNull null
            if (id in dirtyIds) return@mapNotNull null
            val nombre = cell(row, Constants.PaseCols.NOMBRE) ?: ""
            if (nombre.contains("Pase semana", ignoreCase = true)) return@mapNotNull null
            PaseEntity(
                id = id,
                nombre = nombre,
                numTT = cell(row, Constants.PaseCols.NUMTT) ?: "",
                ref1 = cell(row, Constants.PaseCols.REF1) ?: "",
                ref2 = cell(row, Constants.PaseCols.REF2) ?: "",
                imagen1 = cell(row, Constants.PaseCols.IMAGEN1),
                imagen2 = cell(row, Constants.PaseCols.IMAGEN2),
                ubicacion = cell(row, Constants.PaseCols.UBICACION),
                estado = cell(row, Constants.PaseCols.ESTADO) ?: ""
            )
        }
        if (items.isNotEmpty()) paseDao.insertAll(items)
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
                imageUrl4 = cell(row, Constants.SolicitudCols.IMAGEN4)
            )
        }
        if (items.isNotEmpty()) solicitudDao.insertAll(items)
    }

    private suspend fun refreshFiltroFecha() {
        val rows = fetchRows(Constants.SHEET_FILTRO)
        val items = rows.mapNotNull { row ->
            val id = cell(row, Constants.FiltroCols.ID) ?: return@mapNotNull null
            val fechaMillis = DateUtils.parseCellDateToEpochMillis(cell(row, Constants.FiltroCols.FECHA)) ?: return@mapNotNull null
            FiltroFechaEntity(
                id = id,
                nombre = cell(row, Constants.FiltroCols.NOMBRE) ?: "",
                estado = cell(row, Constants.FiltroCols.ESTADO) ?: "",
                observaciones = cell(row, Constants.FiltroCols.OBSERVACIONES),
                numTT = cell(row, Constants.FiltroCols.NUMTT) ?: "",
                fecha = fechaMillis,
                hora = cell(row, Constants.FiltroCols.HORA)
            )
        }
        filtroDao.deleteAll()
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