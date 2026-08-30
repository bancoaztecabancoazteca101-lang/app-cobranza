package com.example.matrizapp

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Un registro por cada vez que a un cliente se le marcó/mandó SMS dentro de un bloque
 * (automático o de catchup). `fechaDia` es el día que se está ACREDITANDO hacia la meta de
 * contactos de esa semana de atraso — normalmente "hoy" (el bloque real que corrió), pero en
 * las corridas de catchup (8:15/9:15) se acredita hacia "ayer": así, si el catchup de las 8:15
 * ya cubrió el déficit completo, el de las 9:15 lo ve al recontar y no vuelve a marcar al mismo
 * cliente.
 */
@Entity(tableName = "contacto_log_table", indices = [Index(value = ["clienteId", "fechaDia"])])
data class ContactoLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clienteId: String,
    val fechaDia: Long,
    val bloqueIndex: Int, // índice real del bloque que lo contactó, o -1 si fue en catchup
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface ContactoLogDao {
    @Insert
    suspend fun insertar(log: ContactoLogEntity)

    @Query("SELECT COUNT(*) FROM contacto_log_table WHERE clienteId = :clienteId AND fechaDia = :fechaDia")
    suspend fun contarContactosEnDia(clienteId: String, fechaDia: Long): Int

    /** Borra logs de hace más de N días para que la tabla no crezca indefinidamente — el
     * catchup solo necesita mirar "ayer", no hace falta conservar historial completo. */
    @Query("DELETE FROM contacto_log_table WHERE fechaDia < :antesDe")
    suspend fun limpiarAnteriores(antesDe: Long)
}
