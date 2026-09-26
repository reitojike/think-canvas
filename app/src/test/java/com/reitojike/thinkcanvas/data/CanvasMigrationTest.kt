package com.reitojike.thinkcanvas.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CanvasMigrationTest {
    @Test
    fun v1BoardAndTextRemainAfterV2Migration() = runBlocking {
        val file = Files.createTempFile("think-canvas-v1-", ".db")
        val driver = BundledSQLiteDriver()
        // app/schemas/.../1.json の createSql と identityHash を使って旧版を再現する。
        driver.open(file.toString()).use { connection ->
            listOf(
                "CREATE TABLE IF NOT EXISTS boards (id INTEGER NOT NULL, name TEXT NOT NULL, " +
                    "updatedAt INTEGER NOT NULL, PRIMARY KEY(id))",
                "CREATE TABLE IF NOT EXISTS text_elements (id TEXT NOT NULL, boardId INTEGER NOT NULL, " +
                    "text TEXT NOT NULL, kind TEXT NOT NULL, color TEXT NOT NULL, " +
                    "x REAL NOT NULL, y REAL NOT NULL, PRIMARY KEY(id))",
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
                "INSERT INTO room_master_table (id, identity_hash) " +
                    "VALUES (42, '216891bcfd8f2ddd514b2f95477e6eb3')",
                "INSERT INTO boards (id, name, updatedAt) VALUES (1, '既存ボード', 1234)",
                "INSERT INTO text_elements (id, boardId, text, kind, color, x, y) " +
                    "VALUES ('old-text', 1, '以前の考え', 'TITLE', 'VERMILION', 42.5, -17.25)",
                "PRAGMA user_version = 1",
            ).forEach { sql -> connection.prepare(sql).use { it.step() } }
        }

        val database = Room.databaseBuilder<CanvasDatabase>(file.toString())
            .setDriver(driver).build()
        try {
            val dao = database.canvasDao()
            assertEquals("既存ボード", dao.firstBoard()?.name)
            assertEquals(1234L, dao.firstBoard()?.updatedAt)
            val text = dao.elements().single()
            assertEquals("以前の考え", text.text)
            assertEquals("TITLE", text.kind)
            assertEquals("VERMILION", text.color)
            assertEquals(42.5f, text.x)
            assertEquals(-17.25f, text.y)
            assertTrue(dao.spatialElements().isEmpty())
            assertTrue(dao.arrows().isEmpty())
        } finally {
            database.close()
            Files.deleteIfExists(file)
        }
    }
}
