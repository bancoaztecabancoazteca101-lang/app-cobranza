package com.example.matrizapp

import androidx.room.Dao
import androidx.room.Entity
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
