package com.thinkcanvas.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class CanvasDatabaseTest {
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
