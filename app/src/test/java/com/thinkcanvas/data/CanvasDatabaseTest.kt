package com.thinkcanvas.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import androidx.sqlite.execSQL
import com.thinkcanvas.board.duplicated
import com.thinkcanvas.canvas.ArrowElement
import com.thinkcanvas.canvas.ArrowEnd
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.ShapeElement
import com.thinkcanvas.canvas.ShapeKind
import com.thinkcanvas.canvas.TextElement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

class CanvasDatabaseTest {
    @Test fun editsAlwaysMoveTheBoardToTheTopEvenWithinOneMillisecond() { runBlocking {
        val file = Files.createTempFile("think-canvas-order-", ".db").toFile()
        file.delete()
        val database = Room.databaseBuilder<CanvasDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        val dao = database.canvasDao()
        val first = dao.createBoard("一", updatedAt = 100)
        val second = dao.createBoard("二", updatedAt = 100)
        assertEquals(second.id, dao.boards().first().id)
        assertEquals(1, dao.renameBoard(first.id, "更新", updatedAt = 100))
        assertEquals(first.id, dao.boards().first().id)
        dao.replaceAll(second.id, emptyList(), emptyList(), emptyList())
        assertEquals(second.id, dao.boards().first().id)
        database.close()
        file.delete()
    } }

