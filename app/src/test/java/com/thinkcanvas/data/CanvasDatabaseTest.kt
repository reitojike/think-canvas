package com.thinkcanvas.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.nio.file.Files
import androidx.sqlite.execSQL
import com.thinkcanvas.canvas.InkElement
import com.thinkcanvas.canvas.InkInputType
import com.thinkcanvas.canvas.InkKind
import com.thinkcanvas.canvas.InkPoint
import com.thinkcanvas.canvas.InkStroke
import com.thinkcanvas.canvas.Viewport

class CanvasDatabaseTest {
    @Test
    fun inkInputEncodingSurvivesDatabaseReopen() { runBlocking {
        // Ink 1.0.0 の JVM native library は Windows 向けに公開されていない。
        // CI の Linux JVM では実行し、Windows では Android 実機/エミュレーターで確認する。
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val file = Files.createTempFile("think-canvas-ink-", ".db").toFile()
        file.delete()
        fun open() = Room.databaseBuilder<CanvasDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver()).addMigrations(CanvasDatabase.MIGRATION_1_2).build()
        val viewports = listOf(Viewport(), Viewport(2f, 100f, -40f), Viewport(.5f, -100f, 20f))
        val model = InkElement(id = "group", kind = InkKind.MARKER, strokes = viewports.mapIndexed { index, viewport ->
            fun point(x: Float, y: Float, elapsed: Long): InkPoint {
                val screen = viewport.worldToScreen(x, y)
                val world = viewport.screenToWorld(screen.first, screen.second)
                return InkPoint(world.first, world.second, elapsed)
            }
            InkStroke(id = "stroke-$index", startedAt = 10L + index * 2_000L,
                endedAt = 30L + index * 2_000L, inputType = InkInputType.STYLUS,
                points = listOf(point(12.5f, -4f, 0), point(20f, 8.5f, 20)))
        })
        val first = open()
        first.canvasDao().replaceAll(emptyList(), emptyList(), emptyList(),
            InkStrokeRow.fromModel(model))
        first.close()
        val second = open()
        val restored = InkStrokeRow.toElements(second.canvasDao().inkStrokes()).single()
        assertEquals(model, restored)
        second.close()
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
        assertEquals("既存ボード", database.canvasDao().firstBoard()?.name)
        assertEquals("既存の考え", database.canvasDao().elements().single().text)
        assertEquals(12.5f, database.canvasDao().elements().single().x)
        assertEquals(emptyList<InkStrokeRow>(), database.canvasDao().inkStrokes())
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
            val initial = TextElementRow(
                id = "element-1", text = "考え", kind = "BODY", color = "INK", x = 42f, y = -17f,
            )
            first.canvasDao().replaceAll(listOf(initial))
            first.close()

            val second = open()
            assertEquals(initial, second.canvasDao().elements().single())
            assertEquals("無題のボード", second.canvasDao().firstBoard()?.name)
            second.canvasDao().replaceAll(emptyList())
            second.close()

            val third = open()
            assertEquals(emptyList<TextElementRow>(), third.canvasDao().elements())
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
        val shape = SpatialElementRow("shape", kind = "RECTANGLE", x = 12f, y = 20f,
            width = 120f, height = 80f, color = "INK", name = "")
        val arrow = ArrowElementRow("arrow", fromTargetId = "shape", fromU = 1f,
            fromV = .5f, fromX = null, fromY = null, toTargetId = null,
            toU = null, toV = null, toX = 320f, toY = 40f, bend = 15f)
        first.canvasDao().replaceAll(emptyList(), listOf(shape), listOf(arrow))
        first.close()

        val second = open()
        assertEquals("計画", second.canvasDao().firstBoard()?.name)
        assertEquals(shape, second.canvasDao().spatialElements().single())
        assertEquals(arrow, second.canvasDao().arrows().single())
        second.canvasDao().replaceAll(emptyList(), emptyList(), emptyList())
        assertEquals(emptyList<ArrowElementRow>(), second.canvasDao().arrows())
        second.close()
        file.delete()
    } }
}
