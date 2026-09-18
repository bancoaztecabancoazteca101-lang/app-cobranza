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

/** Copia independiente de un registro de Matriz cuando su status pasa a "PASE" -- MISMOS
 * campos que MatrizEntity para tener paridad total de UI/funciones, pero es una tabla
 * completamente aparte: una vez copiado, editar aquí NUNCA toca matriz_table y viceversa.
 * `origenMatrizId` guarda de qué registro de Matriz vino, solo para no duplicar la copia (no
 * se usa para sincronizar cambios de vuelta). */
@Entity(tableName = "pase_cartera_table")
data class PaseEntity(
    @PrimaryKey val id: String,
    val nombre: String, val semana: String, val requisito: String, val numTT: String,
    val ref1: String, val ref2: String, var observaciones: String?, var estado: String,
    val ubicacion: String?, var imagenUrl: String?, var imagenUrl2: String?, val fecha: Long?, val hora: String?,
    val ruta: String?, val folioP: String?, val origenMatrizId: String,
    val contiene: String? = null, val capitales: String? = null,
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

/** Catálogo local de sucursales/lugares de pago (Elektra, Banco Azteca, OXXO, etc.) para la
 * zona de trabajo -- se llena por sync desde la hoja "Catálogo Canales Pago" (poblada por un
 * script de Apps Script que sí puede hablarle a Overpass sin el problema de red del teléfono
 * en campo, ver AppsScript/SincronizarCanalesPago.gs). Guarda los datos crudos (nombre/empresa
 * como posible brand/operator, dirección ya armada como texto) -- la clasificación
 * (Elektra/OXXO/etc, Principal/Afiliado) se hace en PaymentChannels.kt con classifyChannel(),
 * igual que antes con los datos que venían directo de Overpass, para no duplicar esa lógica. */
@Entity(tableName = "canal_pago_table")
data class CanalPagoEntity(
    @PrimaryKey val id: String,
    val nombre: String,
    val empresa: String?,
    val direccion: String?,
    val lat: Double,
    val lng: Double,
    val lastSync: Long = System.currentTimeMillis()
)

/** Datos que Diego captura A MANO cada semana para su reporte "Penalización" (Tabla
 * Velocidades) -- 100% local, no viene de ningún Google Sheet ni depende de su cuenta de
 * Google. Fila única (id fijo = 1): no hay una fila por gestor, solo la suya. Guarda los
 * valores crudos que él teclea; las fórmulas (%3-6, $ Arriba, Reque a favor, Pérdida, Me
 * faltan $, Meta diaria, Déficit o exceden) se calculan en Kotlin -- ver
 * VelocidadEntity.calcular() en PenalizacionViewModel.kt para el mapeo exacto, confirmado
 * por Diego con capturas de pantalla de las fórmulas reales de Sheets (sesión 16/09/2026). */
@Entity(tableName = "velocidad_table")
data class VelocidadEntity(
    @PrimaryKey val id: Int = 1,
    val rk: String = "",
    val plan100: Double = 0.0,
    val total: Double = 0.0,
    val reque36: Double = 0.0, val monto36: Double = 0.0,
    val cuPase: Int = 0, val capital: Double = 0.0,
    val diasOp: Int = 6,
    val lastUpdate: Long = System.currentTimeMillis()
)

/** Datos que Diego captura A MANO para el reporte "Comisión" (submenú de Control) -- 100%
 * local, sin fórmula de negocio: por cada segmento de semana de atraso (1-2, 3, 4-6, 7-9)
 * teclea lo cobrado "al momento" (directo) y lo cobrado "indirecta", más la meta del periodo.
 * Fila única (id fijo = 1), mismo patrón que VelocidadEntity/Penalización. Ver
 * ComisionEntity.calcular() en ComisionViewModel.kt para el Total por fila y el % de avance. */
@Entity(tableName = "comision_table")
data class ComisionEntity(
    @PrimaryKey val id: Int = 1,
    val momento12: Double = 0.0, val indirecta12: Double = 0.0,
    val momento3: Double = 0.0, val indirecta3: Double = 0.0,
    val momento46: Double = 0.0, val indirecta46: Double = 0.0,
    val momento79: Double = 0.0, val indirecta79: Double = 0.0,
    val meta: Double = 0.0,
    val lastUpdate: Long = System.currentTimeMillis()
)

/** Datos que Diego captura A MANO para el reporte "Bolsa Gerencia" (4to submenú de Control,
 * 18/09/2026) -- 100% local, nada se extrae de Sheets ni de ningún lado. Fila única (id fijo =
 * 1), mismo patrón que ComisionEntity/VelocidadEntity. Ver BolsaGerenciaEntity.calcular() en
 * BolsaGerenciaViewModel.kt para las fórmulas (Bolsa de la Gerencia, Bolsa a Repartir, %
 * Cumplimiento 1 a 9, Comisión) y la condición de liberación (85% mínimo de cumplimiento del
 * plan, si no la comisión es $0). */
@Entity(tableName = "bolsa_gerencia_table")
data class BolsaGerenciaEntity(
    @PrimaryKey val id: Int = 1,
    val cobranzaGerencia: Double = 0.0,
    val cumplimientoPlan: Double = 0.0,
    val montoBase: Double = 0.0,
    val numeroGestores: Int = 0,
    val cobranza1a9Gestor: Double = 0.0,
    val cobranza1a9Gerencia: Double = 0.0,
    val lastUpdate: Long = System.currentTimeMillis()
)

