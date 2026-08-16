package com.example.matrizapp
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MatrizEntity::class, PaseEntity::class, SolicitudEntity::class, FiltroFechaEntity::class, FiltrarEntity::class, ControlEntity::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun matrizDao(): MatrizDao
    abstract fun paseDao(): PaseCarteraDao
    abstract fun solicitudDao(): SolicitudDao
    abstract fun filtroDao(): FiltroFechaDao
    abstract fun filtrarDao(): FiltrarDao
    abstract fun controlDao(): ControlDao

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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "matriz_database")
                    .addMigrations(MIGRATION_1_2, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}