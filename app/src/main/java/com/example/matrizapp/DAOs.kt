package com.example.matrizapp
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TicketPagoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertar(ticket: TicketPagoEntity)

    @Query("SELECT COALESCE(SUM(monto), 0.0) FROM ticket_pago_table WHERE fecha >= :desde AND fecha <= :hasta")
    fun totalEnRango(desde: Long, hasta: Long): Flow<Double>
}

@Dao
interface VisitaMapaDao {
    // fin EXCLUSIVO (inicio del lunes siguiente): con BETWEEN las visitas de ese lunes entrarían en la semana anterior.
    @Query("SELECT * FROM visita_mapa_table WHERE fechaDia >= :inicio AND fechaDia < :fin ORDER BY fechaDia ASC, timestamp ASC")
    fun getVisitasSemana(inicio: Long, fin: Long): Flow<List<VisitaMapaEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun registrar(visita: VisitaMapaEntity)

    // Cambiar a mano el cartucho de UN punto (si el punto solo existía "en vivo", se guarda con ese cartucho).
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun reemplazar(visita: VisitaMapaEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun registrarVarias(visitas: List<VisitaMapaEntity>)

    @Query("SELECT cartuchoActivo FROM cartucho_dia_table WHERE fechaDia = :fechaDia LIMIT 1")
    suspend fun getCartuchoActivo(fechaDia: Long): Int?

    @Query("INSERT OR IGNORE INTO cartucho_dia_table (fechaDia, cartuchoActivo) VALUES (:fechaDia, 1)")
    suspend fun asegurarCartuchoDia(fechaDia: Long)

    @Query("UPDATE cartucho_dia_table SET cartuchoActivo = :cartucho WHERE fechaDia = :fechaDia")
    suspend fun setCartuchoActivo(fechaDia: Long, cartucho: Int)

    @Query("SELECT * FROM cartucho_dia_table WHERE fechaDia >= :inicio AND fechaDia < :fin ORDER BY fechaDia ASC")
    fun observarCartuchosSemana(inicio: Long, fin: Long): Flow<List<CartuchoDiaEntity>>

    @Query("DELETE FROM visita_mapa_table WHERE fechaDia < :limite")
    suspend fun borrarAnteriores(limite: Long)
}

/** 00:00 del día de [millis] en hora local. Con Calendar (no java.time): la app soporta Android 7 (minSdk 24). */
fun inicioDelDia(millis: Long = System.currentTimeMillis()): Long =
    java.util.Calendar.getInstance().apply {
        timeInMillis = millis
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

/** Registra la visita de HOY. Nunca debe romper el guardado que la dispara: cualquier fallo se ignora. */
suspend fun VisitaMapaDao.registrarVisitaHoy(clave: String, nombre: String, ubicacion: String?, matrizId: String?) {
    try {
        val partes = ubicacion?.split(",")?.map { it.trim() } ?: return
        if (partes.size != 2 || partes[0].toDoubleOrNull() == null || partes[1].toDoubleOrNull() == null) return
        val hoy = inicioDelDia()
        asegurarCartuchoDia(hoy)
        val cartucho = getCartuchoActivo(hoy) ?: 1
        registrar(VisitaMapaEntity(clave, hoy, nombre, partes.joinToString(","), matrizId, cartucho))
        borrarAnteriores(hoy - 120L * 24 * 60 * 60 * 1000) // se conservan ~4 meses
    } catch (_: Exception) { }
}

/** Guarda en el historial del Mapa TODOS los registros de hoy (los mismos que muestra Filtro Fecha:
 * fecha = hoy y status distinto de PASE). Así, si después cambian su fecha, el punto de hoy se conserva.
 * IGNORE: lo ya guardado no se duplica. */
suspend fun VisitaMapaDao.registrarRegistrosDeHoy(items: List<MatrizEntity>) {
    try {
        val hoy = inicioDelDia()
        val finHoy = hoy + 24L * 60 * 60 * 1000
        asegurarCartuchoDia(hoy)
        val cartucho = getCartuchoActivo(hoy) ?: 1
        val nuevos = items.mapNotNull { m ->
            val f = m.fecha ?: return@mapNotNull null
            if (f < hoy || f >= finHoy || m.estado.equals("PASE", ignoreCase = true)) return@mapNotNull null
            val p = m.ubicacion?.split(",")?.map { it.trim() } ?: return@mapNotNull null
            if (p.size != 2 || p[0].toDoubleOrNull() == null || p[1].toDoubleOrNull() == null) return@mapNotNull null
            VisitaMapaEntity("M:${m.id}", hoy, m.nombre, p.joinToString(","), m.id, cartucho)
        }
        if (nuevos.isNotEmpty()) registrarVarias(nuevos)
    } catch (_: Exception) { }
}

@Dao
interface MatrizDao {
    @Query("SELECT * FROM matriz_table WHERE nombre NOT LIKE '%Pase semana%' AND nombre != '' ORDER BY id ASC")
    fun getAllMatriz(): Flow<List<MatrizEntity>>
    @Query("SELECT * FROM matriz_table WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MatrizEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MatrizEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOne(item: MatrizEntity)
    @Query("UPDATE matriz_table SET estado = :nuevoEstado, observaciones = :obs, isDirty = 1 WHERE id = :id")
    suspend fun updateGestionLocal(id: String, nuevoEstado: String, obs: String)
    @Query("UPDATE matriz_table SET estado = :nuevoEstado, hora = :nuevaHora, isDirty = 1 WHERE id = :id")
    suspend fun updateEstadoYHora(id: String, nuevoEstado: String, nuevaHora: String)
    @Query("UPDATE matriz_table SET estado = 'PASE', isDirty = 1 WHERE id = :id")
    suspend fun marcarComoPase(id: String)
    // Marca "Pagado" + guarda el monto leído del ticket de cobranza (foto) -- ver
    // FiltroFechaViewModel.registrarPagoDesdeTicket. montoCobrado es 100% local (no se sube
    // al Sheet, ver SyncWorker.syncMatriz), isDirty=1 solo sube estado/hora como cualquier
    // otro cambio de status.
    @Query("UPDATE matriz_table SET estado = 'Pagado', montoCobrado = :monto, hora = :hora, isDirty = 1 WHERE id = :id")
    suspend fun marcarPagadoConMonto(id: String, monto: Double, hora: String)
    // Consulta puntual (no Flow) usada para el match por nombre del ticket de cobranza contra
    // los registros del día -- ver FiltroFechaViewModel.registrarPagoDesdeTicket.
    @Query("SELECT * FROM matriz_table WHERE fecha BETWEEN :desde AND :hasta")
    suspend fun getMatrizEnRango(desde: Long, hasta: Long): List<MatrizEntity>

    // Mapa: solo los registros de la semana (fin EXCLUSIVO) que tienen coordenadas y no son PASE (igual que Filtro Fecha).
    @Query("SELECT * FROM matriz_table WHERE fecha >= :desde AND fecha < :hasta AND ubicacion IS NOT NULL AND UPPER(estado) != 'PASE'")
    fun observarMatrizEnRango(desde: Long, hasta: Long): Flow<List<MatrizEntity>>
    @Query("""UPDATE matriz_table SET nombre = :nombre, semana = :semana, requisito = :requisito,
        numTT = :numTT, ref1 = :ref1, ref2 = :ref2, observaciones = :observaciones, estado = :estado,
        ubicacion = :ubicacion, fecha = :fecha, hora = :hora, ruta = :ruta, folioP = :folioP,
        descuentoPago = :descuentoPago, descuentoAhorro = :descuentoAhorro,
        ref3 = :ref3, ref4 = :ref4, diaPago = :diaPago, domicilioLaboral = :domicilioLaboral,
        diasAtraso = :diasAtraso, diasApertura = :diasApertura, isDirty = 1
        WHERE id = :id""")
    suspend fun updateRegistroCompleto(
        id: String, nombre: String, semana: String, requisito: String, numTT: String,
        ref1: String, ref2: String, observaciones: String?, estado: String, ubicacion: String?,
        fecha: Long?, hora: String?, ruta: String?, folioP: String?,
        descuentoPago: String?, descuentoAhorro: String?,
        ref3: String?, ref4: String?, diaPago: String?, domicilioLaboral: String?,
        diasAtraso: String?, diasApertura: String?
    )
    @Query("""UPDATE matriz_table SET nombre = :nombre, semana = :semana, requisito = :requisito,
        numTT = :numTT, ref1 = :ref1, ref2 = :ref2, observaciones = :observaciones, estado = :estado,
        ubicacion = :ubicacion, imagenUrl = :imagenUrl, imagenUrl2 = :imagenUrl2, fecha = :fecha,
        hora = :hora, ruta = :ruta, folioP = :folioP
        WHERE id = :id""")
    // UPDATE parcial usado por el pull de Sheets (SheetsRepository.refreshMatriz) para registros
    // que ya existen en Room -- a propósito NO incluye descuentoPago/descuentoAhorro/ref3/ref4/diaPago/domicilioLaboral/isDirty en
    // el SET. El Sheet no tiene esas 2 columnas (son 100% locales), así que antes había que leer
    // el valor local al arrancar el pull y "reinyectarlo" al reconstruir el MatrizEntity completo
    // para no perderlo -- pero si un guardado local (Editar registro) caía justo en la ventana
    // entre esa lectura y el insertAll() final, el pull ganaba la carrera y lo pisaba con el
    // valor viejo. Con UPDATE parcial la columna simplemente no aparece en el SQL, así que no
    // hay ninguna ventana en la que un guardado concurrente pueda perderse.
    suspend fun actualizarDesdeSheet(
        id: String, nombre: String, semana: String, requisito: String, numTT: String,
        ref1: String, ref2: String, observaciones: String?, estado: String, ubicacion: String?,
        imagenUrl: String?, imagenUrl2: String?, fecha: Long?, hora: String?, ruta: String?, folioP: String?
    )
    @Query("UPDATE matriz_table SET id = :idNuevo WHERE id = :idAnterior")
    suspend fun renameId(idAnterior: String, idNuevo: String)
    @Query("UPDATE matriz_table SET folioP = :folioP, isDirty = 1 WHERE id = :id")
    suspend fun updateFolioP(id: String, folioP: String)
    @Query("SELECT * FROM matriz_table WHERE (folioP IS NULL OR folioP = '') AND (imagenUrl IS NOT NULL AND imagenUrl != '' OR imagenUrl2 IS NOT NULL AND imagenUrl2 != '')")
    suspend fun getSinCuConFoto(): List<MatrizEntity>
    @Query("UPDATE matriz_table SET descuentoPago = NULL, descuentoAhorro = NULL WHERE (descuentoPago IS NOT NULL AND descuentoPago != '') OR (descuentoAhorro IS NOT NULL AND descuentoAhorro != '')")
    suspend fun limpiarDescuentosVencidos(): Int
    @Query("UPDATE matriz_table SET imagenUrl = :uri, isDirty = 1 WHERE id = :id")
    suspend fun updateImagenLocal(id: String, uri: String)
    @Query("UPDATE matriz_table SET imagenUrl2 = :uri, isDirty = 1 WHERE id = :id")
    suspend fun updateImagen2Local(id: String, uri: String)
    @Query("SELECT * FROM matriz_table WHERE isDirty = 1")
    suspend fun getDirtyItems(): List<MatrizEntity>
    @Query("UPDATE matriz_table SET isDirty = 0, imagenUrl = :remoteImg, imagenUrl2 = :remoteImg2, lastSync = :syncTime WHERE id = :id")
    suspend fun markAsClean(id: String, remoteImg: String?, remoteImg2: String?, syncTime: Long = System.currentTimeMillis())
    @Query("DELETE FROM matriz_table WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface PaseCarteraDao {
    @Query("SELECT * FROM pase_cartera_table ORDER BY id ASC")
    fun getAllPase(): Flow<List<PaseEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PaseEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertar(item: PaseEntity)
    @Update
    suspend fun actualizar(item: PaseEntity)
    @Query("DELETE FROM pase_cartera_table WHERE id = :id")
    suspend fun eliminar(id: String)
    @Query("SELECT origenMatrizId FROM pase_cartera_table")
    suspend fun getOrigenesYaCopiados(): List<String>
    @Query("UPDATE pase_cartera_table SET estado = :nuevoEstado, isDirty = 1 WHERE id = :id")
    suspend fun updateEstadoLocal(id: String, nuevoEstado: String)
    @Query("SELECT * FROM pase_cartera_table WHERE origenMatrizId = :origenMatrizId LIMIT 1")
    suspend fun getByOrigenMatrizId(origenMatrizId: String): PaseEntity?
    @Query("UPDATE pase_cartera_table SET contiene = :contiene, capitales = :capitales, isDirty = 1 WHERE id = :id")
    suspend fun updateCamposGcr(id: String, contiene: String?, capitales: String?)
    @Query("SELECT * FROM pase_cartera_table WHERE isDirty = 1")
    suspend fun getDirtyItems(): List<PaseEntity>
    @Query("UPDATE pase_cartera_table SET isDirty = 0, lastSync = :syncTime WHERE id = :id")
    suspend fun markAsClean(id: String, syncTime: Long = System.currentTimeMillis())
}

@Dao
interface SolicitudDao {
    @Query("SELECT * FROM solicitud_table ORDER BY id ASC") fun getAllSolicitud(): Flow<List<SolicitudEntity>>
    @Query("UPDATE solicitud_table SET estado = :nuevoEstado, isDirty = 1 WHERE id = :id") suspend fun updateEstadoLocal(id: String, nuevoEstado: String)
    @Query("UPDATE solicitud_table SET audioUrl = :uri, isDirty = 1 WHERE id = :id") suspend fun updateAudioLocal(id: String, uri: String)
    @Query("UPDATE solicitud_table SET imageUrl = :uri, isDirty = 1 WHERE id = :id") suspend fun updateImagenLocal(id: String, uri: String)
    @Query("UPDATE solicitud_table SET imageUrl2 = :uri, isDirty = 1 WHERE id = :id") suspend fun updateImagen2Local(id: String, uri: String)
    @Query("UPDATE solicitud_table SET imageUrl3 = :uri, isDirty = 1 WHERE id = :id") suspend fun updateImagen3Local(id: String, uri: String)
    @Query("UPDATE solicitud_table SET imageUrl4 = :uri, isDirty = 1 WHERE id = :id") suspend fun updateImagen4Local(id: String, uri: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertOne(item: SolicitudEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<SolicitudEntity>)
    @Query("""UPDATE solicitud_table SET nombre = :nombre, numero = :numero, sucursal = :sucursal,
        ubicacionRaw = :ubicacion, nombreRef1 = :nombreRef1, ref1 = :ref1, nombreRef2 = :nombreRef2, ref2 = :ref2,
        observaciones = :observaciones, estado = :estado,
        fechaHora = COALESCE(:fechaHoraOverride, fechaHora, :fechaHoraSiFalta), isDirty = 1 WHERE id = :id""")
    suspend fun updateCompleto(id: String, nombre: String, numero: String, sucursal: String, ubicacion: String, nombreRef1: String, ref1: String, nombreRef2: String, ref2: String, observaciones: String, estado: String, fechaHoraOverride: Long? = null, fechaHoraSiFalta: Long = System.currentTimeMillis())
    @Query("UPDATE solicitud_table SET fechaHora = :fechaHora, isDirty = 1 WHERE id = :id AND fechaHora IS NULL") suspend fun backfillFechaHoraSiFalta(id: String, fechaHora: Long)
    @Query("SELECT * FROM solicitud_table WHERE isDirty = 1") suspend fun getDirtyItems(): List<SolicitudEntity>
    @Query("UPDATE solicitud_table SET isDirty = 0, audioUrl = :remoteAudio, imageUrl = :remoteImg, imageUrl2 = :remoteImg2, imageUrl3 = :remoteImg3, imageUrl4 = :remoteImg4, lastSync = :syncTime WHERE id = :id") suspend fun markAsClean(id: String, remoteAudio: String?, remoteImg: String? = null, remoteImg2: String? = null, remoteImg3: String? = null, remoteImg4: String? = null, syncTime: Long = System.currentTimeMillis())
    @Query("DELETE FROM solicitud_table WHERE id = :id") suspend fun deleteById(id: String)
}

@Dao
interface FiltroFechaDao {
    @Query("SELECT * FROM filtro_fecha_table ORDER BY fecha DESC") fun getAll(): Flow<List<FiltroFechaEntity>>
    @Query("SELECT * FROM filtro_fecha_table WHERE fecha BETWEEN :desde AND :hasta ORDER BY fecha DESC") fun getItemsByRange(desde: Long, hasta: Long): Flow<List<FiltroFechaEntity>>
    @Query("SELECT * FROM filtro_fecha_table WHERE id = :id LIMIT 1") suspend fun getById(id: String): FiltroFechaEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<FiltroFechaEntity>)
    @Query("DELETE FROM filtro_fecha_table") suspend fun deleteAll()
    @Query("DELETE FROM filtro_fecha_table WHERE isDirty = 0") suspend fun deleteAllClean()
    @Query("UPDATE filtro_fecha_table SET estado = :nuevoEstado, isDirty = 1 WHERE id = :id") suspend fun updateEstadoLocal(id: String, nuevoEstado: String)
    @Query("UPDATE filtro_fecha_table SET estado = :nuevoEstado, hora = :nuevaHora, isDirty = 1 WHERE id = :id") suspend fun updateEstadoYHoraLocal(id: String, nuevoEstado: String, nuevaHora: String)
    @Query("SELECT * FROM filtro_fecha_table WHERE isDirty = 1") suspend fun getDirtyItems(): List<FiltroFechaEntity>
    @Query("UPDATE filtro_fecha_table SET isDirty = 0, lastSync = :syncTime WHERE id = :id") suspend fun markAsClean(id: String, syncTime: Long = System.currentTimeMillis())
}

@Dao
interface FiltrarDao {
    @Query("SELECT * FROM filtrar_table ORDER BY nombre ASC") fun getAll(): Flow<List<FiltrarEntity>>
    @Query("UPDATE filtrar_table SET estado = :nuevoEstado, observaciones = :obs, isDirty = 1 WHERE id = :id") suspend fun updateGestionLocal(id: String, nuevoEstado: String, obs: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<FiltrarEntity>)
    @Query("SELECT * FROM filtrar_table WHERE isDirty = 1") suspend fun getDirtyItems(): List<FiltrarEntity>
    @Query("UPDATE filtrar_table SET isDirty = 0, lastSync = :syncTime WHERE id = :id") suspend fun markAsClean(id: String, syncTime: Long = System.currentTimeMillis())
}

@Dao
interface ControlDao {
    @Query("SELECT * FROM control_table ORDER BY rowid ASC") fun getAll(): Flow<List<ControlEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<ControlEntity>)
    @Query("DELETE FROM control_table") suspend fun deleteAll()
}

@Dao
interface RutaIADao {
    @Query("SELECT * FROM ruta_ia_table ORDER BY orden ASC")
    fun getAll(): Flow<List<RutaIAEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RutaIAEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOne(item: RutaIAEntity)
    @Query("DELETE FROM ruta_ia_table")
    suspend fun deleteAll()
    @Query("UPDATE ruta_ia_table SET estado = :nuevoEstado, isDirty = 1 WHERE id = :id")
    suspend fun updateEstadoLocal(id: String, nuevoEstado: String)
    @Query("UPDATE ruta_ia_table SET orden = :nuevoOrden, isDirty = 1 WHERE id = :id")
    suspend fun updateOrden(id: String, nuevoOrden: Int)
    @Query("UPDATE ruta_ia_table SET esNuevo = 0, cuMatrizMatch = :matrizId, isDirty = 1 WHERE id = :id")
    suspend fun marcarAltaEnMatriz(id: String, matrizId: String)
    @Query("SELECT * FROM ruta_ia_table WHERE isDirty = 1")
    suspend fun getDirtyItems(): List<RutaIAEntity>
    @Query("DELETE FROM ruta_ia_table WHERE id NOT IN (:ids)")
    suspend fun deleteNotIn(ids: List<String>)
    @Query("UPDATE ruta_ia_table SET isDirty = 0, lastSync = :syncTime WHERE id = :id")
    suspend fun markAsClean(id: String, syncTime: Long = System.currentTimeMillis())
}

@Dao
interface RutaIAFiltroDao {
    @Query("SELECT * FROM ruta_ia_filtro_table WHERE id = 1") suspend fun get(): RutaIAFiltroEntity?
    @Query("SELECT * FROM ruta_ia_filtro_table WHERE id = 1") fun getFlow(): Flow<RutaIAFiltroEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun guardar(item: RutaIAFiltroEntity)
}

@Dao
interface CanalPagoDao {
    @Query("SELECT * FROM canal_pago_table")
    suspend fun getAll(): List<CanalPagoEntity>
    @Query("SELECT COUNT(*) FROM canal_pago_table")
    suspend fun count(): Int
    @Query("SELECT MIN(lastSync) FROM canal_pago_table")
    suspend fun oldestSync(): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CanalPagoEntity>)
    @Query("DELETE FROM canal_pago_table")
    suspend fun deleteAll()
}

@Dao
interface VelocidadDao {
    @Query("SELECT * FROM velocidad_table WHERE id = 1 LIMIT 1")
    fun getFlow(): Flow<VelocidadEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardar(item: VelocidadEntity)
}

@Dao
interface ComisionDao {
    @Query("SELECT * FROM comision_table WHERE id = 1 LIMIT 1")
    fun getFlow(): Flow<ComisionEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardar(item: ComisionEntity)
}

@Dao
interface BolsaGerenciaDao {
    @Query("SELECT * FROM bolsa_gerencia_table WHERE id = 1 LIMIT 1")
    fun getFlow(): Flow<BolsaGerenciaEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardar(item: BolsaGerenciaEntity)
}
