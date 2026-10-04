package com.thinkcanvas.canvas

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.BoardSaveState
import com.thinkcanvas.BoardSessionViewModel
import com.thinkcanvas.MainActivity
import com.thinkcanvas.data.ArrowElementRow
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.CanvasStore
import com.thinkcanvas.data.InkStrokeRow
import com.thinkcanvas.data.SpatialElementRow
import com.thinkcanvas.data.TextElementRow
import com.thinkcanvas.data.showBoardOneAtStartup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Spec009: actual target ownership, native viewport gestures and durable content are independent observations. */
@RunWith(AndroidJUnit4::class)
class ViewportHistoryInteractionTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val note = TextElement(id = "indicator-note", text = "Find alpha", x = 500f, y = 1200f)
    private val reference = TextElement(id = "indicator-reference", text = "Reference", kind = TextKind.TITLE,
        x = 1300f, y = 1800f)
    private val other = TextElement(id = "indicator-other", text = "Find beta", kind = TextKind.TITLE,
        x = 900f, y = 1400f)
    private val rectangle = ShapeElement(id = "indicator-shape", kind = ShapeKind.RECTANGLE,
        x = 720f, y = 1450f, width = 300f, height = 140f, name = "Selection shape")
    private val region = ShapeElement(id = "indicator-region", kind = ShapeKind.REGION,
        x = 400f, y = 1000f, width = 650f, height = 600f, name = "Cluster")
    private val stroke = InkElement(id = "indicator-ink", kind = InkKind.PEN, strokes = listOf(
        InkStroke(id = "indicator-stroke", startedAt = 1L, endedAt = 101L, inputType = InkInputType.TOUCH,
            points = listOf(InkPoint(600f, 1400f, 0L), InkPoint(780f, 1400f, 100L)))))
    private val arrow = ArrowElement(id = "indicator-arrow", from = ArrowEnd.Free(750f, 1300f),
        to = ArrowEnd.Free(1000f, 1550f))
    private val defaults get() = BoardSnapshot(texts = listOf(note, other, reference),
        shapes = listOf(rectangle), arrows = listOf(arrow), ink = listOf(stroke))
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private inner class Harness(val scenario: ActivityScenario<MainActivity>,
                                val sessions: BoardSessionViewModel, val initial: BoardSnapshot) {
        val board get() = sessions.stateFor(1L, initial)
        val editor get() = sessions.textEditorFor(1L, initial)
        val saves = AtomicInteger()
        val canvas get() = composeRule.onNodeWithContentDescription("キャンバス")
            .fetchSemanticsNode().boundsInWindow
        fun position(label: String) = composeRule.onAllNodesWithContentDescription(label)
            .fetchSemanticsNodes().first().positionInWindow
        fun nodes(prefix: String) = composeRule.onAllNodesWithContentDescription(prefix, substring = true)
            .fetchSemanticsNodes()
        fun indicators() = nodes("へ移動").filter {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { value ->
                value.startsWith("現在の検索結果、") || value.startsWith("選択対象、") } == true }
        fun marker(prefix: String) = nodes(prefix).single()
        fun click(label: String) {
            val candidates = if (label == "戻す" || label == "進む")
                composeRule.onAllNodesWithContentDescription(label).fetchSemanticsNodes() else nodes(label)
            val node = candidates.first { it.config.contains(SemanticsActions.OnClick) }
            composeRule.runOnUiThread { assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()) }
            composeRule.waitForIdle()
            if (label == "戻す" || label == "進む") {
                composeRule.waitUntil(5_000) { sessions.saveStateFor(1L, initial).value == BoardSaveState.Idle }
                composeRule.waitForIdle()
            }
        }
        fun action(label: String, action: String) {
            val node = nodes(label).first { it.config.contains(SemanticsActions.CustomActions) }
            composeRule.runOnUiThread {
                assertTrue(node.config[SemanticsActions.CustomActions].single { it.label == action }.action())
            }
            composeRule.waitForIdle()
        }
        fun saved() = runBlocking { checkNotNull(CanvasStore.get(instrumentation.targetContext).savedBoard(1L)).snapshot }
        fun unchanged() {
            assertEquals(initial, board.snapshot())
            assertEquals(initial, saved())
            assertEquals(0, saves.get())
            assertFalse(board.canUndo)
            assertFalse(board.canRedo)
        }
        fun event(action: Int, points: List<Offset>, downTime: Long) {
            val origin = IntArray(2)
            scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
            val properties = Array(points.size) { index -> MotionEvent.PointerProperties().apply {
                id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(points.size) { index -> MotionEvent.PointerCoords().apply {
                x = points[index].x + origin[0]; y = points[index].y + origin[1]; pressure = 1f; size = 1f
            } }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, points.size,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        fun pan(delta: Offset, start: Offset = Offset(canvas.left + canvas.width * .2f,
                                                       canvas.top + canvas.height * .72f)) {
            val time = SystemClock.uptimeMillis()
            try {
                event(MotionEvent.ACTION_DOWN, listOf(start), time)
                event(MotionEvent.ACTION_MOVE, listOf(start + delta), time)
                event(MotionEvent.ACTION_UP, listOf(start + delta), time)
            } catch (failure: Throwable) {
                event(MotionEvent.ACTION_CANCEL, listOf(start + delta), time)
                throw failure
            }
            composeRule.waitForIdle()
        }
        fun outsideToLeft() {
            // Each swipe stays inside the native window; no synthetic off-window motion.
            repeat(4) { pan(Offset(-canvas.width * .62f, 0f),
                Offset(canvas.left + canvas.width * .8f, canvas.top + canvas.height * .72f)) }
            composeRule.waitUntil(5_000) { indicators().isNotEmpty() }
        }
        fun pinch(factor: Float) {
            val center = Offset(canvas.center.x, canvas.top + canvas.height * .65f)
            val before = listOf(center - Offset(8f, 0f), center + Offset(8f, 0f))
            val after = listOf(center - Offset(8f * factor, 0f), center + Offset(8f * factor, 0f))
            val time = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, listOf(before.first()), time)
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), before, time)
            event(MotionEvent.ACTION_MOVE, after, time)
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), after, time)
            event(MotionEvent.ACTION_UP, listOf(after.first()), time)
            composeRule.waitForIdle()
        }
        fun search(query: String = "Find") {
            click("ボード内を検索")
            composeRule.onNodeWithContentDescription("ボード内を探す").performTextReplacement(query)
            closeSoftKeyboard()
            composeRule.waitForIdle()
            composeRule.waitUntil(5_000) { nodes("1件目、全").isNotEmpty() }
        }
        fun back() {
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()
        }
        fun nextPanAt(point: Offset) {
            val before = position(reference.text)
            pan(Offset(90f, 25f), point)
            val after = position(reference.text)
            assertEquals(before.x + 90f, after.x, 2f)
            assertEquals(before.y + 25f, after.y, 2f)
        }
    }

    private fun withBoard(initial: BoardSnapshot = defaults, block: Harness.() -> Unit) {
        val context = instrumentation.targetContext
        val database = CanvasDatabase.open(context)
        runBlocking {
            if (database.canvasDao().board(1L) == null) database.canvasDao().putBoard(BoardRow())
            database.canvasDao().replaceAll(1L, initial.texts.map { TextElementRow.fromModel(1L, it) },
                initial.shapes.map { SpatialElementRow.fromModel(1L, it) },
                initial.arrows.map { ArrowElementRow.fromModel(1L, it) },
                initial.ink.flatMap { InkStrokeRow.fromModel(1L, it) })
        }
        database.close()
        showBoardOneAtStartup(context)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithContentDescription("キャンバス")
                .fetchSemanticsNodes().isNotEmpty() }
            composeRule.waitForIdle()
            lateinit var sessions: BoardSessionViewModel
            scenario.onActivity { sessions = ViewModelProvider(it)[BoardSessionViewModel::class.java] }
            val harness = Harness(scenario, sessions, initial)
            sessions.setSaveOperation { id, snapshot ->
                harness.saves.incrementAndGet()
                CanvasStore.get(context).save(id, snapshot)
            }
            harness.block()
        } finally { composeRule.mainClock.autoAdvance = true; scenario.close() }
    }

    private val Harness.navigation get() = sessions.viewportHistoryFor(1L, initial)
    private fun sameFocus(expected: ViewportFocus?, actual: ViewportFocus?) {
        assertNotNull(expected); assertNotNull(actual)
        assertEquals(expected!!.centerX, actual!!.centerX, 3f)
        assertEquals(expected.centerY, actual.centerY, 3f)
        assertEquals(expected.scale, actual.scale, .005f)
    }

    @Test fun searchCyclePanPinchAndIndicatorRestoreWithoutContentOrSave() = withBoard {
        val start = navigation.focus()
        search()
        val found = navigation.focus()
        assertNotEquals(start, found)
        click("前の視点へ戻る")
        sameFocus(start, navigation.focus())
        click("次の視点へ進む")
        sameFocus(found, navigation.focus())
        click("倍率を切り替える、")
        val cycle = navigation.focus()
        click("前の視点へ戻る")
        sameFocus(found, navigation.focus())
        click("次の視点へ進む")
        sameFocus(cycle, navigation.focus())
        pan(Offset(-canvas.width * .4f, 30f))
        val panned = navigation.focus()
        click("前の視点へ戻る")
        sameFocus(cycle, navigation.focus())
        click("次の視点へ進む")
        sameFocus(panned, navigation.focus())
        pinch(1.4f)
        click("前の視点へ戻る")
        sameFocus(panned, navigation.focus())
        outsideToLeft()
        val beforeIndicator = navigation.focus()
        click("現在の検索結果、")
        click("前の視点へ戻る")
        sameFocus(beforeIndicator, navigation.focus())
        assertTrue(nodes("1件目、全2件").isNotEmpty())
        unchanged()
    }

    @Test fun blankDoubleTapAndRegionFitHaveOneEntry() {
        withBoard {
            val start = navigation.focus()
            val point = Offset(canvas.left + canvas.width * .18f, canvas.top + canvas.height * .6f)
            repeat(2) {
                val time = SystemClock.uptimeMillis()
                event(MotionEvent.ACTION_DOWN, listOf(point), time)
                event(MotionEvent.ACTION_UP, listOf(point), time)
            }
            composeRule.waitForIdle()
            assertNotEquals(start, navigation.focus())
            click("前の視点へ戻る")
            sameFocus(start, navigation.focus())
            assertFalse(navigation.canBack)
            unchanged()
        }
        withBoard(defaults.copy(shapes = listOf(rectangle, region))) {
            val start = navigation.focus()
            pinch(.02f)
            val far = navigation.focus()
            click("囲み: Cluster")
            click("前の視点へ戻る")
            sameFocus(far, navigation.focus())
            click("前の視点へ戻る")
            sameFocus(start, navigation.focus())
            assertFalse(navigation.canBack)
            unchanged()
        }
    }

    @Test fun visibleAndOffscreenUndoRedoFocusWithoutExtraContentHistoryOrSave() = withBoard {
        search("Find alpha")
        val visible = navigation.focus()
        composeRule.runOnUiThread { assertTrue(board.edit(note.id, "Changed alpha", note.kind, note.color)) }
        click("戻す")
        sameFocus(visible, navigation.focus())
        assertEquals(initial, board.snapshot())
        assertEquals(1, saves.get())
        click("進む")
        assertEquals("Changed alpha", board.elements.first { it.id == note.id }.text)
        assertEquals(2, saves.get())
        repeat(4) { pan(Offset(-canvas.width * .62f, 0f),
            Offset(canvas.left + canvas.width * .8f, canvas.top + canvas.height * .72f)) }
        val distant = navigation.focus()
        click("戻す")
        assertEquals(initial, board.snapshot())
        assertEquals(3, saves.get())
        assertNotEquals(distant, navigation.focus())
        assertTrue(canvas.contains(position(note.text)))
        click("前の視点へ戻る")
        sameFocus(distant, navigation.focus())
        assertEquals(initial, board.snapshot())
        assertTrue(board.canRedo)
        assertEquals(3, saves.get())
        pan(Offset(50f, 20f))
        assertTrue(board.canRedo)
        click("進む")
        assertEquals("Changed alpha", board.elements.first { it.id == note.id }.text)
        assertEquals(4, saves.get())
    }

    @Test fun recreationAndNativeCancelPreserveSessionAndNextGesture() = withBoard {
        pan(Offset(100f, 30f))
        val panned = navigation.focus()
        val stale = marker("前の視点へ戻る").config[SemanticsActions.OnClick].action!!
        scenario.recreate()
        composeRule.waitForIdle()
        composeRule.runOnUiThread { stale.invoke() }
        sameFocus(panned, navigation.focus())
        assertTrue(navigation.canBack)
        click("前の視点へ戻る")
        assertFalse(navigation.canBack)
        val point = Offset(canvas.left + canvas.width * .2f, canvas.top + canvas.height * .6f)
        val time = SystemClock.uptimeMillis()
        event(MotionEvent.ACTION_DOWN, listOf(point), time)
        event(MotionEvent.ACTION_MOVE, listOf(point + Offset(80f, 20f)), time)
        event(MotionEvent.ACTION_CANCEL, listOf(point + Offset(80f, 20f)), time)
        composeRule.waitForIdle()
        assertFalse(navigation.canBack)
        val canceled = navigation.focus()
        pan(Offset(70f, 20f), point)
        click("前の視点へ戻る")
        sameFocus(canceled, navigation.focus())
        unchanged()
    }

    @Test fun staleViewActionRejectsSaveEditorToolAndNextGestureUsesCurrentState() = withBoard {
        pan(Offset(100f, 30f))
        val button = marker("前の視点へ戻る")
        val density = instrumentation.targetContext.resources.displayMetrics.density
        assertTrue(button.boundsInWindow.width >= 48f * density - 1f)
        assertTrue(button.boundsInWindow.height >= 48f * density - 1f)
        assertTrue(marker("次の視点へ進む").config.contains(SemanticsProperties.Disabled))
        val stale = button.config[SemanticsActions.OnClick].action!!
        val camera = navigation.focus()
        val completion = CompletableDeferred<Unit>()
        sessions.setSaveOperation { _, _ -> completion }
        scenario.onActivity {
            sessions.requestSave(1L, initial)
            stale.invoke()
        }
        composeRule.waitForIdle()
        assertTrue(nodes("前の視点へ戻る").isEmpty())
        sameFocus(camera, navigation.focus())
        completion.completeExceptionally(IllegalStateException("bounded fixture"))
        composeRule.waitUntil(5_000) { sessions.saveStateFor(1L, initial).value is BoardSaveState.Failed }
        composeRule.runOnUiThread { stale.invoke() }
        sameFocus(camera, navigation.focus())
        sessions.setSaveOperation { _, _ -> CompletableDeferred(Unit) }
        scenario.onActivity { sessions.retrySave(1L) }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { sessions.saveStateFor(1L, initial).value == BoardSaveState.Idle }
        scenario.onActivity {
            editor.pendingDraftAcknowledgement.value = sessions.requestSave(1L, initial)
            assertEquals(BoardSaveState.Idle, sessions.saveStateFor(1L, initial).value)
            stale.invoke()
            sameFocus(camera, navigation.focus())
        }
        composeRule.waitForIdle()
        assertNull(editor.pendingDraftAcknowledgement.value)
        composeRule.runOnUiThread { editor.draft.value = Draft(null, 800f, 1600f, "Draft"); stale.invoke() }
        composeRule.waitForIdle()
        sameFocus(camera, navigation.focus())
        assertTrue(nodes("前の視点へ戻る").isEmpty())
        composeRule.runOnUiThread { editor.draft.value = null }
        closeSoftKeyboard()
        composeRule.waitForIdle()
        click("図形ツールを開く")
        composeRule.runOnUiThread { stale.invoke() }
        sameFocus(camera, navigation.focus())
        assertTrue(nodes("前の視点へ戻る").isEmpty())
        back()
        nextPanAt(Offset(canvas.left + canvas.width * .2f, canvas.top + canvas.height * .72f))
        unchanged()
    }
    @Test fun everyFamilyDeletionAndMultipleUndoTargetsUseOneDisplayNavigation() {
        val families = listOf(note.id, rectangle.id, arrow.id, stroke.id)
        for (id in families) withBoard {
            composeRule.runOnUiThread { assertTrue(board.delete(setOf(id))) }
            val visible = navigation.focus()
            click("戻す")
            sameFocus(visible, navigation.focus())
            assertEquals(initial, board.snapshot())
            assertEquals(1, saves.get())
            click("進む")
            assertFalse("対象をRedoで削除した後の内容", board.snapshot() == initial)
            sameFocus(visible, navigation.focus())
            assertEquals(2, saves.get())
            repeat(4) { pan(Offset(-canvas.width * .62f, 0f),
                Offset(canvas.left + canvas.width * .8f, canvas.top + canvas.height * .72f)) }
            val distant = navigation.focus()
            click("戻す")
            assertEquals(initial, board.snapshot())
            assertEquals(3, saves.get())
            assertNotEquals(distant, navigation.focus())
            click("前の視点へ戻る")
            sameFocus(distant, navigation.focus())
            assertTrue(board.canRedo)
            assertEquals(initial, board.snapshot())
            assertEquals(3, saves.get())
            click("進む")
            assertNotEquals(initial, board.snapshot())
            assertEquals(4, saves.get())
            assertNotEquals(distant, navigation.focus())
            click("前の視点へ戻る")
            sameFocus(distant, navigation.focus())
            assertEquals(4, saves.get())
        }
        withBoard {
            val ids = setOf(rectangle.id, stroke.id)
            composeRule.runOnUiThread { assertTrue(board.moveSelection(ids, 90f, 40f)) }
            repeat(4) { pan(Offset(-canvas.width * .62f, 0f),
                Offset(canvas.left + canvas.width * .8f, canvas.top + canvas.height * .72f)) }
            val distant = navigation.focus()
            click("戻す")
            assertEquals(initial, board.snapshot())
            val geometry = initial.resolveRenderedGeometry(emptyMap(), navigation.viewportState.value.scale,
                instrumentation.targetContext.resources.displayMetrics.density)
            for (id in ids) {
                val bounds = geometry.bounds(id)!!
                val view = navigation.viewportState.value
                val (left, top) = view.worldToScreen(bounds.left, bounds.top)
                val (right, bottom) = view.worldToScreen(bounds.right, bounds.bottom)
                assertTrue(left >= -2f && top >= -2f && right <= canvas.width + 2f && bottom <= canvas.height + 2f)
            }
            click("前の視点へ戻る")
            sameFocus(distant, navigation.focus())
            assertEquals(initial, board.snapshot())
            assertEquals(1, saves.get())
        }
    }
    @Test fun activeSearchUndoPreservesResultPositionAndOneChangedTargetNavigation() = withBoard {
        search()
        composeRule.runOnUiThread { assertTrue(board.edit(note.id, "Find revised", note.kind, note.color)) }
        composeRule.waitForIdle()
        click("次の検索結果")
        repeat(4) { pan(Offset(-canvas.width * .62f, 0f),
            Offset(canvas.left + canvas.width * .8f, canvas.top + canvas.height * .72f)) }
        val distant = navigation.focus()
        assertTrue(nodes("2件目、全2件").isNotEmpty())
        click("戻す")
        assertEquals(initial, board.snapshot())
        assertTrue(nodes("2件目、全2件").isNotEmpty())
        assertTrue(canvas.contains(position(note.text)))
        assertEquals(1, saves.get())
        click("前の視点へ戻る")
        sameFocus(distant, navigation.focus())
        assertEquals(initial, board.snapshot())
        assertTrue(board.canRedo)
        assertEquals(1, saves.get())
    }

    @Test fun unchangedOffscreenAttachedArrowDoesNotMoveVisibleColorUndo() {
        val attached = ArrowElement(id = "long-attached", from = ArrowEnd.Attached(note.id, 1f, .5f),
            to = ArrowEnd.Free(6000f, 1600f))
        withBoard(defaults.copy(arrows = listOf(attached))) {
            search("Find alpha")
            val visible = navigation.focus()
            composeRule.runOnUiThread {
                assertTrue(board.edit(note.id, note.text, note.kind, TextColor.VERMILION))
            }
            composeRule.waitForIdle()
            click("戻す")
            sameFocus(visible, navigation.focus())
            assertEquals(initial, board.snapshot())
            assertEquals(1, saves.get())
        }
    }

    @Test fun moveAutoPanCancelAddsNoEntryAndNextPanHasOneEntry() = withBoard {
        click(note.text)
        val start = nodes("要素を移動").single().boundsInWindow.center
        val edge = Offset(canvas.right - 3f, canvas.center.y)
        val time = SystemClock.uptimeMillis()
        var finished = false
        composeRule.mainClock.autoAdvance = false
        try {
            event(MotionEvent.ACTION_DOWN, listOf(start), time)
            repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
            event(MotionEvent.ACTION_MOVE, listOf(edge), time)
            repeat(90) { composeRule.mainClock.advanceTimeByFrame() }
            assertFalse(navigation.canBack)
            assertTrue(nodes("前の視点へ戻る").isEmpty())
            assertEquals(initial, board.snapshot())
            event(MotionEvent.ACTION_CANCEL, listOf(edge), time)
            finished = true
            repeat(4) { composeRule.mainClock.advanceTimeByFrame() }
        } finally {
            if (!finished) event(MotionEvent.ACTION_CANCEL, listOf(edge), time)
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        assertFalse(navigation.canBack)
        val canceled = navigation.focus()
        pan(Offset(75f, 20f))
        click("前の視点へ戻る")
        sameFocus(canceled, navigation.focus())
        assertFalse(navigation.canBack)
        unchanged()
    }
}
