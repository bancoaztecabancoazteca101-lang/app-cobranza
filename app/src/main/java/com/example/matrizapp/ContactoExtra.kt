package com.example.matrizapp

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Un número de contacto extra confirmado a mano desde la pantalla Filtrar: Ref1/Ref2 de un
 * "cercano" (ej. José, vecino a <=10m) que Diego decidió sumar al caso del cliente TITULAR
 * (ej. Maricruz), porque ese cercano probablemente conoce/puede avisarle al titular.
 *
 * Estos números entran al mismo ciclo automático de Bloques que los Ref1/Ref2 propios del
 * cliente: mismos mensajes/rotación de plantillas (MensajesCobranza.paraReferencia), siempre
 * mencionando el %nombre% del TITULAR (no el del cercano) -- el mensaje/llamada nunca cambia,
 * solo se le agrega un número más de destino a la ronda de SMS de referencia.
 *
 * `nombreOrigen` es solo para mostrar en la UI de dónde salió el número (ej. "José Anastacio
 * Gutierrez"), no se usa en el mensaje.
 */
@Entity(tableName = "contacto_extra_table", indices = [Index(value = ["clienteId"])])
data class ContactoExtraEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clienteId: String,
    val telefono: String,
    val nombreOrigen: String,
    val fechaAgregado: Long = System.currentTimeMillis()
)

@Dao
interface ContactoExtraDao {
    @Insert
    suspend fun insertar(contacto: ContactoExtraEntity)

    /** Todos los contactos extra de todos los clientes -- se combina con el flujo de Matriz en
     * FiltrarViewModel para saber, por cada cercano mostrado, si ya se agregó o no. */
    @Query("SELECT * FROM contacto_extra_table")
    fun observarTodos(): Flow<List<ContactoExtraEntity>>

    /** Los números extra ya confirmados para un cliente -- los que lee el flujo automático de
     * Bloques (LlamadaAutomaticaWorker/CatchupLlamadaWorker) para sumarlos a la ronda de SMS
     * de referencia de ese cliente. */
    @Query("SELECT * FROM contacto_extra_table WHERE clienteId = :clienteId")
    suspend fun obtenerPara(clienteId: String): List<ContactoExtraEntity>
}
