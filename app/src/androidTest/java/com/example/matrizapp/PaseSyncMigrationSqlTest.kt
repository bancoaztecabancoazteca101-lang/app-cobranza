package com.example.matrizapp

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prueba acotada de la operación SQL 37 -> 38.
 * La prueba integral con MigrationTestHelper y los snapshots 37/38 queda pendiente
 * hasta versionar ambos esquemas generados por Room en app/schemas.
 */
@RunWith(AndroidJUnit4::class)
class PaseSyncMigrationSqlTest {
    @Test
    fun migrationCreatesQueueAndPreservesExistingPaseRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "migration-37-38-test.db"
        context.deleteDatabase(dbName)

        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE pase_cartera_table (id TEXT NOT NULL PRIMARY KEY, nombre TEXT NOT NULL)"
                    )
                    db.execSQL(
                        "INSERT INTO pase_cartera_table (id, nombre) VALUES ('cliente-prueba-001', 'Registro conservado')"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)

        try {
            val db = helper.writableDatabase
            assertEquals(1, db.query("SELECT COUNT(*) FROM pase_cartera_table").use { c ->
                c.moveToFirst()
                c.getInt(0)
            })

            AppDatabase.MIGRATION_37_38.migrate(db)

            assertEquals(1, db.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='pase_sync_operation_table'").use { c ->
                c.moveToFirst()
                c.getInt(0)
            })
            assertEquals("Registro conservado", db.query(
                "SELECT nombre FROM pase_cartera_table WHERE id='cliente-prueba-001'"
            ).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            })
            assertEquals(0, db.query("SELECT COUNT(*) FROM pase_sync_operation_table").use { c ->
                c.moveToFirst()
                c.getInt(0)
            })
        } finally {
            helper.close()
            context.deleteDatabase(dbName)
        }
    }
}
