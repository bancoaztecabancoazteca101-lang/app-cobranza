package com.example.matrizapp
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MatrizEntity::class, PaseEntity::class, SolicitudEntity::class, FiltroFechaEntity::class, FiltrarEntity::class, ControlEntity::class, BloqueHorarioEntity::class, ContactoLogEntity::class, PlantillaSmsEntity::class, ConfiguracionAutomatizacionEntity::class, ReglaSemanaEntity::class, RutaIAEntity::class, RutaIAFiltroEntity::class, CanalPagoEntity::class, ContactoExtraEntity::class, VelocidadEntity::class], version = 27, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun matrizDao(): MatrizDao
    abstract fun paseDao(): PaseCarteraDao
    abstract fun solicitudDao(): SolicitudDao
    abstract fun filtroDao(): FiltroFechaDao
    abstract fun filtrarDao(): FiltrarDao
    abstract fun controlDao(): ControlDao
    abstract fun bloqueHorarioDao(): BloqueHorarioDao
    abstract fun contactoLogDao(): ContactoLogDao
    abstract fun plantillaSmsDao(): PlantillaSmsDao
    abstract fun configuracionAutomatizacionDao(): ConfiguracionAutomatizacionDao
    abstract fun reglaSemanaDao(): ReglaSemanaDao
    abstract fun rutaIADao(): RutaIADao
    abstract fun rutaIAFiltroDao(): RutaIAFiltroDao
    abstract fun canalPagoDao(): CanalPagoDao
    abstract fun contactoExtraDao(): ContactoExtraDao
    abstract fun velocidadDao(): VelocidadDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val tables = listOf("matriz_table", "pase_cartera_table", "solicitud_table", "filtro_fecha_table")
                tables.forEach { table ->
                    addColumnSafely(db, table, "isDirty", "INTEGER NOT NULL DEFAULT 0")
                    addColumnSafely(db, table, "lastSync", "INTEGER NOT NULL DEFAULT 0")
                }
            }
            private fun addColumnSafely(db: SupportSQLiteDatabase, table: String, col: String, type: String) {
                try { db.execSQL("ALTER TABLE $table ADD COLUMN $col $type") }
                catch (e: Exception) { if (!(e.message?.contains("duplicate column name", true) ?: false)) throw e }
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE solicitud_table ADD COLUMN numero TEXT"); db.execSQL("ALTER TABLE solicitud_table ADD COLUMN sucursal TEXT"); db.execSQL("ALTER TABLE solicitud_table ADD COLUMN imageUrl2 TEXT"); db.execSQL("ALTER TABLE solicitud_table ADD COLUMN observaciones TEXT") } }
        private val MIGRATION_5_6 = object : Migration(5, 6) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE solicitud_table ADD COLUMN nombreRef1 TEXT"); db.execSQL("ALTER TABLE solicitud_table ADD COLUMN ref1 TEXT"); db.execSQL("ALTER TABLE solicitud_table ADD COLUMN nombreRef2 TEXT"); db.execSQL("ALTER TABLE solicitud_table ADD COLUMN ref2 TEXT") } }
        private val MIGRATION_6_7 = object : Migration(6, 7) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE solicitud_table ADD COLUMN imageUrl3 TEXT"); db.execSQL("ALTER TABLE solicitud_table ADD COLUMN imageUrl4 TEXT") } }
        private val MIGRATION_7_8 = object : Migration(7, 8) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE filtro_fecha_table ADD COLUMN imagenUrl TEXT") } }
        private val MIGRATION_8_9 = object : Migration(8, 9) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE filtro_fecha_table ADD COLUMN ref1 TEXT"); db.execSQL("ALTER TABLE filtro_fecha_table ADD COLUMN ref2 TEXT"); db.execSQL("ALTER TABLE filtro_fecha_table ADD COLUMN ubicacion TEXT") } }
        private val MIGRATION_9_10 = object : Migration(9, 10) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE solicitud_table ADD COLUMN gestorAsignado TEXT NOT NULL DEFAULT 'Flores'"); db.execSQL("ALTER TABLE solicitud_table ADD COLUMN fechaHora INTEGER") } }
        private val MIGRATION_10_11 = object : Migration(10, 11) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE filtro_fecha_table ADD COLUMN req TEXT") } }
        private val MIGRATION_11_12 = object : Migration(11, 12) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("""CREATE TABLE IF NOT EXISTS bloques_horario_table (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, hora INTEGER NOT NULL, minuto INTEGER NOT NULL, activo INTEGER NOT NULL DEFAULT 1)""") } }
        private val MIGRATION_12_13 = object : Migration(12, 13) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("""CREATE TABLE IF NOT EXISTS contacto_log_table (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, clienteId TEXT NOT NULL, fechaDia INTEGER NOT NULL, bloqueIndex INTEGER NOT NULL, timestamp INTEGER NOT NULL)"""); db.execSQL("CREATE INDEX IF NOT EXISTS index_contacto_log_table_clienteId_fechaDia ON contacto_log_table(clienteId, fechaDia)") } }
        private val MIGRATION_13_14 = object : Migration(13, 14) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("""CREATE TABLE IF NOT EXISTS plantilla_sms_table (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, tipo TEXT NOT NULL, semana INTEGER NOT NULL, slot INTEGER NOT NULL, texto TEXT NOT NULL)"""); db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_plantilla_sms_table_tipo_semana_slot ON plantilla_sms_table(tipo, semana, slot)") } }
        private val MIGRATION_14_15 = object : Migration(14, 15) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("""CREATE TABLE IF NOT EXISTS config_automatizacion_table (id INTEGER PRIMARY KEY NOT NULL, simSeleccionada INTEGER, ocultarNumero INTEGER NOT NULL DEFAULT 0, segundosPausaEntreLlamadas INTEGER NOT NULL DEFAULT 5, duracionMaximaLlamada INTEGER NOT NULL DEFAULT 45)"""); db.execSQL("INSERT OR IGNORE INTO config_automatizacion_table (id, simSeleccionada, ocultarNumero, segundosPausaEntreLlamadas, duracionMaximaLlamada) VALUES (1, NULL, 0, 5, 45)") } }
        private val MIGRATION_15_16 = object : Migration(15, 16) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("""CREATE TABLE IF NOT EXISTS regla_semana_table (semana INTEGER PRIMARY KEY NOT NULL, offsets TEXT NOT NULL)"""); val defaults = mapOf(1 to "0,5", 2 to "0,4,8", 3 to "0,2,4,6,8", 4 to "0,1,3,4,6,7,9", 5 to "0,1,2,3,4,5,6,7,8,9"); defaults.forEach { (sem, offsets) -> db.execSQL("INSERT OR IGNORE INTO regla_semana_table (semana, offsets) VALUES ($sem, '$offsets')") } } }
        private val MIGRATION_16_17 = object : Migration(16, 17) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE config_automatizacion_table ADD COLUMN simSms INTEGER") } }
        private val MIGRATION_17_18 = object : Migration(17, 18) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("DROP TABLE IF EXISTS pase_cartera_table"); db.execSQL("""CREATE TABLE IF NOT EXISTS pase_cartera_table (id TEXT PRIMARY KEY NOT NULL, nombre TEXT NOT NULL, semana TEXT NOT NULL, requisito TEXT NOT NULL, numTT TEXT NOT NULL, ref1 TEXT NOT NULL, ref2 TEXT NOT NULL, observaciones TEXT, estado TEXT NOT NULL, ubicacion TEXT, imagenUrl TEXT, imagenUrl2 TEXT, fecha INTEGER, hora TEXT, ruta TEXT, folioP TEXT, origenMatrizId TEXT NOT NULL, isDirty INTEGER NOT NULL DEFAULT 0, lastSync INTEGER NOT NULL DEFAULT 0)""") } }
        private val MIGRATION_18_19 = object : Migration(18, 19) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE regla_semana_table ADD COLUMN catchupActivo INTEGER NOT NULL DEFAULT 1") } }
        private val MIGRATION_19_20 = object : Migration(19, 20) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("""CREATE TABLE IF NOT EXISTS ruta_ia_table (id TEXT PRIMARY KEY NOT NULL, nombre TEXT NOT NULL, cu TEXT, direccion TEXT NOT NULL, coloniaCp TEXT, diasAtraso INTEGER, pagoRequerido REAL, lat REAL, lng REAL, orden INTEGER NOT NULL DEFAULT 0, esNuevo INTEGER NOT NULL DEFAULT 1, cuMatrizMatch TEXT, fechaDia INTEGER NOT NULL, estado TEXT NOT NULL DEFAULT 'Pendiente', fotoOrigenUrl TEXT, isDirty INTEGER NOT NULL DEFAULT 0, lastSync INTEGER NOT NULL DEFAULT 0)"""); db.execSQL("""CREATE TABLE IF NOT EXISTS ruta_ia_filtro_table (id INTEGER PRIMARY KEY NOT NULL, criteriosOrden TEXT NOT NULL DEFAULT 'DISTANCIA:ASC')"""); db.execSQL("INSERT OR IGNORE INTO ruta_ia_filtro_table (id, criteriosOrden) VALUES (1, 'DISTANCIA:ASC')") } }
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pase_cartera_table ADD COLUMN contiene TEXT")
                db.execSQL("ALTER TABLE pase_cartera_table ADD COLUMN capitales TEXT")
            }
        }
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ruta_ia_table ADD COLUMN saldoAtraso REAL")
            }
        }
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS canal_pago_table (id TEXT NOT NULL PRIMARY KEY, nombre TEXT NOT NULL, empresa TEXT, direccion TEXT, lat REAL NOT NULL, lng REAL NOT NULL, lastSync INTEGER NOT NULL)")
            }
        }
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS contacto_extra_table (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, clienteId TEXT NOT NULL, telefono TEXT NOT NULL, nombreOrigen TEXT NOT NULL, fechaAgregado INTEGER NOT NULL)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_contacto_extra_table_clienteId ON contacto_extra_table(clienteId)")
            }
        }
        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS velocidad_table (gic TEXT NOT NULL PRIMARY KEY, rk TEXT, plan100 TEXT, lunes TEXT, martes TEXT, miercoles TEXT, jueves TEXT, viernes TEXT, sabado TEXT, domingo TEXT, total TEXT, planAvance TEXT, reque36 TEXT, monto36 TEXT, porcentaje36 TEXT, arriba TEXT, requeFavor TEXT, cuPase TEXT, capital TEXT, perdida TEXT, planMeta TEXT, monto TEXT, meFaltan TEXT, metaDiaria TEXT, diasOp TEXT, deficit TEXT, lastSync INTEGER NOT NULL)""")
            }
        }
        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Cambia de "copia de solo lectura de Sheets" a "captura manual local" -- el
                // esquema es incompatible (antes texto por columna de Sheets, ahora números que
                // Diego teclea) y la tabla nunca llegó a tener datos reales (siempre fallaba el
                // fetch a Sheets), así que se recrea sin intentar preservar nada.
                db.execSQL("DROP TABLE IF EXISTS velocidad_table")
                db.execSQL("""CREATE TABLE IF NOT EXISTS velocidad_table (id INTEGER NOT NULL PRIMARY KEY, rk TEXT NOT NULL, plan100 REAL NOT NULL, lunes REAL NOT NULL, martes REAL NOT NULL, miercoles REAL NOT NULL, jueves REAL NOT NULL, viernes REAL NOT NULL, sabado REAL NOT NULL, domingo REAL NOT NULL, reque36 REAL NOT NULL, monto36 REAL NOT NULL, cuPase INTEGER NOT NULL, capital REAL NOT NULL, diasOp INTEGER NOT NULL, lastUpdate INTEGER NOT NULL)""")
            }
        }
        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Diego pidió quitar la captura por día (Lunes-Domingo) y dejar un solo campo
                // Total que él teclea directo -- se recrea la tabla, ya que solo tenía como
                // mucho una fila de prueba.
                db.execSQL("DROP TABLE IF EXISTS velocidad_table")
                db.execSQL("""CREATE TABLE IF NOT EXISTS velocidad_table (id INTEGER NOT NULL PRIMARY KEY, rk TEXT NOT NULL, plan100 REAL NOT NULL, total REAL NOT NULL, reque36 REAL NOT NULL, monto36 REAL NOT NULL, cuPase INTEGER NOT NULL, capital REAL NOT NULL, diasOp INTEGER NOT NULL, lastUpdate INTEGER NOT NULL)""")
            }
        }
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "matriz_database")
                    .addMigrations(MIGRATION_1_2, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
