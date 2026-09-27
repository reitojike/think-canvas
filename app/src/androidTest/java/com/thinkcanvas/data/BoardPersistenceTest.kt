package com.thinkcanvas.data

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoardPersistenceTest {
    @Test fun boardsRemainSeparateAcrossReopenDeleteAndLateSave() { runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "board-lifecycle-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, CanvasDatabase::class.java, name)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(CanvasDatabase.MIGRATION_1_2).build()
        val first = open()
        val dao = first.canvasDao()
        dao.putBoard(BoardRow(id = 1, name = "既存"))
        val original = TextElementRow(id = "original", boardId = 1, text = "保持する",
            kind = "BODY", color = "INK", x = 10f, y = 20f)
        dao.replaceAll(1, listOf(original), emptyList(), emptyList())
        val second = dao.createBoard("追加")
        val added = original.copy(id = "added", boardId = second.id, text = "独立した内容")
        dao.replaceAll(second.id, listOf(added), emptyList(), emptyList())
        first.close()

        val reopened = open()
        val after = reopened.canvasDao()
        assertEquals(listOf(original), after.elements(1))
        assertEquals(listOf(added), after.elements(second.id))
        assertTrue(after.deleteBoard(second.id))
        val replacement = after.createBoard("次のボード")
        assertNotEquals(second.id, replacement.id)
        assertTrue(runCatching {
            after.replaceAll(second.id, listOf(added), emptyList(), emptyList())
        }.isFailure)
        assertTrue(after.elements(replacement.id).isEmpty())
        assertEquals(listOf(original), after.elements(1))
        reopened.close()
        context.deleteDatabase(name)
    } }
}
