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
class OffscreenIndicatorsTest {
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
            val node = nodes(label).first { it.config.contains(SemanticsActions.OnClick) }
            composeRule.runOnUiThread { assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()) }
            composeRule.waitForIdle()
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

    @Test fun searchIndicatorReturnsToCurrentResultWithoutMutation() = withBoard {
        search()
        assertEquals(0, indicators().size)
        outsideToLeft()
        val marker = marker("現在の検索結果、")
        assertTrue(marker.config[SemanticsProperties.ContentDescription].single().contains(note.text))
        assertEquals(1, indicators().size)
        assertTrue(marker.boundsInWindow.center.x < canvas.center.x)
        val density = instrumentation.targetContext.resources.displayMetrics.density
        assertTrue(marker.boundsInWindow.width >= 48f * density - 1f)
        assertTrue(marker.boundsInWindow.height >= 48f * density - 1f)
        val old = marker.boundsInWindow.center
        val time = SystemClock.uptimeMillis()
        event(MotionEvent.ACTION_DOWN, listOf(old), time)
        event(MotionEvent.ACTION_UP, listOf(old), time)
        composeRule.waitForIdle()
        assertEquals(0, indicators().size)
        assertTrue(canvas.contains(position(note.text)))
        assertTrue(nodes("1件目、全2件").isNotEmpty())
        unchanged()
    }

    @Test fun queryAndResultChangesReplaceTargetAndCloseRemovesHit() = withBoard {
        search()
        outsideToLeft()
        click("次の検索結果")
        assertEquals(0, indicators().size)
        outsideToLeft()
        assertTrue(marker("現在の検索結果、").config[SemanticsProperties.ContentDescription].single().contains(other.text))
        composeRule.onNodeWithContentDescription("ボード内を探す").performTextReplacement("Reference")
        closeSoftKeyboard()
        composeRule.waitForIdle()
        assertEquals(0, indicators().size)
        outsideToLeft()
        val old = marker("現在の検索結果、").boundsInWindow.center
        click("検索を閉じる")
        assertEquals(0, indicators().size)
        nextPanAt(old)
        unchanged()
    }

    @Test fun singleSelectionFamiliesNavigateWithoutHistoryOrSaving() {
        val cases = listOf(note.text, "四角: Selection shape", "囲み: Cluster", "ペンの線", "矢印")
        for (label in cases) withBoard(defaults.copy(shapes = listOf(rectangle, region))) {
            click(label)
            outsideToLeft()
            assertEquals(1, indicators().size)
            click("選択対象、")
            assertEquals(0, indicators().size)
            val selected = nodes(label).any { it.config.getOrNull(SemanticsProperties.StateDescription) == "選択中" }
            assertTrue("selection survives $label", selected)
            unchanged()
        }
    }

    @Test fun multiSelectionHasOneIndicatorAndRetainsRelativeGeometry() = withBoard {
        click(note.text)
        action("四角: Selection shape", "選択に追加")
        action("矢印", "選択に追加")
        outsideToLeft()
        assertEquals(1, indicators().size)
        assertTrue(marker("選択対象、").config[SemanticsProperties.ContentDescription].single().contains("3個"))
        click("選択対象、")
        assertEquals(0, indicators().size)
        for (label in listOf(note.text, "四角: Selection shape", "矢印")) {
            assertTrue(nodes(label).any { it.config.getOrNull(SemanticsProperties.StateDescription) == "選択中" })
        }
        unchanged()
    }

    @Test fun searchAndSelectionDeduplicateAndKeepStableAccessibleOrder() = withBoard {
        search()
        click(note.text)
        outsideToLeft()
        assertEquals(1, indicators().size)
        assertTrue(indicators().single().config[SemanticsProperties.ContentDescription].single().startsWith("現在の検索結果、"))
        click("現在の検索結果、")
        click(other.text)
        outsideToLeft()
        assertEquals(2, indicators().size)
        val search = marker("現在の検索結果、")
        val selection = marker("選択対象、")
        assertEquals(0f, search.config[SemanticsProperties.TraversalIndex], 0f)
        assertEquals(1f, selection.config[SemanticsProperties.TraversalIndex], 0f)
        assertFalse(search.boundsInWindow.overlaps(selection.boundsInWindow))
        assertNotNull(search.config[SemanticsActions.OnClick].label)
        assertNotNull(selection.config[SemanticsActions.OnClick].label)
        click("選択対象、")
        assertTrue(nodes(other.text).any { it.config.getOrNull(SemanticsProperties.StateDescription) == "選択中" })
        unchanged()
    }

    @Test fun nativePinchAndPanUpdateIndicatorsAcrossSemanticTiers() = withBoard {
        search()
        outsideToLeft()
        pinch(.02f)
        assertTrue(nodes("倍率を切り替える、15%").isNotEmpty())
        // Bring the current result back through its accessibility navigation action.
        if (nodes("現在の検索結果、").isEmpty()) outsideToLeft()
        click("現在の検索結果、")
        assertEquals(0, indicators().size)
        assertTrue(nodes(note.text).isNotEmpty())
        pinch(40f)
        assertTrue(nodes("倍率を切り替える、300%").isNotEmpty())
        outsideToLeft()
        val before = marker("現在の検索結果、").boundsInWindow.center
        pan(Offset(0f, -canvas.height * .35f), Offset(canvas.center.x, canvas.top + canvas.height * .7f))
        val after = marker("現在の検索結果、").boundsInWindow.center
        assertNotEquals(before, after)
        click("現在の検索結果、")
        assertEquals(0, indicators().size)
        unchanged()
    }

    @Test fun backClearsSelectionAndOldHitAllowsNativePan() = withBoard {
        click(note.text)
        outsideToLeft()
        val old = marker("選択対象、").boundsInWindow.center
        back()
        assertEquals(0, indicators().size)
        nextPanAt(old)
        unchanged()
    }

    @Test fun editorAndCreationToolSuppressIndicatorsAndRestoreOnlyCurrentTargets() = withBoard {
        click(note.text)
        outsideToLeft()
        val oldAction = marker("選択対象、").config[SemanticsActions.OnClick].action!!
        val camera = position(reference.text)
        composeRule.runOnUiThread {
            editor.draft.value = Draft(null, 800f, 1600f, "Uncommitted draft")
            oldAction.invoke()
        }
        composeRule.waitForIdle()
        assertEquals(0, indicators().size)
        assertEquals(camera, position(reference.text))
        composeRule.runOnUiThread { editor.draft.value = null }
        closeSoftKeyboard()
        composeRule.waitForIdle()
        assertEquals(1, indicators().size)
        click("図形ツールを開く")
        assertEquals(0, indicators().size)
        back()
        assertEquals(1, indicators().size)
        click("図形ツールを開く")
        click("四角")
        assertEquals(0, indicators().size)
        back()
        composeRule.waitForIdle()
        assertEquals(1, indicators().size)
        unchanged()
    }

    @Test fun saveBlockRejectsStaleNavigationBeforeRecomposition() = withBoard {
        click(note.text)
        outsideToLeft()
        val staleAction = marker("選択対象、").config[SemanticsActions.OnClick].action!!
        val camera = position(reference.text)
        val completion = CompletableDeferred<Unit>()
        sessions.setSaveOperation { _, _ -> completion }
        scenario.onActivity {
            sessions.requestSave(1L, initial)
            staleAction.invoke()
        }
        composeRule.waitForIdle()
        assertEquals(0, indicators().size)
        assertEquals(camera, position(reference.text))
        completion.completeExceptionally(IllegalStateException("bounded save fixture"))
        composeRule.waitUntil(5_000) { sessions.saveStateFor(1L, initial).value is BoardSaveState.Failed }
        assertEquals(0, indicators().size)
        assertEquals(camera, position(reference.text))
        unchanged()
    }

    @Test fun recreationDoesNotRestoreStaleTargetsOrHitRegions() = withBoard {
        search()
        outsideToLeft()
        val old = marker("現在の検索結果、").boundsInWindow.center
        scenario.recreate()
        composeRule.waitUntil(10_000) { nodes("ボード内を検索").isNotEmpty() }
        composeRule.waitForIdle()
        assertEquals(0, indicators().size)
        nextPanAt(old)
        unchanged()
    }

    @Test fun movePreviewSuppressesIndicatorUntilNativeCancelReleasesOwner() = withBoard {
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
            assertEquals(0, indicators().size)
            assertEquals(initial, board.snapshot())
            event(MotionEvent.ACTION_CANCEL, listOf(edge), time)
            finished = true
            repeat(4) { composeRule.mainClock.advanceTimeByFrame() }
        } finally {
            if (!finished) event(MotionEvent.ACTION_CANCEL, listOf(edge), time)
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        assertEquals(1, indicators().size)
        click("選択対象、")
        assertEquals(0, indicators().size)
        unchanged()
    }

    @Test fun pendingAcknowledgementAndRemovedTargetRejectSameTurnStaleActions() {
        for (removed in listOf(false, true)) withBoard {
            click(note.text)
            outsideToLeft()
            val stale = marker("選択対象、").config[SemanticsActions.OnClick].action!!
            val camera = position(reference.text)
            sessions.setSaveOperation { _, _ -> CompletableDeferred(Unit) }
            scenario.onActivity {
                if (removed) assertTrue(board.delete(setOf(note.id))) else {
                    val acknowledgement = sessions.requestSave(1L, initial)
                    assertEquals(BoardSaveState.Idle, sessions.saveStateFor(1L, initial).value)
                    editor.pendingDraftAcknowledgement.value = acknowledgement
                    assertNotNull(editor.pendingDraftAcknowledgement.value)
                }
                stale.invoke()
            }
            composeRule.waitForIdle()
            assertEquals(camera, position(reference.text))
            assertEquals(if (removed) 0 else 1, indicators().size)
            assertEquals(0, saves.get())
            assertEquals(initial, saved())
            if (removed) {
                composeRule.runOnUiThread { assertTrue(board.undo()) }
                assertEquals(initial, board.snapshot())
            } else {
                assertNull(editor.pendingDraftAcknowledgement.value)
                click("選択対象、")
                assertEquals(0, indicators().size)
                unchanged()
            }
        }
    }
}
