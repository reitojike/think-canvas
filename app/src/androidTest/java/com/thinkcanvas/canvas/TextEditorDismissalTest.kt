package com.thinkcanvas.canvas

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.BoardSaveState
import com.thinkcanvas.BoardSessionViewModel
import com.thinkcanvas.MainActivity
import com.thinkcanvas.R
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.CanvasStore
import com.thinkcanvas.data.TextElementRow
import com.thinkcanvas.data.showBoardOneAtStartup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #71: user-initiated outside tap, transient focus and save continuation. */
@RunWith(AndroidJUnit4::class)
class TextEditorDismissalTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val original = TextElement(id = "outside-original", text = "Original note",
        x = 200f, y = 300f)
    private val newEditor = "新しいテキスト"
    private val existingEditor = "テキストを編集"

    private inner class Harness(
        val scenario: ActivityScenario<MainActivity>,
        val database: CanvasDatabase,
        val sessions: BoardSessionViewModel,
        private val composeTouch: Boolean,
    ) {
        val board get() = sessions.stateFor(1L, BoardSnapshot())
        fun rows() = runBlocking { database.canvasDao().elements(1L) }
        fun point(x: Float, y: Float): Offset {
            val origin = IntArray(2)
            scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
            val bounds = composeRule.onNodeWithContentDescription("キャンバス")
                .fetchSemanticsNode().boundsInWindow
            return Offset(origin[0] + bounds.left + bounds.width * x,
                origin[1] + bounds.top + bounds.height * y)
        }
        fun center(label: String): Offset {
            val origin = IntArray(2)
            scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
            val bounds = composeRule.onNodeWithContentDescription(label)
                .fetchSemanticsNode().boundsInWindow
            return bounds.center + Offset(origin[0].toFloat(), origin[1].toFloat())
        }
        fun startNew(text: String = "", y: Float = .23f) {
            tap(point(.1f, y))
            awaitEditor(newEditor)
            if (text.isNotEmpty()) composeRule.onNodeWithContentDescription(newEditor)
                .performTextReplacement(text)
            composeRule.waitForIdle()
        }
        fun startExistingEditor() {
            composeRule.waitForIdle()
            check(editor.draft.value == null) { "Existing entry requires no active draft" }
            check(editor.pendingDraftAcknowledgement.value == null)
            check(sessions.saveStateFor(1L, BoardSnapshot()).value is BoardSaveState.Idle)
            val target = board.elements.single { it.id == original.id }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val selected = context.getString(R.string.selection_state_selected)
            val unselected = context.getString(R.string.unselected)
            fun selectionState(): String {
                val node = composeRule.onAllNodesWithContentDescription(target.text)
                    .fetchSemanticsNodes().single()
                val state = node.config.getOrNull(SemanticsProperties.StateDescription)
                check(state == selected || state == unselected) { "Unknown selection state: $state" }
                val selectedElements = composeRule.onAllNodes(SemanticsMatcher("Selected text elements") {
                    it.config.getOrNull(SemanticsProperties.StateDescription) == selected &&
                        it.config.getOrNull(SemanticsProperties.ContentDescription)
                            ?.any { label -> board.elements.any { element -> element.text == label } } == true
                }).fetchSemanticsNodes()
                check(selectedElements.size == if (state == selected) 1 else 0) {
                    "Existing entry requires an unambiguous target selection"
                }
                return checkNotNull(state)
            }
            if (selectionState() == unselected) {
                composeRule.onNodeWithContentDescription(target.text).performClick()
                assertEquals(selected, selectionState())
            }
            composeRule.onNodeWithContentDescription(target.text).performClick()
            composeRule.waitUntil(10_000) {
                val nodes = composeRule.onAllNodesWithContentDescription(existingEditor).fetchSemanticsNodes()
                editor.draft.value?.id == target.id && nodes.size == 1 &&
                    nodes.single().config.contains(SemanticsActions.SetText) &&
                    nodes.single().config.getOrNull(SemanticsProperties.Focused) == true &&
                    composeRule.onAllNodesWithText("完了").fetchSemanticsNodes().size == 1 &&
                    composeRule.onAllNodesWithText("やめる").fetchSemanticsNodes().size == 1
            }
            awaitEditor(existingEditor)
            assertExistingDraft(target)
        }
        fun assertExistingDraft(expected: TextElement) {
            val current = checkNotNull(editor.draft.value)
            assertEquals(expected.id, current.id)
            assertEquals(expected.text, current.text)
            assertEquals(expected.kind, current.kind)
            assertEquals(expected.color, current.color)
            assertEquals(expected.x, current.x)
            assertEquals(expected.y, current.y)
        }
        fun assertExistingSelected(expected: TextElement) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val node = composeRule.onAllNodesWithContentDescription(expected.text)
                .fetchSemanticsNodes().single()
            assertEquals(context.getString(R.string.selection_state_selected),
                node.config.getOrNull(SemanticsProperties.StateDescription))
        }
        fun prepareExistingDraft(text: String) {
            composeRule.waitForIdle()
            awaitEditor(existingEditor)
            check(editor.pendingDraftAcknowledgement.value == null)
            check(sessions.saveStateFor(1L, BoardSnapshot()).value is BoardSaveState.Idle)
            val sessionId = checkNotNull(editor.draft.value).sessionId
            fun latest(): Draft {
                val current = checkNotNull(editor.draft.value)
                assertEquals(original.id, current.id)
                assertEquals(sessionId, current.sessionId)
                return current
            }
            if (latest().text != text) {
                composeRule.onNodeWithContentDescription(existingEditor).performTextReplacement(text)
            }
            assertEquals(text, latest().text)
            if (latest().kind != TextKind.TITLE) {
                composeRule.onNodeWithText("見出し").performClick()
            }
            assertEquals(TextKind.TITLE, latest().kind)
            if (latest().color != TextColor.VERMILION) {
                composeRule.onNodeWithText("朱").performClick()
            }
            val prepared = latest()
            assertEquals(text, prepared.text)
            assertEquals(TextKind.TITLE, prepared.kind)
            assertEquals(TextColor.VERMILION, prepared.color)
        }
        fun outside() = tap(point(.92f, .24f))
        private var lastComposeDown: Long? = null

        fun tap(point: Offset, withinDoubleTap: Boolean = false) = gesture(point,
            withinDoubleTap = withinDoubleTap)

        private fun assertLatestEditableInput(expected: String, sessionId: String) {
            val latest = checkNotNull(editor.draft.value)
            assertEquals(sessionId, latest.sessionId)
            assertEquals("COMPOSE_SPLIT_TOUCH_TIMING_RECOVERY_INCOMPLETE: latest draft",
                expected, latest.text)
            val node = composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().single()
            check(node.config.contains(SemanticsActions.SetText))
            val editable = checkNotNull(node.config.getOrNull(SemanticsProperties.EditableText))
            assertEquals(expected, editable.text)
        }

        fun gesture(start: Offset, end: Offset = start, heldMillis: Long = 0,
                    cancelled: Boolean = false, duringPress: (() -> Unit)? = null,
                    expectedLatestText: String? = null, withinDoubleTap: Boolean = false) {
            if (!composeTouch) {
                uiAutomationGesture(start, end, heldMillis, cancelled, duringPress)
                return
            }
            check(start == end && heldMillis == 0L && !cancelled) {
                "Hybrid Compose mode only admits stationary short taps"
            }
            val canvas = composeRule.onNodeWithContentDescription("キャンバス")
            canvas.assertIsDisplayed()
            val host = composeRule.onAllNodesWithContentDescription("キャンバス")
                .fetchSemanticsNodes().single()
            val root = host.root
            val bounds = host.boundsInRoot
            check(bounds.width > 0f && bounds.height > 0f)
            val origin = IntArray(2)
            scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
            // Preserve screen-pixel fractions/centers; injection uses clipped canvas-local px.
            val local = start - Offset(origin[0].toFloat(), origin[1].toFloat()) -
                host.boundsInWindow.topLeft
            val rootPoint = bounds.topLeft + local
            fun strictlyInside(rect: Rect, point: Offset) = point.x > rect.left &&
                point.x < rect.right && point.y > rect.top && point.y < rect.bottom
            check(strictlyInside(bounds, rootPoint)) { "Touch must be inside visible canvas bounds" }
            val before = editor.draft.value
            if (before != null) {
                val field = composeRule.onNodeWithContentDescription(
                    if (before.id == null) newEditor else existingEditor).fetchSemanticsNode()
                val done = composeRule.onNodeWithText("完了").fetchSemanticsNode()
                val cancel = composeRule.onNodeWithText("やめる").fetchSemanticsNode()
                assertSame(root, field.root)
                assertSame(root, done.root)
                assertSame(root, cancel.root)
                val cancelAncestors = generateSequence(cancel.layoutInfo) { it.parentInfo }.toList()
                val toolbar = generateSequence(done.layoutInfo) { it.parentInfo }
                    .first { ancestor -> cancelAncestors.any { it === ancestor } }
                val toolbarBounds = toolbar.coordinates.boundsInRoot()
                // Inclusive exclusion keeps test points off field and toolbar boundaries.
                fun outside(rect: Rect) = rootPoint.x < rect.left || rootPoint.x > rect.right ||
                    rootPoint.y < rect.top || rootPoint.y > rect.bottom
                check(outside(field.boundsInRoot) && outside(toolbarBounds)) {
                    "Outside DOWN must exclude current field and toolbar bounds"
                }
            }
            val clock = composeRule.mainClock
            val originalAutoAdvance = clock.autoAdvance
            val started = clock.currentTime
            val threshold = host.layoutInfo.viewConfiguration.longPressTimeoutMillis
            if (withinDoubleTap) {
                val previous = checkNotNull(lastComposeDown)
                check(started - previous in 0..host.layoutInfo.viewConfiguration.doubleTapTimeoutMillis) {
                    "Nearby outside tap must exercise the current double-tap interval"
                }
            }
            var downCompleted = false
            var terminalCompleted = false
            var failure: Throwable? = null
            fun sameRoot() = composeRule.onAllNodesWithContentDescription("キャンバス")
                .fetchSemanticsNodes().single().root === root
            fun assertActive() {
                check(sameRoot()) { "HYBRID_TOUCH_RECOVERY_INCOMPLETE: Compose root changed" }
                val currentBounds = canvas.fetchSemanticsNode().boundsInRoot
                canvas.performTouchInput {
                    val active = checkNotNull(currentPosition(0))
                    assertTrue("Same pointer must retain its root position",
                        (active + currentBounds.topLeft - rootPoint).getDistance() < .01f)
                    assertEquals(null, currentPosition(1))
                }
            }
            try {
                clock.autoAdvance = false
                check(40L < threshold) { "COMPOSE_SPLIT_TOUCH_TIMING_RECOVERY_INCOMPLETE" }
                canvas.performTouchInput { down(local) }
                downCompleted = true
                lastComposeDown = started
                assertActive()
                duringPress?.invoke() // DOWN already flushed; SetText remains outside injection blocks.
                if (before != null) {
                    assertEquals(before.sessionId, checkNotNull(editor.draft.value).sessionId)
                }
                // Fixed budget: three Android test-clock frames (48ms), including recomposition.
                // No condition-driven advancement or retry if the required state is not ready.
                repeat(3) { clock.advanceTimeByFrame() }
                if (before != null) {
                    val expected = expectedLatestText ?: before.text
                    assertLatestEditableInput(expected, before.sessionId)
                }
                assertActive()
                val elapsed = clock.currentTime - started
                assertTrue("COMPOSE_SPLIT_TOUCH_TIMING_RECOVERY_INCOMPLETE: minimum hold", elapsed >= 40L)
                assertTrue("COMPOSE_SPLIT_TOUCH_TIMING_RECOVERY_INCOMPLETE: long press", elapsed < threshold)
                println("COMPOSE_SHORT_TAP inputMillis=$elapsed thresholdMillis=$threshold rootStable=true")
                canvas.performTouchInput { up(); terminalCompleted = true }
                check(clock.currentTime - started == elapsed)
                canvas.performTouchInput { assertEquals(null, currentPosition(0)) }
            } catch (original: Throwable) {
                failure = original
                if (downCompleted && !terminalCompleted) {
                    try {
                        check(sameRoot()) { "HYBRID_TOUCH_RECOVERY_INCOMPLETE: cleanup root changed" }
                        canvas.performTouchInput {
                            check(currentPosition(0) != null)
                            cancel()
                            terminalCompleted = true
                        }
                    } catch (cleanup: Throwable) {
                        original.addSuppressed(cleanup)
                    }
                }
                throw original
            } finally {
                try { clock.autoAdvance = originalAutoAdvance }
                catch (restore: Throwable) {
                    if (failure != null) failure.addSuppressed(restore) else throw restore
                }
            }
            composeRule.waitForIdle()
        }
        fun assertUnchanged(expectedRedo: Boolean = false) {
            assertEquals(listOf(original), board.elements)
            assertEquals(listOf(original), rows().map { it.toModel() })
            assertFalse(board.canUndo)
            assertEquals(expectedRedo, board.canRedo)
        }
        fun assertClosed() {
            composeRule.waitUntil(5_000) {
                // Running removes editable semantics before the save acknowledgement closes chrome.
                composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() &&
                    composeRule.onAllNodesWithText("完了").fetchSemanticsNodes().isEmpty() &&
                    composeRule.onAllNodesWithText("やめる").fetchSemanticsNodes().isEmpty() &&
                    composeRule.onAllNodesWithText("再試行").fetchSemanticsNodes().isEmpty()
            }
            assertTrue(composeRule.onAllNodes(hasSetTextAction() and isFocused())
                .fetchSemanticsNodes().isEmpty())
            composeRule.waitUntil(5_000) {
                var visible = true
                scenario.onActivity {
                    visible = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                }
                !visible
            }
        }
        val editor get() = sessions.textEditorFor(1L, BoardSnapshot())
        fun trackSaves(): AtomicInteger {
            val count = AtomicInteger()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            sessions.setSaveOperation { id, snapshot ->
                count.incrementAndGet()
                CanvasStore.get(context).save(id, snapshot)
            }
            return count
        }
        fun assertSaved(expected: List<TextElement>) {
            assertEquals(expected, board.elements)
            composeRule.waitUntil(5_000) {
                rows().map { it.toModel() }.sortedBy { it.id } == expected.sortedBy { it.id }
            }
        }
        fun assertOneHistoryChange(expected: List<TextElement>) {
            scenario.onActivity {
                assertTrue(board.canUndo)
                assertFalse(board.canRedo)
                assertTrue(board.undo())
                assertEquals(listOf(original), board.elements)
                assertFalse(board.canUndo)
                assertTrue(board.canRedo)
                assertTrue(board.redo())
                assertEquals(expected, board.elements)
                assertFalse(board.canRedo)
            }
        }
        fun reopenSaved(expected: List<TextElement>) {
            scenario.close()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val reopened = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            try {
                composeRule.waitUntil(10_000) {
                    composeRule.onAllNodesWithContentDescription(expected.last().text)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                reopened.onActivity {
                    val fresh = ViewModelProvider(it)[BoardSessionViewModel::class.java]
                    assertEquals(expected, fresh.stateFor(1L, BoardSnapshot()).elements)
                }
            } finally {
                reopened.close()
            }
        }
        fun recreate() {
            scenario.recreate()
            composeRule.waitForIdle()
        }
    }

    private fun withBoard(composeTouch: Boolean = false, block: Harness.() -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val seed = CanvasDatabase.open(context)
        runBlocking {
            if (seed.canvasDao().board(1L) == null) seed.canvasDao().putBoard(BoardRow())
            seed.canvasDao().replaceAll(1L, listOf(TextElementRow.fromModel(1L, original)),
                emptyList(), emptyList())
        }
        seed.close()
        showBoardOneAtStartup(context)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        val database = CanvasDatabase.open(context)
        try {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithContentDescription(original.text).fetchSemanticsNodes().isNotEmpty()
            }
            lateinit var sessions: BoardSessionViewModel
            scenario.onActivity { sessions = ViewModelProvider(it)[BoardSessionViewModel::class.java] }
            Harness(scenario, database, sessions, composeTouch).block()
        } finally {
            database.close()
            scenario.close()
        }
    }

    private fun awaitEditor(label: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(label).assertIsFocused()
    }

    private fun uiAutomationGesture(start: Offset, end: Offset = start, heldMillis: Long = 0,
                        cancelled: Boolean = false, duringPress: (() -> Unit)? = null) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        var downAccepted = false
        var terminalSent = false
        var lastPoint = start
        fun send(action: Int, point: Offset) {
            val properties = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                x = point.x; y = point.y; pressure = 1f; size = 1f
            })
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                1, properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) terminalSent = true
                val accepted = instrumentation.uiAutomation.injectInputEvent(event, true)
                if (action == MotionEvent.ACTION_DOWN) downAccepted = accepted
                assertTrue(accepted)
            } finally { event.recycle() }
        }
        try {
            send(MotionEvent.ACTION_DOWN, start)
            Thread.sleep(heldMillis.coerceAtLeast(40))
            duringPress?.invoke()
            if (duringPress != null) assertTrue("The input update must fit inside a short tap",
                SystemClock.uptimeMillis() - downTime < ViewConfiguration.getLongPressTimeout())
            if (start != end) { lastPoint = end; send(MotionEvent.ACTION_MOVE, end) }
            send(if (cancelled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, end)
        } catch (original: Throwable) {
            if (downAccepted && !terminalSent) {
                terminalSent = true
                try { send(MotionEvent.ACTION_CANCEL, lastPoint) }
                catch (cleanup: Throwable) { original.addSuppressed(cleanup) }
            }
            throw original
        }
        composeRule.waitForIdle()
    }

    @Test fun emptyDraftOutsideTapClosesWithoutCreatingAndNextTapStartsDraft() = withBoard {
        val saves = trackSaves()
        // A discard must preserve redo as well as undo, even when redo is available.
        scenario.onActivity {
            board.move(original.id, original.x + 10f, original.y)
            board.undo()
        }
        startNew()
        outside()
        assertClosed()
        assertUnchanged(expectedRedo = true)
        startNew()
        composeRule.onNodeWithText("やめる").performClick()
        assertClosed()
        assertUnchanged(expectedRedo = true)
        assertEquals(0, saves.get())
    }

    @Test fun nonEmptyDraftOutsideCommitsOnceWithWorldPositionKindColorAndReopen() = withBoard {
        val saves = trackSaves()
        startNew("Saved outside")
        composeRule.onNodeWithText("見出し").performClick()
        composeRule.onNodeWithText("朱").performClick()
        val draft = checkNotNull(editor.draft.value)
        outside()
        assertClosed()
        val created = board.elements.single { it.id != original.id }
        assertEquals(TextElement(id = created.id, text = draft.text, kind = draft.kind,
            color = draft.color, x = draft.x, y = draft.y), created)
        val expected = listOf(original, created)
        assertSaved(expected)
        assertEquals(1, saves.get())
        // The consumed completion cannot start a second draft; a separate blank tap can.
        startNew(y = .6f)
        outside()
        assertClosed()
        assertSaved(expected)
        assertOneHistoryChange(expected)
        assertEquals(1, saves.get())
        reopenSaved(expected)
    }

    @Test fun outsideTapCommitsLatestInputWhenEmptyDraftChangesBetweenDownAndUp() = withBoard(composeTouch = true) {
        val saves = trackSaves()
        startNew()
        val sessionId = checkNotNull(editor.draft.value).sessionId
        gesture(point(.92f, .24f), expectedLatestText = "Input update while pressed", duringPress = {
            composeRule.onNodeWithContentDescription(newEditor)
                .performTextReplacement("Input update while pressed")
            assertEquals(sessionId, checkNotNull(editor.draft.value).sessionId)
        })
        assertClosed()
        val created = board.elements.single { it.id != original.id }
        assertEquals("Input update while pressed", created.text)
        assertSaved(listOf(original, created))
        assertEquals(1, saves.get())
        startNew("Before second update", y = .6f)
        gesture(point(.92f, .24f), expectedLatestText = "Latest non-empty update", duringPress = {
            composeRule.onNodeWithContentDescription(newEditor)
                .performTextReplacement("Latest non-empty update")
        })
        assertClosed()
        assertEquals("Latest non-empty update", board.elements.last().text)
        assertSaved(board.elements)
        assertEquals(3, board.elements.size)
        assertEquals(2, saves.get())
    }

    @Test fun existingEditOutsideTapCommitsContentKindColorAndPreservesIdentityPosition() = withBoard(composeTouch = true) {
        val saves = trackSaves()
        startExistingEditor()
        prepareExistingDraft("Changed note")
        outside()
        assertClosed()
        val expected = listOf(original.copy(text = "Changed note",
            kind = TextKind.TITLE, color = TextColor.VERMILION))
        assertSaved(expected)
        assertOneHistoryChange(expected)
        assertEquals(1, saves.get())
        reopenSaved(expected)
    }

    @Test fun existingElementTapOnlyFinalizesAndFormerChromeIsNotBlocked() = withBoard {
        val saves = trackSaves()
        val formerControl = center("ボード内を検索")
        startNew("Saved first")
        tap(center(original.text))
        assertClosed()
        assertEquals(original, board.elements.single { it.id == original.id })
        // Forwarded selection would make this independent blank tap only deselect.
        startNew("Saved second", y = .6f)
        tap(formerControl)
        assertClosed()
        assertEquals(3, board.elements.size)
        assertSaved(board.elements)
        assertEquals(2, saves.get())
        // Neither the former editor bounds nor search action survives completion.
        startNew(y = .8f)
        composeRule.onNodeWithText("やめる").performClick()
        assertClosed()
        assertEquals(2, saves.get())
    }

    @Test fun insideFieldToolbarAndDoneKeepTheirActions() = withBoard {
        startNew("Committed")
        tap(center(newEditor))
        awaitEditor(newEditor)
        tap(center("見出し"))
        tap(center("朱"))
        composeRule.onNodeWithContentDescription(newEditor).assertTextEquals("Committed")
        tap(center("完了"))
        assertClosed()
        composeRule.waitUntil(5_000) { rows().size == 2 }
        val created = rows().single { it.id != original.id }.toModel()
        assertEquals("Committed", created.text)
        assertEquals(TextKind.TITLE, created.kind)
        assertEquals(TextColor.VERMILION, created.color)
    }

    @Test fun outsideDragLongPressAndCancellationKeepDraft() = withBoard {
        val initial = point(.1f, .23f)
        val originalCenter = center(original.text)
        tap(initial)
        awaitEditor(newEditor)
        val outside = point(.92f, .24f)
        gesture(outside, outside + Offset(80f, 60f))
        awaitEditor(newEditor)
        gesture(outside, heldMillis = ViewConfiguration.getLongPressTimeout().toLong() + 100)
        awaitEditor(newEditor)
        gesture(outside, cancelled = true)
        awaitEditor(newEditor)
        tap(outside)
        assertClosed()
        assertEquals(originalCenter, center(original.text))
        assertUnchanged()
    }

    @Test fun nearbyOutsideTapIsConsumedBeforeBlankDoubleTapZoom() = withBoard(composeTouch = true) {
        val saves = trackSaves()
        val initial = point(.1f, .23f)
        val originalCenter = center(original.text)
        tap(initial)
        awaitEditor(newEditor)
        // Keep the original empty-draft zoom regression.
        tap(initial - Offset(8f, 0f), withinDoubleTap = true)
        assertClosed()
        assertEquals(originalCenter, center(original.text))
        assertUnchanged()
        assertEquals(null, editor.draft.value)
        assertEquals(0, saves.get())
    }

    @Test fun outsideTwoFingerGestureKeepsDraft() = withBoard {
        startNew("Two fingers")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val first = point(.86f, .26f)
        val second = first + Offset(40f, 30f)
        val downTime = SystemClock.uptimeMillis()
        var downAccepted = false
        var terminalSent = false
        var activeCount = 1
        fun send(action: Int, count: Int) {
            val properties = Array(count) { index -> MotionEvent.PointerProperties().apply {
                id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply {
                val point = if (index == 0) first else second
                x = point.x; y = point.y; pressure = 1f; size = 1f
            } }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                count, properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) terminalSent = true
                val accepted = automation.injectInputEvent(event, true)
                if (action == MotionEvent.ACTION_DOWN) downAccepted = accepted
                if (accepted && action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_POINTER_DOWN) activeCount = 2
                if (accepted && action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_POINTER_UP) activeCount = 1
                assertTrue(accepted)
            } finally { event.recycle() }
            Thread.sleep(40)
        }
        try {
            send(MotionEvent.ACTION_DOWN, 1)
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
            send(MotionEvent.ACTION_UP, 1)
        } catch (original: Throwable) {
            if (downAccepted && !terminalSent) {
                terminalSent = true
                try { send(MotionEvent.ACTION_CANCEL, activeCount) }
                catch (cleanup: Throwable) { original.addSuppressed(cleanup) }
            }
            throw original
        }
        composeRule.waitForIdle()
        awaitEditor(newEditor)
        composeRule.onNodeWithContentDescription(newEditor).assertTextEquals("Two fingers")
        assertUnchanged()
    }
    @Test fun newDraftSurvivesRecreationStopResumeAndImeBackWithoutSave() = withBoard {
        startNew("Keep draft")
        composeRule.onNodeWithText("見出し").performClick()
        composeRule.onNodeWithText("朱").performClick()
        val before = checkNotNull(editor.draft.value)
        recreate()
        awaitEditor(newEditor)
        assertEquals(before, editor.draft.value)
        composeRule.onNodeWithContentDescription(newEditor).assertTextEquals("Keep draft")
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        awaitEditor(newEditor)
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(newEditor).assertTextEquals("Keep draft")
        assertEquals(before, editor.draft.value)
        assertUnchanged()
        composeRule.onNodeWithText("完了").performClick()
        assertClosed()
        composeRule.waitUntil(5_000) { rows().size == 2 }
        val created = rows().single { it.id != original.id }.toModel()
        assertEquals(TextKind.TITLE, created.kind)
        assertEquals(TextColor.VERMILION, created.color)
        assertEquals(before.x, created.x)
        assertEquals(before.y, created.y)
    }

    @Test fun existingDraftSurvivesRecreationThenCanCancelWithoutSave() = withBoard(composeTouch = true) {
        startExistingEditor()
        prepareExistingDraft("Changed note")
        recreate()
        awaitEditor(existingEditor)
        composeRule.onNodeWithContentDescription(existingEditor).assertTextEquals("Changed note")
        composeRule.onNodeWithText("やめる").performClick()
        assertClosed()
        assertUnchanged()
    }

    @Test fun runningSaveOutsideTapAndRecreationKeepOneRequestUntilSuccess() = withBoard {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gate = CompletableDeferred<Unit>()
        var saves = 0
        lateinit var requested: BoardSnapshot
        sessions.setSaveOperation { _, snapshot -> saves++; requested = snapshot; gate }
        startNew("Save once")
        outside()
        composeRule.waitUntil(5_000) {
            sessions.saveStateFor(1L, BoardSnapshot()).value is BoardSaveState.Running
        }
        val acknowledgement = checkNotNull(editor.pendingDraftAcknowledgement.value)
        outside()
        awaitEditor(newEditor)
        assertSame(acknowledgement, editor.pendingDraftAcknowledgement.value)
        recreate()
        awaitEditor(newEditor)
        assertSame(acknowledgement, editor.pendingDraftAcknowledgement.value)
        outside()
        awaitEditor(newEditor)
        assertSame(acknowledgement, editor.pendingDraftAcknowledgement.value)
        assertEquals(1, saves)
        runBlocking { CanvasStore.get(context).save(1L, requested).await() }
        gate.complete(Unit)
        assertClosed()
        assertEquals(1, saves)
        assertEquals(2, rows().size)
        assertEquals(2, board.elements.size)
        assertOneHistoryChange(board.elements)
    }

    @Test fun failedSaveOutsideTapAndRecreationKeepRetryWithoutDuplicateElement() = withBoard {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gate = CompletableDeferred<Unit>()
        var saves = 0
        sessions.setSaveOperation { _, _ -> saves++; gate }
        startNew("Retry once")
        outside()
        composeRule.waitUntil(5_000) {
            sessions.saveStateFor(1L, BoardSnapshot()).value is BoardSaveState.Running
        }
        gate.completeExceptionally(IllegalStateException("expected test failure"))
        composeRule.waitUntil(5_000) {
            sessions.saveStateFor(1L, BoardSnapshot()).value is BoardSaveState.Failed
        }
        val acknowledgement = checkNotNull(editor.pendingDraftAcknowledgement.value)
        outside()
        awaitEditor(newEditor)
        assertSame(acknowledgement, editor.pendingDraftAcknowledgement.value)
        recreate()
        awaitEditor(newEditor)
        assertSame(acknowledgement, editor.pendingDraftAcknowledgement.value)
        composeRule.onNodeWithContentDescription(newEditor).assertTextEquals("Retry once")
        outside()
        awaitEditor(newEditor)
        assertSame(acknowledgement, editor.pendingDraftAcknowledgement.value)
        assertEquals(1, saves)
        sessions.setSaveOperation { id, snapshot -> saves++; CanvasStore.get(context).save(id, snapshot) }
        composeRule.onNodeWithText("再試行").performClick()
        assertClosed()
        assertEquals(2, saves)
        assertEquals(2, board.elements.size)
        assertEquals(2, rows().size)
        assertEquals(1, rows().count { it.text == "Retry once" })
        assertOneHistoryChange(board.elements)
    }

    @Test fun outsideTapDiscardsLatestEmptyInputBetweenDownAndUp() = withBoard(composeTouch = true) {
        val saves = trackSaves()
        startNew("Removed while pressed")
        val sessionId = checkNotNull(editor.draft.value).sessionId
        gesture(point(.92f, .24f), expectedLatestText = "", duringPress = {
            composeRule.onNodeWithContentDescription(newEditor).performTextReplacement("")
            assertEquals(sessionId, checkNotNull(editor.draft.value).sessionId)
        })
        assertClosed()
        assertUnchanged()
        assertEquals(0, saves.get())
    }

    @Test fun explicitCancelDiscardsNewAndExistingEditsWithoutSaving() = withBoard(composeTouch = true) {
        val saves = trackSaves()
        startNew("Explicit cancel")
        composeRule.onNodeWithText("やめる").performClick()
        assertClosed()
        assertUnchanged()
        startExistingEditor()
        prepareExistingDraft("Changed note")
        composeRule.onNodeWithText("やめる").performClick()
        assertClosed()
        assertUnchanged()
        assertEquals(0, saves.get())
    }

    @Test fun existingEditExplicitDoneMatchesOutsideCompletion() = withBoard(composeTouch = true) {
        val saves = trackSaves()
        startExistingEditor()
        prepareExistingDraft("Changed note")
        composeRule.onNodeWithText("完了").performClick()
        assertClosed()
        val expected = listOf(original.copy(text = "Changed note",
            kind = TextKind.TITLE, color = TextColor.VERMILION))
        assertSaved(expected)
        assertOneHistoryChange(expected)
        assertEquals(1, saves.get())
        reopenSaved(expected)
    }

    @Test fun existingEditorEntryHandlesUnselectedSelectedDoneAndOutsideReentry() = withBoard(composeTouch = true) {
        val saves = trackSaves()
        // Fresh unselected entry selects first; completion retains that selection.
        startExistingEditor()
        assertExistingDraft(original)
        prepareExistingDraft("Changed note")
        composeRule.onNodeWithText("完了").performClick()
        assertClosed()
        val expected = listOf(original.copy(text = "Changed note",
            kind = TextKind.TITLE, color = TextColor.VERMILION))
        assertSaved(expected)
        assertEquals(1, saves.get())
        // Already selected after Done: activation only, using the current text label.
        assertExistingSelected(expected.single())
        startExistingEditor()
        assertExistingDraft(expected.single())
        composeRule.onNodeWithContentDescription(existingEditor).performTextReplacement("Outside reentry")
        outside()
        assertClosed()
        val outsideExpected = listOf(expected.single().copy(text = "Outside reentry"))
        assertSaved(outsideExpected)
        assertEquals(2, saves.get())
        // Already selected after a valid outside commit: activation only again.
        assertExistingSelected(outsideExpected.single())
        startExistingEditor()
        assertExistingDraft(outsideExpected.single())
        composeRule.onNodeWithText("やめる").performClick()
        assertClosed()
        assertSaved(outsideExpected)
        assertEquals(2, saves.get())
    }

    @Test fun existingDraftSurvivesRecreationThenOutsideCommits() = withBoard(composeTouch = true) {
        startExistingEditor()
        prepareExistingDraft("Changed note")
        val before = checkNotNull(editor.draft.value)
        recreate()
        awaitEditor(existingEditor)
        assertEquals(before, editor.draft.value)
        assertUnchanged()
        val saves = trackSaves()
        outside()
        assertClosed()
        val expected = listOf(original.copy(text = before.text,
            kind = before.kind, color = before.color))
        assertSaved(expected)
        assertOneHistoryChange(expected)
        assertEquals(1, saves.get())
        reopenSaved(expected)
    }

    @Test fun whitespaceOutsideUsesExistingDoneValidationForNewAndExistingDrafts() = withBoard(composeTouch = true) {
        val saves = trackSaves()
        repeat(2) { index ->
            startNew("   ")
            if (index == 0) outside() else composeRule.onNodeWithText("完了").performClick()
            assertClosed()
            assertUnchanged()
        }
        repeat(2) { index ->
            startExistingEditor()
            prepareExistingDraft("   ")
            if (index == 0) outside() else composeRule.onNodeWithText("完了").performClick()
            assertClosed()
            assertUnchanged()
        }
        assertEquals(0, saves.get())
    }

    @Test fun emptyExistingOutsideUsesExistingDoneValidationWithoutDeletingElement() = withBoard(composeTouch = true) {
        val saves = trackSaves()
        repeat(2) { index ->
            startExistingEditor()
            prepareExistingDraft("")
            if (index == 0) outside() else composeRule.onNodeWithText("完了").performClick()
            assertClosed()
            assertUnchanged()
        }
        assertEquals(0, saves.get())
    }

    @Test fun splitOutsideTouchExceptionCancelsAndNextGestureSucceeds() = withBoard(composeTouch = true) {
        val saves = trackSaves()
        startNew("Cleanup draft")
        val before = checkNotNull(editor.draft.value)
        val originalAutoAdvance = composeRule.mainClock.autoAdvance
        val sentinel = IllegalStateException("intentional split-touch sentinel")
        var caught: Throwable? = null
        try {
            gesture(point(.92f, .24f), duringPress = { throw sentinel })
        } catch (failure: Throwable) { caught = failure }
        assertSame(sentinel, caught)
        assertEquals(IllegalStateException::class.java, checkNotNull(caught).javaClass)
        assertTrue(sentinel.suppressed.isEmpty())
        assertEquals(originalAutoAdvance, composeRule.mainClock.autoAdvance)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("キャンバス").performTouchInput {
            assertEquals(null, currentPosition(0))
        }
        assertEquals(before, editor.draft.value)
        assertEquals(null, editor.pendingDraftAcknowledgement.value)
        assertTrue(sessions.saveStateFor(1L, BoardSnapshot()).value is BoardSaveState.Idle)
        assertEquals(0, saves.get())
        assertUnchanged()
        outside()
        assertClosed()
        val created = board.elements.single { it.id != original.id }
        assertEquals(before.text, created.text)
        assertSaved(listOf(original, created))
        assertOneHistoryChange(listOf(original, created))
        assertEquals(1, saves.get())
    }
}
