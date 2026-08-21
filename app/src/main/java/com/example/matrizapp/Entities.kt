package com.example.matrizapp
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "matriz_table")
data class MatrizEntity(
    @PrimaryKey val id: String,
    val nombre: String, val semana: String, val requisito: String, val numTT: String,
    val ref1: String, val ref2: String, var observaciones: String?, var estado: String,
    val ubicacion: String?, var imagenUrl: String?, var imagenUrl2: String?, val fecha: Long?, val hora: String?,
    val ruta: String?, val folioP: String?,
    val isDirty: Boolean = false, val lastSync: Long = System.currentTimeMillis()
)

@Entity(tableName = "pase_cartera_table")
data class PaseEntity(
    @PrimaryKey val id: String,
    val nombre: String, val numTT: String, val ref1: String, val ref2: String,
    val imagen1: String?, val imagen2: String?, val ubicacion: String?, var estado: String,
    val isDirty: Boolean = false, val lastSync: Long = System.currentTimeMillis()
)

@Entity(tableName = "solicitud_table")
data class SolicitudEntity(
    @PrimaryKey val id: String,
    val nombre: String, val numero: String?, val sucursal: String?,
    val ubicacionRaw: String?, val imageUrl: String?, val imageUrl2: String?,
    val nombreRef1: String?, val ref1: String?, val nombreRef2: String?, val ref2: String?,
    var observaciones: String?, var audioUrl: String?, var estado: String,
    val imageUrl3: String? = null, val imageUrl4: String? = null,
    // Fijos al crear el registro: gestorAsignado siempre "Flores" (no editable),
    // fechaHora se captura automático con la hora del dispositivo al guardar.
    val gestorAsignado: String = "Flores", val fechaHora: Long? = null,
    val isDirty: Boolean = false, val lastSync: Long = System.currentTimeMillis()
)

@Entity(tableName = "filtro_fecha_table")
data class FiltroFechaEntity(
    @PrimaryKey val id: String,
    val nombre: String, var estado: String, val observaciones: String?,
    val numTT: String, val fecha: Long, val hora: String?,
    val imagenUrl: String? = null,
    val ref1: String? = null, val ref2: String? = null, val ubicacion: String? = null,
    val req: String? = null,
    val isDirty: Boolean = false, val lastSync: Long = System.currentTimeMillis()
)

@Entity(tableName = "filtrar_table")
data class FiltrarEntity(
    @PrimaryKey val id: String,
    val nombre: String, val semana: String, val requerido: String, val numTT: String,
    /** Bloque de texto pre-formateado con los 7 pares Nombre/Referencia, uno por linea. */
    val referencias: String?,
    var observaciones: String?, var estado: String, val ubicacion: String?,
    val imagen: String?, val fecha: Long?, val hora: String?,
    val isDirty: Boolean = false, val lastSync: Long = System.currentTimeMillis()
)

@Entity(tableName = "control_table")
data class ControlEntity(
    @PrimaryKey val semana: String,
    val requerido: String,
    val lastSync: Long = System.currentTimeMillis()
)
