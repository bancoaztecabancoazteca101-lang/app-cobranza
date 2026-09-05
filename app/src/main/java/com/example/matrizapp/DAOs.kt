package com.example.matrizapp
import androidx.room.*
import kotlinx.coroutines.flow.Flow

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
    @Query("""UPDATE matriz_table SET nombre = :nombre, semana = :semana, requisito = :requisito,
        numTT = :numTT, ref1 = :ref1, ref2 = :ref2, observaciones = :observaciones, estado = :estado,
        ubicacion = :ubicacion, fecha = :fecha, hora = :hora, ruta = :ruta, folioP = :folioP, isDirty = 1
        WHERE id = :id""")
    suspend fun updateRegistroCompleto(
        id: String, nombre: String, semana: String, requisito: String, numTT: String,
        ref1: String, ref2: String, observaciones: String?, estado: String, ubicacion: String?,
        fecha: Long?, hora: String?, ruta: String?, folioP: String?
    )
    @Query("UPDATE matriz_table SET id = :idNuevo WHERE id = :idAnterior")
    suspend fun renameId(idAnterior: String, idNuevo: String)
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
    @Query("SELECT * FROM ruta_ia_table ORDER BY orden ASC") fun getAll(): Flow<List<RutaIAEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<RutaIAEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertOne(item: RutaIAEntity)
    @Query("DELETE FROM ruta_ia_table") suspend fun deleteAll()
    @Query("UPDATE ruta_ia_table SET estado = :nuevoEstado, isDirty = 1 WHERE id = :id") suspend fun updateEstadoLocal(id: String, nuevoEstado: String)
    @Query("SELECT * FROM ruta_ia_table WHERE isDirty = 1") suspend fun getDirtyItems(): List<RutaIAEntity>
    @Query("UPDATE ruta_ia_table SET isDirty = 0, lastSync = :syncTime WHERE id = :id") suspend fun markAsClean(id: String, syncTime: Long = System.currentTimeMillis())
}

@Dao
interface RutaIAFiltroDao {
    @Query("SELECT * FROM ruta_ia_filtro_table WHERE id = 1") suspend fun get(): RutaIAFiltroEntity?
    @Query("SELECT * FROM ruta_ia_filtro_table WHERE id = 1") fun getFlow(): Flow<RutaIAFiltroEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun guardar(item: RutaIAFiltroEntity)
}
