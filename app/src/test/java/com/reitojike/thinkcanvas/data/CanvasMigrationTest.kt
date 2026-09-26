package com.reitojike.thinkcanvas.data

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CanvasMigrationTest {
    private val databasePath = Files.createTempFile("think-canvas-v1-", ".db").also { Files.deleteIfExists(it) }

    @get:Rule
    val helper = MigrationTestHelper(
        schemaDirectoryPath = File("schemas").toPath(),
        databasePath = databasePath,
        driver = BundledSQLiteDriver(),
        databaseClass = CanvasDatabase::class,
    )

    @Test
    fun v1BoardAndTextRemainAfterV2Migration() = runBlocking {
        helper.createDatabase(1).use { connection ->
            connection.prepare("INSERT INTO boards (id, name, updatedAt) VALUES (1, '既存ボード', 1234)").use { it.step() }
            connection.prepare(
                "INSERT INTO text_elements (id, boardId, text, kind, color, x, y) " +
                    "VALUES ('old-text', 1, '以前の考え', 'TITLE', 'VERMILION', 42.5, -17.25)",
            ).use { it.step() }
        }

        helper.runMigrationsAndValidate(2).use { connection ->
            connection.prepare("SELECT name, updatedAt FROM boards WHERE id = 1").use {
                assertTrue(it.step())
                assertEquals("既存ボード", it.getText(0))
                assertEquals(1234L, it.getLong(1))
                assertFalse(it.step())
            }
            connection.prepare("SELECT text, kind, color, x, y FROM text_elements WHERE id = 'old-text'").use {
                assertTrue(it.step())
                assertEquals("以前の考え", it.getText(0))
                assertEquals("TITLE", it.getText(1))
                assertEquals("VERMILION", it.getText(2))
                assertEquals(42.5, it.getDouble(3), 0.001)
                assertEquals(-17.25, it.getDouble(4), 0.001)
                assertFalse(it.step())
            }
            connection.prepare("SELECT COUNT(*) FROM spatial_elements").use {
                assertTrue(it.step())
                assertEquals(0L, it.getLong(0))
            }
            connection.prepare("SELECT COUNT(*) FROM arrow_elements").use {
                assertTrue(it.step())
                assertEquals(0L, it.getLong(0))
            }
        }
    }
}
