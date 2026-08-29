package com.example.matrizapp

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalTime

/**
 * Bloque de horario configurable (reemplaza los perfiles fijos de Tasker).
 * El orden real es por (hora, minuto) ascendente — no hay campo "orden" fijo,
 * así agregar/quitar/mover un bloque no requiere renumerar nada.
 */
@Entity(tableName = "bloques_horario_table")
data class BloqueHorarioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hora: Int,
    val minuto: Int,
    val activo: Boolean = true
) {
    fun toLocalTime(): LocalTime = LocalTime.of(hora, minuto)

    companion object {
        fun fromLocalTime(hora: LocalTime, activo: Boolean = true, id: Long = 0) =
            BloqueHorarioEntity(id = id, hora = hora.hour, minuto = hora.minute, activo = activo)
    }
}

@Dao
interface BloqueHorarioDao {

    @Query("SELECT * FROM bloques_horario_table ORDER BY hora ASC, minuto ASC")
    fun observarBloques(): Flow<List<BloqueHorarioEntity>>

    @Query("SELECT * FROM bloques_horario_table WHERE activo = 1 ORDER BY hora ASC, minuto ASC")
    suspend fun obtenerBloquesActivos(): List<BloqueHorarioEntity>

    @Insert
    suspend fun insertar(bloque: BloqueHorarioEntity): Long

    @Update
    suspend fun actualizar(bloque: BloqueHorarioEntity)

    @Delete
    suspend fun eliminar(bloque: BloqueHorarioEntity)

    @Query("UPDATE bloques_horario_table SET activo = :activo WHERE id = :id")
    suspend fun setActivo(id: Long, activo: Boolean)
}

/*
 * Integración con tu AppDatabase.kt real:
 *
 * 1. entities = [MatrizEntity::class, PaseEntity::class, SolicitudEntity::class,
 *      FiltroFechaEntity::class, FiltrarEntity::class, ControlEntity::class,
 *      BloqueHorarioEntity::class]   <- agregar
 * 2. version = 12   (subir de 11)
 * 3. Agregar a la lista de addMigrations():
 *
 *    private val MIGRATION_11_12 = object : Migration(11, 12) {
 *        override fun migrate(db: SupportSQLiteDatabase) {
 *            db.execSQL("""
 *                CREATE TABLE IF NOT EXISTS bloques_horario_table (
 *                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
 *                    hora INTEGER NOT NULL,
 *                    minuto INTEGER NOT NULL,
 *                    activo INTEGER NOT NULL DEFAULT 1
 *                )
 *            """.trimIndent())
 *        }
 *    }
 *
 * 4. abstract fun bloqueHorarioDao(): BloqueHorarioDao   <- agregar a la clase AppDatabase
 */
