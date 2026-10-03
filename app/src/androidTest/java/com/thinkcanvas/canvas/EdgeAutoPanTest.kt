package com.thinkcanvas.canvas

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.BoardSaveState
import com.thinkcanvas.BoardSessionViewModel
import com.thinkcanvas.MainActivity
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.CanvasStore
import com.thinkcanvas.data.TextElementRow
import com.thinkcanvas.data.showBoardOneAtStartup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** 固定した指・別要素・内容・Roomを独立に観測するSpec008の操作回帰。 */
@RunWith(AndroidJUnit4::class)
class EdgeAutoPanTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val moving = TextElement(id = "edge-moving", text = "Carry this", x = 500f, y = 1200f)
    private val fixed = TextElement(id = "edge-fixed", text = "Fixed reference", x = 1300f, y = 1800f)

    private inner class Harness(val scenario: ActivityScenario<MainActivity>,
                               val sessions: BoardSessionViewModel, val database: CanvasDatabase) {
        val board get() = sessions.stateFor(1L, BoardSnapshot())
        val saves = AtomicInteger()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val canvas get() = composeRule.onNodeWithContentDescription("キャンバス")
            .fetchSemanticsNode().boundsInWindow
        fun bounds(label: String) = composeRule.onNodeWithContentDescription(label)
            .fetchSemanticsNode().boundsInWindow
        fun frames(count: Int) { repeat(count) { composeRule.mainClock.advanceTimeByFrame() } }
        fun send(action: Int, point: Offset, downTime: Long) {
            val origin = IntArray(2)
            scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                point.x + origin[0], point.y + origin[1], 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        fun stationaryDrag(cancel: Boolean = false) {
            composeRule.onNodeWithContentDescription(moving.text).performClick()
            val start = bounds("要素を移動").center
            val area = canvas
            val edge = Offset(area.right - 3f, area.top + area.height * .45f)
            val initial = board.snapshot()
            val fixedBefore = bounds(fixed.text).topLeft
            val downTime = SystemClock.uptimeMillis()
            composeRule.mainClock.autoAdvance = false
            try {
                send(MotionEvent.ACTION_DOWN, start, downTime)
                frames(2)
                send(MotionEvent.ACTION_MOVE, edge, downTime)
                frames(4)
                val targetBefore = bounds(moving.text).topLeft
                frames(90)
                val targetAfter = bounds(moving.text).topLeft
                val fixedAfter = bounds(fixed.text).topLeft
                assertTrue("静止した指の保持中にもcameraが進む", fixedAfter.x < fixedBefore.x - 100f)
                assertEquals("finger offset x", targetBefore.x, targetAfter.x, 3f)
                assertEquals("finger offset y", targetBefore.y, targetAfter.y, 3f)
                assertEquals("UP前の内容", initial, board.snapshot())
                assertEquals(0, saves.get())
                assertFalse(board.canUndo)
                // Return to the central stop band, then retain the move until release.
                val center = area.center
                send(MotionEvent.ACTION_MOVE, center, downTime)
                frames(4)
                val stopped = bounds(fixed.text).topLeft
                frames(20)
                assertEquals(stopped, bounds(fixed.text).topLeft)
                send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, center, downTime)
                frames(3)
            } finally {
                send(MotionEvent.ACTION_CANCEL, edge, downTime)
                composeRule.mainClock.autoAdvance = true
            }
            composeRule.waitForIdle()
            if (cancel) {
                assertEquals(initial, board.snapshot())
                assertEquals(0, saves.get())
                assertFalse(board.canUndo)
            } else {
                composeRule.waitUntil(10_000) { sessions.saveStateFor(1L, BoardSnapshot()).value == BoardSaveState.Idle }
                val after = board.snapshot()
                assertNotEquals(initial, after)
                assertEquals(fixed, after.texts.single { it.id == fixed.id })
                assertEquals(1, saves.get())
                assertEquals(after.texts, runBlocking { database.canvasDao().elements(1L).map { it.toModel() } })
                composeRule.runOnUiThread { assertTrue(board.undo()) }
                assertEquals(initial, board.snapshot())
                assertFalse("一回のUndo", board.canUndo)
                composeRule.runOnUiThread { assertTrue(board.redo()) }
                assertEquals(after, board.snapshot())
                assertFalse(board.canRedo)
            }
        }
    }

    private fun withBoard(block: Harness.() -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val seed = CanvasDatabase.open(context)
        runBlocking {
            if (seed.canvasDao().board(1L) == null) seed.canvasDao().putBoard(BoardRow())
            seed.canvasDao().replaceAll(1L, listOf(moving, fixed).map { TextElementRow.fromModel(1L, it) },
                emptyList(), emptyList())
        }
        seed.close()
        showBoardOneAtStartup(context)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        val database = CanvasDatabase.open(context)
        try {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithContentDescription(moving.text).fetchSemanticsNodes().isNotEmpty()
            }
            lateinit var sessions: BoardSessionViewModel
            scenario.onActivity { sessions = ViewModelProvider(it)[BoardSessionViewModel::class.java] }
            val harness = Harness(scenario, sessions, database)
            sessions.setSaveOperation { id, snapshot ->
                harness.saves.incrementAndGet()
                CanvasStore.get(context).save(id, snapshot)
            }
            harness.block()
        } finally {
            composeRule.mainClock.autoAdvance = true
            scenario.close()
            database.close()
        }
    }

    @Test fun stationaryPointerPreservesOffsetStopsInCenterAndCommitsOnce() = withBoard { stationaryDrag() }
    @Test fun nativeCancelDiscardsPreviewWithoutSavingOrHistory() = withBoard { stationaryDrag(cancel = true) }
}
