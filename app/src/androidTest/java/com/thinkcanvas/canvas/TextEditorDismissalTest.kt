package com.thinkcanvas.canvas

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsFocused
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
import androidx.lifecycle.Lifecycle
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
        fun startExisting() {
            composeRule.onNodeWithContentDescription(original.text).performClick()
            composeRule.onNodeWithContentDescription(original.text).performClick()
            awaitEditor(existingEditor)
            composeRule.onNodeWithContentDescription(existingEditor).performTextReplacement("Changed note")
            composeRule.onNodeWithText("見出し").performClick()
            composeRule.onNodeWithText("朱").performClick()
        }
        fun outside() = tap(point(.92f, .24f))
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

    private fun withBoard(block: Harness.() -> Unit) {
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
            Harness(scenario, database, sessions).block()
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

    private fun tap(point: Offset) = gesture(point)

    private fun gesture(start: Offset, end: Offset = start, heldMillis: Long = 0,
                        cancelled: Boolean = false, duringPress: (() -> Unit)? = null) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
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
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, start)
        Thread.sleep(heldMillis.coerceAtLeast(40))
        duringPress?.invoke()
        if (duringPress != null) assertTrue("The input update must fit inside a short tap",
            SystemClock.uptimeMillis() - downTime < ViewConfiguration.getLongPressTimeout())
        if (start != end) send(MotionEvent.ACTION_MOVE, end)
        send(if (cancelled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, end)
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

    @Test fun outsideTapCommitsLatestInputWhenEmptyDraftChangesBetweenDownAndUp() = withBoard {
        val saves = trackSaves()
        startNew()
        val sessionId = checkNotNull(editor.draft.value).sessionId
        gesture(point(.92f, .24f), duringPress = {
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
        gesture(point(.92f, .24f), duringPress = {
            composeRule.onNodeWithContentDescription(newEditor)
                .performTextReplacement("Latest non-empty update")
        })
        assertClosed()
        assertEquals("Latest non-empty update", board.elements.last().text)
        assertSaved(board.elements)
        assertEquals(3, board.elements.size)
        assertEquals(2, saves.get())
    }

    @Test fun existingEditOutsideTapCommitsContentKindColorAndPreservesIdentityPosition() = withBoard {
        val saves = trackSaves()
        startExisting()
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

    @Test fun nearbyOutsideTapIsConsumedBeforeBlankDoubleTapZoom() = withBoard {
        val saves = trackSaves()
        val initial = point(.1f, .23f)
        val originalCenter = center(original.text)
        tap(initial)
        awaitEditor(newEditor)
        // Keep the original empty-draft zoom regression.
        tap(initial - Offset(8f, 0f))
        assertClosed()
        assertEquals(originalCenter, center(original.text))
        assertUnchanged()
        val next = point(.1f, .6f)
        startNew("Near outside", y = .6f)
        tap(next - Offset(8f, 0f))
        assertClosed()
        assertEquals(originalCenter, center(original.text))
        assertEquals(2, board.elements.size)
        assertSaved(board.elements)
        assertEquals(1, saves.get())
        startNew(y = .8f)
    }

    @Test fun outsideTwoFingerGestureKeepsDraft() = withBoard {
        startNew("Two fingers")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val first = point(.86f, .26f)
        val second = first + Offset(40f, 30f)
        val downTime = SystemClock.uptimeMillis()
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
            assertTrue(automation.injectInputEvent(event, true))
            event.recycle()
            Thread.sleep(40)
        }
        send(MotionEvent.ACTION_DOWN, 1)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
        send(MotionEvent.ACTION_UP, 1)
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

    @Test fun existingDraftSurvivesRecreationThenCanCancelWithoutSave() = withBoard {
        startExisting()
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

    @Test fun outsideTapDiscardsLatestEmptyInputBetweenDownAndUp() = withBoard {
        val saves = trackSaves()
        startNew("Removed while pressed")
        val sessionId = checkNotNull(editor.draft.value).sessionId
        gesture(point(.92f, .24f), duringPress = {
            composeRule.onNodeWithContentDescription(newEditor).performTextReplacement("")
            assertEquals(sessionId, checkNotNull(editor.draft.value).sessionId)
        })
        assertClosed()
        assertUnchanged()
        assertEquals(0, saves.get())
    }

    @Test fun explicitCancelDiscardsNewAndExistingEditsWithoutSaving() = withBoard {
        val saves = trackSaves()
        startNew("Explicit cancel")
        composeRule.onNodeWithText("やめる").performClick()
        assertClosed()
        assertUnchanged()
        startExisting()
        composeRule.onNodeWithText("やめる").performClick()
        assertClosed()
        assertUnchanged()
        assertEquals(0, saves.get())
    }

    @Test fun existingEditExplicitDoneMatchesOutsideCompletion() = withBoard {
        val saves = trackSaves()
        startExisting()
        composeRule.onNodeWithText("完了").performClick()
        assertClosed()
        val expected = listOf(original.copy(text = "Changed note",
            kind = TextKind.TITLE, color = TextColor.VERMILION))
        assertSaved(expected)
        assertOneHistoryChange(expected)
        assertEquals(1, saves.get())
        reopenSaved(expected)
    }

    @Test fun existingDraftSurvivesRecreationThenOutsideCommits() = withBoard {
        startExisting()
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

    @Test fun whitespaceOutsideUsesExistingDoneValidationForNewAndExistingDrafts() = withBoard {
        val saves = trackSaves()
        repeat(2) { index ->
            startNew("   ")
            if (index == 0) outside() else composeRule.onNodeWithText("完了").performClick()
            assertClosed()
            assertUnchanged()
        }
        repeat(2) { index ->
            startExisting()
            composeRule.onNodeWithContentDescription(existingEditor).performTextReplacement("   ")
            if (index == 0) outside() else composeRule.onNodeWithText("完了").performClick()
            assertClosed()
            assertUnchanged()
        }
        assertEquals(0, saves.get())
    }

    @Test fun emptyExistingOutsideUsesExistingDoneValidationWithoutDeletingElement() = withBoard {
        val saves = trackSaves()
        repeat(2) { index ->
            startExisting()
            composeRule.onNodeWithContentDescription(existingEditor).performTextReplacement("")
            if (index == 0) outside() else composeRule.onNodeWithText("完了").performClick()
            assertClosed()
            assertUnchanged()
        }
        assertEquals(0, saves.get())
    }
}
