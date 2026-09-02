package com.example.matrizapp
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MatrizEntity::class, PaseEntity::class, SolicitudEntity::class, FiltroFechaEntity::class, FiltrarEntity::class, ControlEntity::class, BloqueHorarioEntity::class, ContactoLogEntity::class, PlantillaSmsEntity::class, ConfiguracionAutomatizacionEntity::class, ReglaSemanaEntity::class], version = 18, exportSchema = false)
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
                try {
                    db.execSQL("ALTER TABLE $table ADD COLUMN $col $type")
                } catch (e: Exception) {
                    if (!(e.message?.contains("duplicate column name", true) ?: false)) throw e
                }
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE solicitud_table ADD COLUMN numero TEXT")
                db.execSQL("ALTER TABLE solicitud_table ADD COLUMN sucursal TEXT")
                db.execSQL("ALTER TABLE solicitud_table ADD COLUMN imageUrl2 TEXT")
                db.execSQL("ALTER TABLE solicitud_table ADD COLUMN observaciones TEXT")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE solicitud_table ADD COLUMN nombreRef1 TEXT")
                db.execSQL("ALTER TABLE solicitud_table ADD COLUMN ref1 TEXT")
                db.execSQL("ALTER TABLE solicitud_table ADD COLUMN nombreRef2 TEXT")
                db.execSQL("ALTER TABLE solicitud_table ADD COLUMN ref2 TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE solicitud_table ADD COLUMN imageUrl3 TEXT")
                db.execSQL("ALTER TABLE solicitud_table ADD COLUMN imageUrl4 TEXT")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE filtro_fecha_table ADD COLUMN imagenUrl TEXT")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE filtro_fecha_table ADD COLUMN ref1 TEXT")
                db.execSQL("ALTER TABLE filtro_fecha_table ADD COLUMN ref2 TEXT")
                db.execSQL("ALTER TABLE filtro_fecha_table ADD COLUMN ubicacion TEXT")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE solicitud_table ADD COLUMN gestorAsignado TEXT NOT NULL DEFAULT 'Flores'")
                db.execSQL("ALTER TABLE solicitud_table ADD COLUMN fechaHora INTEGER")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE filtro_fecha_table ADD COLUMN req TEXT")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bloques_horario_table (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        hora INTEGER NOT NULL,
                        minuto INTEGER NOT NULL,
                        activo INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS contacto_log_table (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        clienteId TEXT NOT NULL,
                        fechaDia INTEGER NOT NULL,
                        bloqueIndex INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_contacto_log_table_clienteId_fechaDia ON contacto_log_table(clienteId, fechaDia)")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS plantilla_sms_table (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        tipo TEXT NOT NULL,
                        semana INTEGER NOT NULL,
                        slot INTEGER NOT NULL,
                        texto TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_plantilla_sms_table_tipo_semana_slot ON plantilla_sms_table(tipo, semana, slot)")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS config_automatizacion_table (
                        id INTEGER PRIMARY KEY NOT NULL,
                        simSeleccionada INTEGER,
                        ocultarNumero INTEGER NOT NULL DEFAULT 0,
                        segundosPausaEntreLlamadas INTEGER NOT NULL DEFAULT 5,
                        duracionMaximaLlamada INTEGER NOT NULL DEFAULT 45
                    )
                """.trimIndent())
                // Semilla con los mismos defaults que ya usaba el flujo automático hardcodeado
                // (subId null, sin ocultar número, 45s de duración máxima) para no cambiar
                // comportamiento existente al migrar instalaciones ya en uso.
                db.execSQL("INSERT OR IGNORE INTO config_automatizacion_table (id, simSeleccionada, ocultarNumero, segundosPausaEntreLlamadas, duracionMaximaLlamada) VALUES (1, NULL, 0, 5, 45)")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS regla_semana_table (
                        semana INTEGER PRIMARY KEY NOT NULL,
                        offsets TEXT NOT NULL
                    )
                """.trimIndent())
                // Semilla con los mismos offsets que ReglaRepeticion.BLOQUES_POR_SEM tenía
                // hardcodeados, para no cambiar comportamiento existente al migrar.
                val defaults = mapOf(1 to "0,5", 2 to "0,4,8", 3 to "0,2,4,6,8", 4 to "0,1,3,4,6,7,9", 5 to "0,1,2,3,4,5,6,7,8,9")
                defaults.forEach { (sem, offsets) ->
                    db.execSQL("INSERT OR IGNORE INTO regla_semana_table (semana, offsets) VALUES ($sem, '$offsets')")
                }
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // SIM independiente para SMS automáticos -- antes reusaba simSeleccionada
                // (la misma línea de las llamadas), sin forma de cambiarla aparte.
                db.execSQL("ALTER TABLE config_automatizacion_table ADD COLUMN simSms INTEGER")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Pase pasa de una tabla reducida (sincronizada aparte, siempre vacía en la
                // práctica) a una copia independiente de Matriz con exactamente los mismos
                // campos -- se recrea desde cero porque los datos viejos nunca se llenaban.
                db.execSQL("DROP TABLE IF EXISTS pase_cartera_table")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pase_cartera_table (
                        id TEXT PRIMARY KEY NOT NULL,
                        nombre TEXT NOT NULL, semana TEXT NOT NULL, requisito TEXT NOT NULL, numTT TEXT NOT NULL,
                        ref1 TEXT NOT NULL, ref2 TEXT NOT NULL, observaciones TEXT, estado TEXT NOT NULL,
                        ubicacion TEXT, imagenUrl TEXT, imagenUrl2 TEXT, fecha INTEGER, hora TEXT,
                        ruta TEXT, folioP TEXT, origenMatrizId TEXT NOT NULL,
                        isDirty INTEGER NOT NULL DEFAULT 0, lastSync INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "matriz_database")
                    .addMigrations(MIGRATION_1_2, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