    @Test
    fun versionOneMigrationPreservesExistingBoardAndElements() { runBlocking {
        val file = Files.createTempFile("think-canvas-migration-", ".db").toFile()
        file.delete()
        val legacy = BundledSQLiteDriver().open(file.absolutePath)
        legacy.execSQL("CREATE TABLE boards (id INTEGER NOT NULL, name TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
        legacy.execSQL("CREATE TABLE text_elements (id TEXT NOT NULL, boardId INTEGER NOT NULL, text TEXT NOT NULL, kind TEXT NOT NULL, color TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, PRIMARY KEY(id))")
        legacy.execSQL("CREATE TABLE spatial_elements (id TEXT NOT NULL, boardId INTEGER NOT NULL, kind TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, width REAL NOT NULL, height REAL NOT NULL, color TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(id))")
        legacy.execSQL("CREATE TABLE arrow_elements (id TEXT NOT NULL, boardId INTEGER NOT NULL, fromTargetId TEXT, fromU REAL, fromV REAL, fromX REAL, fromY REAL, toTargetId TEXT, toU REAL, toV REAL, toX REAL, toY REAL, bend REAL NOT NULL, PRIMARY KEY(id))")
        legacy.execSQL("INSERT INTO boards VALUES (1, '既存ボード', 123)")
        legacy.execSQL("INSERT INTO text_elements VALUES ('text', 1, '既存の考え', 'BODY', 'INK', 12.5, -8.0)")
        legacy.execSQL("PRAGMA user_version = 1")
        legacy.close()

        val database = Room.databaseBuilder<CanvasDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(CanvasDatabase.MIGRATION_1_2)
            .build()
        assertEquals("既存ボード", database.canvasDao().board(1)?.name)
        assertEquals("既存の考え", database.canvasDao().elements(1).single().text)
        assertEquals(12.5f, database.canvasDao().elements(1).single().x)
        assertEquals(emptyList<InkStrokeRow>(), database.canvasDao().inkStrokes(1))
        database.close()
        file.delete()
    } }

    @Test
    fun committedElementsSurviveDatabaseReopenAndReplacement() {
        runBlocking {
            val file = Files.createTempFile("think-canvas-room-", ".db").toFile()
            file.delete()
            fun open() = Room.databaseBuilder<CanvasDatabase>(file.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .build()

            val first = open()
            first.canvasDao().putBoard(BoardRow())
            val initial = TextElementRow(
                id = "element-1", boardId = 1, text = "考え", kind = "BODY", color = "INK", x = 42f, y = -17f,
            )
            first.canvasDao().replaceAll(1, listOf(initial), emptyList(), emptyList())
            first.close()

            val second = open()
            assertEquals(initial, second.canvasDao().elements(1).single())
            assertEquals("無題のボード", second.canvasDao().board(1)?.name)
            second.canvasDao().replaceAll(1, emptyList(), emptyList(), emptyList())
            second.close()

            val third = open()
            assertEquals(emptyList<TextElementRow>(), third.canvasDao().elements(1))
            third.close()
            file.delete()
        }
    }

    @Test
    fun shapesAndArrowsSurviveReopenWithoutChangingBoardName() { runBlocking {
        val file = Files.createTempFile("think-canvas-spatial-", ".db").toFile()
        file.delete()
        fun open() = Room.databaseBuilder<CanvasDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        val first = open()
        first.canvasDao().putBoard(BoardRow(name = "計画"))
        val shape = SpatialElementRow("shape", boardId = 1, kind = "RECTANGLE", x = 12f, y = 20f,
            width = 120f, height = 80f, color = "INK", name = "")
        val arrow = ArrowElementRow("arrow", boardId = 1, fromTargetId = "shape", fromU = 1f,
            fromV = .5f, fromX = null, fromY = null, toTargetId = null,
            toU = null, toV = null, toX = 320f, toY = 40f, bend = 15f)
        first.canvasDao().replaceAll(1, emptyList(), listOf(shape), listOf(arrow))
        first.close()

        val second = open()
        assertEquals("計画", second.canvasDao().board(1)?.name)
        assertEquals(shape, second.canvasDao().spatialElements(1).single())
        assertEquals(arrow, second.canvasDao().arrows(1).single())
        second.canvasDao().replaceAll(1, emptyList(), emptyList(), emptyList())
        assertEquals(emptyList<ArrowElementRow>(), second.canvasDao().arrows(1))
        second.close()
        file.delete()
    } }

    @Test
    fun multipleBoardsStaySeparateAcrossCopyDeleteAndReopen() { runBlocking {
        val file = Files.createTempFile("think-canvas-boards-", ".db").toFile()
        file.delete()
        fun open() = Room.databaseBuilder<CanvasDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        val original = BoardSnapshot(
            texts = listOf(TextElement(id = "source-text", text = "残す内容", x = 3f, y = 7f)),
            shapes = listOf(ShapeElement(id = "source-shape", kind = ShapeKind.RECTANGLE,
                x = 0f, y = 0f, width = 120f, height = 60f)),
            arrows = listOf(ArrowElement(id = "source-arrow",
                from = ArrowEnd.Attached("source-text", .5f, .5f),
                to = ArrowEnd.Attached("source-shape", .5f, .5f))),
        )
        val first = open()
        val dao = first.canvasDao()
        dao.putBoard(BoardRow(id = 1, name = "元"))
        dao.replaceAll(1, original.texts.map { TextElementRow.fromModel(1, it) },
            original.shapes.map { SpatialElementRow.fromModel(1, it) },
            original.arrows.map { ArrowElementRow.fromModel(1, it) })
        val copied = dao.createBoardWithSnapshot("コピー", original.duplicated())
        assertNotEquals(1L, copied.id)
        assertEquals(listOf("コピー", "元"), dao.boards().map { it.name })
        assertNotEquals(dao.elements(1).single().id, dao.elements(copied.id).single().id)
        assertEquals("source-text", dao.elements(1).single().id)
        assertTrue(dao.deleteBoard(copied.id))
        assertFalse(dao.deleteBoard(copied.id))
        val staleSave = runCatching { dao.replaceAll(copied.id, emptyList(), emptyList(), emptyList()) }
        assertTrue(staleSave.isFailure)
        val replacement = dao.createBoard("新規")
        assertNotEquals("削除済み ID を再利用しない", copied.id, replacement.id)
        assertTrue(runCatching {
            dao.replaceAll(copied.id, listOf(TextElementRow.fromModel(copied.id,
                TextElement(id = "late", text = "遅延保存", x = 0f, y = 0f))), emptyList(), emptyList())
        }.isFailure)
        assertTrue(dao.elements(replacement.id).isEmpty())
        assertEquals("残す内容", dao.elements(1).single().text)
        first.close()

        val reopened = open()
        assertEquals(setOf("元", "新規"), reopened.canvasDao().boards().map { it.name }.toSet())
        assertEquals("残す内容", reopened.canvasDao().elements(1).single().text)
        assertEquals("source-shape", reopened.canvasDao().spatialElements(1).single().id)
        assertEquals("source-arrow", reopened.canvasDao().arrows(1).single().id)
        reopened.close()
        file.delete()
    } }
}
