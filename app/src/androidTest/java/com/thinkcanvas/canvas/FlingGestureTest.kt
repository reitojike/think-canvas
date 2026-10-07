package com.thinkcanvas.canvas

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
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
import com.thinkcanvas.test.PrSmoke
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Spec015: native速度、release後のcamera、表示履歴、durable contentを独立して観測する。 */
@RunWith(AndroidJUnit4::class)
class FlingGestureTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val initial = BoardSnapshot(texts = listOf(
        TextElement(id = "fling-a", text = "Fling anchor", kind = TextKind.TITLE, x = 200f, y = 200f),
        TextElement(id = "fling-b", text = "Fling reference", kind = TextKind.TITLE, x = 800f, y = 400f),
    ))

    private inner class Harness(val scenario: ActivityScenario<MainActivity>, val sessions: BoardSessionViewModel) {
        val board get() = sessions.stateFor(1L, initial)
        val navigation get() = sessions.viewportHistoryFor(1L, initial)
        val editor get() = sessions.textEditorFor(1L, initial)
        val saves = AtomicInteger()
        val canvas get() = composeRule.onNodeWithContentDescription("キャンバス").fetchSemanticsNode().boundsInWindow
        fun ready() {
            composeRule.waitUntil(10_000) {
                var focused = false
                scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
                focused && sessions.saveStateFor(1L, initial).value == BoardSaveState.Idle &&
                    composeRule.onAllNodesWithContentDescription("キャンバス").fetchSemanticsNodes().isNotEmpty() &&
                    navigation.focus() != null
            }
            composeRule.waitForIdle()
        }
        fun camera(): Viewport {
            lateinit var value: Viewport
            composeRule.runOnUiThread { value = navigation.viewportState.value }
            return value
        }
        fun unchanged() {
            assertEquals(initial, board.snapshot())
            assertEquals(initial, runBlocking { CanvasStore.get(instrumentation.targetContext).savedBoard(1L)!!.snapshot })
            assertEquals(0, saves.get())
            assertFalse(board.canUndo)
            assertFalse(board.canRedo)
        }
        fun event(action: Int, points: List<Offset>, downTime: Long, time: Long) {
            val origin = IntArray(2)
            scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
            val properties = Array(points.size) { index -> MotionEvent.PointerProperties().apply {
                id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(points.size) { index -> MotionEvent.PointerCoords().apply {
                x = points[index].x + origin[0]; y = points[index].y + origin[1]; pressure = 1f; size = 1f
            } }
            val event = MotionEvent.obtain(downTime, time, action, points.size, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue("native action=$action down=$downTime time=$time now=${SystemClock.uptimeMillis()}",
                instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            composeRule.runOnUiThread {} // native event処理のbarrier。animation clockは進めない。
        }
        fun pan(duration: Long = 80L, pause: Long = 0L, canceled: Boolean = false): Viewport {
            val bounds = canvas
            val from = Offset(bounds.left + bounds.width * .2f, bounds.top + bounds.height * .65f)
            val delta = Offset(bounds.width * .35f, 0f)
            // 過去のuptimeでnative traceを再生し、host/renderの遅延を指の速度に混ぜない。
            // historyのnativeクリック等より前へ時刻を戻さない。
            Thread.sleep(duration + pause + 60L)
            val time = SystemClock.uptimeMillis() - duration - pause
            var last = from
            var released = false
            try {
                event(MotionEvent.ACTION_DOWN, listOf(from), time, time)
                for (step in 1..8) {
                    last = from + delta * (step / 8f)
                    event(MotionEvent.ACTION_MOVE, listOf(last), time, time + duration * step / 8)
                    if (step != 8) composeRule.mainClock.advanceTimeByFrame()
                }
                event(if (canceled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,
                    listOf(last), time, time + duration + pause)
                released = true
                return camera()
            } finally {
                if (!released) runCatching {
                    event(MotionEvent.ACTION_CANCEL, listOf(last), time, SystemClock.uptimeMillis())
                }
            }
        }
        fun advance(ms: Long) { composeRule.mainClock.advanceTimeBy(ms); composeRule.waitForIdle() }
        fun history(label: String) {
            composeRule.onNodeWithContentDescription(label).performClick()
            advance(600L)
        }
        fun stopWithTouch(): Viewport {
            val bounds = canvas
            val point = Offset(bounds.center.x, bounds.top + bounds.height * .6f)
            val time = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, listOf(point), time, time)
            val stopped = camera()
            advance(300L)
            assertEquals(stopped, camera())
            event(MotionEvent.ACTION_CANCEL, listOf(point), time, SystemClock.uptimeMillis())
            return stopped
        }
    }

    private fun withBoard(block: Harness.() -> Unit) {
        val context = instrumentation.targetContext
        val database = CanvasDatabase.open(context)
        runBlocking {
            if (database.canvasDao().board(1L) == null) database.canvasDao().putBoard(BoardRow())
            database.canvasDao().replaceAll(1L, initial.texts.map { TextElementRow.fromModel(1L, it) },
                emptyList(), emptyList())
        }
        database.close()
        showBoardOneAtStartup(context)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithContentDescription("Fling anchor")
                .fetchSemanticsNodes().isNotEmpty() }
            lateinit var sessions: BoardSessionViewModel
            scenario.onActivity { sessions = ViewModelProvider(it)[BoardSessionViewModel::class.java] }
            val harness = Harness(scenario, sessions)
            harness.ready()
            sessions.setSaveOperation { id, snapshot ->
                harness.saves.incrementAndGet()
                CanvasStore.get(context).save(id, snapshot)
            }
            composeRule.runOnUiThread { harness.navigation.viewportState.value = Viewport(.8f, 100f, 100f) }
            composeRule.waitForIdle()
            composeRule.mainClock.autoAdvance = false
            harness.block()
        } finally { composeRule.mainClock.autoAdvance = true; scenario.close() }
    }

    @PrSmoke
    @Test fun fastPanContinuesAndUsesOneHistoryBoundary() = withBoard {
        val before = camera()
        val release = pan()
        advance(120L)
        assertTrue("release=$release later=${camera()}", camera().panX > release.panX + 3f)
        advance(2_000L)
        val stopped = camera()
        assertTrue(navigation.canBack)
        history("前の視点へ戻る")
        assertEquals(before.panX, camera().panX, 1f)
        assertEquals(before.panY, camera().panY, 1f)
        assertFalse(navigation.canBack)
        history("次の視点へ進む")
        assertEquals(stopped.panX, camera().panX, 1f)
        assertFalse(navigation.canForward)
        unchanged()
    }

    @Test fun slowPanAndPausedReleaseStopExactly() = withBoard {
        val slow = pan(duration = 1_000L)
        advance(400L)
        assertEquals(slow, camera())
        val paused = pan(pause = 180L)
        advance(400L)
        assertEquals(paused, camera())
        unchanged()
    }

    @Test fun nativeCancelNeverStartsFlingOrAddsHistory() = withBoard {
        val release = pan(canceled = true)
        advance(1_000L)
        assertEquals(release, camera())
        assertFalse(navigation.canBack)
        unchanged()
    }

    @PrSmoke
    @Test fun disabledSystemAnimationsKeepStandardTouchDecay() {
        val resolver = instrumentation.targetContext.contentResolver
        val original = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            assertTrue(Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f))
            withBoard {
                val released = pan()
                advance(48L)
                val middle = camera()
                assertTrue(middle.panX > released.panX)
                advance(96L)
                assertTrue("scale0 must not jump directly to target", camera().panX > middle.panX + 3f)
                advance(2_000L)
                unchanged()
            }
        } finally {
            Settings.Global.putString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, original)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun newTouchStopsAndNextSlowPanOwnsCamera() = withBoard {
        val before = camera()
        pan()
        advance(80L)
        val stopped = stopWithTouch()
        history("前の視点へ戻る")
        assertEquals(before.panX, camera().panX, 1f)
        assertFalse(navigation.canBack)
        history("次の視点へ進む")
        assertEquals(stopped.panX, camera().panX, 1f)
        val slow = pan(duration = 1_000L)
        advance(400L)
        assertEquals(slow, camera())
        unchanged()
    }

    @PrSmoke
    @Test fun pinchStopsOldFlingAndOwnsZoom() = withBoard {
        pan()
        advance(80L)
        val bounds = canvas
        val center = Offset(bounds.center.x, bounds.top + bounds.height * .6f)
        val before = listOf(center - Offset(80f, 0f), center + Offset(80f, 0f))
        val after = listOf(center - Offset(110f, 0f), center + Offset(110f, 0f))
        val time = SystemClock.uptimeMillis()
        event(MotionEvent.ACTION_DOWN, listOf(before[0]), time, time)
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), before, time, time + 10)
        event(MotionEvent.ACTION_MOVE, after, time, time + 20)
        composeRule.mainClock.advanceTimeByFrame()
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), after, time, time + 30)
        event(MotionEvent.ACTION_UP, listOf(after[0]), time, time + 40)
        val zoomed = camera()
        assertTrue(zoomed.scale > .8f)
        advance(400L)
        assertEquals(zoomed, camera())
        unchanged()
    }

    @Test fun toolAndEditorAdmissionRejectQueuedFrames() = withBoard {
        pan()
        advance(80L)
        val tool = composeRule.onNodeWithContentDescription("図形ツールを開く").fetchSemanticsNode().boundsInWindow.center
        val time = SystemClock.uptimeMillis()
        event(MotionEvent.ACTION_DOWN, listOf(tool), time, time)
        val stopped = camera()
        advance(32L)
        event(MotionEvent.ACTION_UP, listOf(tool), time, SystemClock.uptimeMillis())
        advance(400L)
        assertEquals(stopped, camera())
        composeRule.onNodeWithContentDescription("四角").assertExists()
        composeRule.onNodeWithContentDescription("図形ツールを開く").performClick()
        advance(32L)
        pan()
        advance(80L)
        val focus = navigation.focus()!!
        val visible = camera().screenToWorld(canvas.width * .2f, canvas.height * .15f)
        composeRule.runOnUiThread { editor.draft.value = Draft(null, visible.first, visible.second) }
        advance(500L)
        assertEquals(focus.centerX, navigation.focus()!!.centerX, 1f)
        unchanged()
    }

    @Test fun backAndRecreationStopCompletedPanContinuation() = withBoard {
        pan()
        advance(80L)
        scenario.recreate()
        composeRule.mainClock.autoAdvance = true
        ready()
        val recreated = camera()
        Thread.sleep(300L)
        composeRule.waitForIdle()
        assertEquals(recreated, camera())
        assertTrue(navigation.canBack)
        composeRule.mainClock.autoAdvance = false
        pan()
        advance(80L)
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        val stopped = camera()
        advance(400L)
        assertEquals(stopped, camera())
        assertTrue(composeRule.onAllNodesWithContentDescription("キャンバス").fetchSemanticsNodes().isEmpty())
        unchanged()
    }
}
