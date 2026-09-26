package com.reitojike.thinkcanvas.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class CanvasDatabaseTest {
    @Test
    fun committedElementsSurviveDatabaseReopenAndUndoSnapshot() {
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
}
