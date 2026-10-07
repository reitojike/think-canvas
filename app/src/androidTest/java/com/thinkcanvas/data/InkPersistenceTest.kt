package com.thinkcanvas.data

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.canvas.InkElement
import com.thinkcanvas.canvas.InkInputType
import com.thinkcanvas.canvas.InkKind
import com.thinkcanvas.canvas.InkPoint
import com.thinkcanvas.canvas.InkStroke
import com.thinkcanvas.canvas.Viewport
import kotlinx.coroutines.runBlocking
import com.thinkcanvas.test.PrSmoke
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkPersistenceTest {
    @PrSmoke
    @Test
    fun inkInputEncodingSurvivesDatabaseReopenAtThreeViewports() { runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ink-persistence-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, CanvasDatabase::class.java, name)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(CanvasDatabase.MIGRATION_1_2)
            .build()

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
        first.canvasDao().putBoard(BoardRow())
        first.canvasDao().replaceAll(1, emptyList(), emptyList(), emptyList(), InkStrokeRow.fromModel(1, model))
        first.close()

        val second = open()
        val restored = InkStrokeRow.toElements(second.canvasDao().inkStrokes(1)).single()
        assertEquals(model, restored)
        second.close()
        context.deleteDatabase(name)
    } }
}
