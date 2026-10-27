package com.example.matrizapp

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Valida la migración con los snapshots reales de Room 37 y 38.
 * MigrationTestHelper también compara el esquema final completo con 38.json.
 */
@RunWith(AndroidJUnit4::class)
class PaseSyncMigrationRoomTest {
    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(AppDatabase.MIGRATION_37_38),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migration37To38PreservesPaseAndValidatesEntireSchema() {
        val dbName = "room-migration-37-38-full-test"

        helper.createDatabase(dbName, 37).apply {
            execSQL(
                """
                INSERT INTO pase_cartera_table (
                    id, nombre, semana, requisito, numTT, ref1, ref2,
                    estado, origenMatrizId, isDirty, lastSync
                ) VALUES (
                    'cliente-prueba-001', 'Registro conservado', 'S1', 'R1',
                    'TT1', '', '', 'Pendiente', 'origen-prueba-001', 1, 123
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            dbName,
            38,
            true,
            AppDatabase.MIGRATION_37_38
        )

        migratedDb.use { db ->
            db.query(
                "SELECT nombre, origenMatrizId, isDirty, lastSync FROM pase_cartera_table WHERE id = ?",
                arrayOf("cliente-prueba-001")
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("Registro conservado", cursor.getString(0))
                assertEquals("origen-prueba-001", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
                assertEquals(123L, cursor.getLong(3))
            }

            db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'pase_sync_operation_table'"
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }

            db.query("SELECT COUNT(*) FROM pase_sync_operation_table").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }
}
