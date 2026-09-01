package com.example.matrizapp

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Configuración exclusiva del flujo automático (Bloques de horario) — independiente de la
 * que usan las pantallas manuales de Llamadas/SMS (CallViewModel), para que ajustar una no
 * afecte a la otra. Fila única (id fijo = 1, tipo singleton), igual de simple que
 * AutomatizacionPrefs pero en Room porque aquí sí conviene observarla como Flow desde la UI.
 */
@Entity(tableName = "config_automatizacion_table")
data class ConfiguracionAutomatizacionEntity(
    @PrimaryKey val id: Int = 1,
    val simSeleccionada: Int? = null, // null = línea default del sistema
    val ocultarNumero: Boolean = false,
    val segundosPausaEntreLlamadas: Int = 5,
    val duracionMaximaLlamada: Int = 45 // segundos
)

/**
 * Frecuencia de contacto editable por semana de atraso — reemplaza el mapa fijo
 * ReglaRepeticion.BLOQUES_POR_SEM. `offsets` guarda una lista separada por comas de
 * posiciones relativas al bloque de alta del cliente (offset 0 = su bloque de alta,
 * mismo número que el "número de guía - 1" que ve Diego en la pantalla de Bloques de
 * horario, ya que ese número de guía es 1-based y estos offsets son 0-based).
 * Fila por semana (1 a 5), sembrada con los valores de BLOQUES_POR_SEM la primera vez.
 */
@Entity(tableName = "regla_semana_table")
data class ReglaSemanaEntity(
    @PrimaryKey val semana: Int,
    val offsets: String // ej. "0,2,4,6,8"
) {
    fun offsetsList(): List<Int> = offsets.split(",").mapNotNull { it.trim().toIntOrNull() }
}

@Dao
interface ReglaSemanaDao {
    @Query("SELECT * FROM regla_semana_table ORDER BY semana ASC")
    fun observarTodas(): Flow<List<ReglaSemanaEntity>>

    @Query("SELECT * FROM regla_semana_table ORDER BY semana ASC")
    suspend fun obtenerTodas(): List<ReglaSemanaEntity>

    @Update
    suspend fun actualizar(regla: ReglaSemanaEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertarSiNoExiste(reglas: List<ReglaSemanaEntity>)

    /** Siembra las 5 filas con los valores de BLOQUES_POR_SEM si la tabla está vacía --
     * así una instalación nueva o recién migrada arranca con el comportamiento actual. */
    suspend fun sembrarSiVacia() {
        if (obtenerTodas().isEmpty()) {
            insertarSiNoExiste(ReglaRepeticion.BLOQUES_POR_SEM.map { (sem, offsets) ->
                ReglaSemanaEntity(sem, offsets.joinToString(","))
            })
        }
    }

    suspend fun obtenerMapaOSembrar(): Map<Int, List<Int>> {
        sembrarSiVacia()
        val filas = obtenerTodas()
        return if (filas.isEmpty()) ReglaRepeticion.BLOQUES_POR_SEM
        else filas.associate { it.semana to it.offsetsList() }
    }
}

@Dao
interface ConfiguracionAutomatizacionDao {

    @Query("SELECT * FROM config_automatizacion_table WHERE id = 1")
    fun observar(): Flow<ConfiguracionAutomatizacionEntity?>

    @Query("SELECT * FROM config_automatizacion_table WHERE id = 1")
    suspend fun obtener(): ConfiguracionAutomatizacionEntity?

    @Update
    suspend fun actualizar(config: ConfiguracionAutomatizacionEntity)

    @Query("INSERT OR IGNORE INTO config_automatizacion_table (id, simSeleccionada, ocultarNumero, segundosPausaEntreLlamadas, duracionMaximaLlamada) VALUES (1, NULL, 0, 5, 45)")
    suspend fun sembrarSiVacia()

    /** Devuelve la fila (sembrando el default si aún no existe) — así el worker en background
     * siempre tiene una config válida, sin depender de que la pantalla se haya abierto antes. */
    suspend fun obtenerOSembrar(): ConfiguracionAutomatizacionEntity {
        sembrarSiVacia()
        return obtener() ?: ConfiguracionAutomatizacionEntity()
    }
}
