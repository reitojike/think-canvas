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
        fun startNew(text: String = "") {
            tap(point(.1f, .23f))
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
        fun assertUnchanged() {
            assertEquals(listOf(original), board.elements)
            assertEquals(listOf(original), rows().map { it.toModel() })
            assertFalse(board.canUndo)
        }
        fun assertClosed() {
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()
            }
            assertTrue(composeRule.onAllNodesWithText("完了").fetchSemanticsNodes().isEmpty())
            assertTrue(composeRule.onAllNodesWithText("やめる").fetchSemanticsNodes().isEmpty())
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
                        cancelled: Boolean = false) {
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
        if (start != end) send(MotionEvent.ACTION_MOVE, end)
        send(if (cancelled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, end)
        composeRule.waitForIdle()
    }

    @Test fun emptyDraftOutsideTapClosesWithoutCreatingAndNextTapStartsDraft() = withBoard {
        startNew()
        outside()
        assertClosed()
        assertUnchanged()
        startNew()
        composeRule.onNodeWithText("やめる").performClick()
        assertClosed()
        assertUnchanged()
    }

    @Test fun nonEmptyDraftAndExplicitCancelDoNotSave() = withBoard {
        var saves = 0
        sessions.setSaveOperation { _, _ -> saves++; CompletableDeferred(Unit) }
        startNew("Never saved")
        outside()
        assertClosed()
        assertUnchanged()
        startNew("Explicit cancel")
        composeRule.onNodeWithText("やめる").performClick()
        assertClosed()
        assertUnchanged()
        assertEquals(0, saves)
    }

    @Test fun existingEditOutsideTapRestoresOriginalContentKindColorAndPosition() = withBoard {
        startExisting()
        outside()
        assertClosed()
        assertUnchanged()
    }

    @Test fun existingElementTapOnlyDismissesAndFormerChromeIsNotBlocked() = withBoard {
        val formerControl = center("ボード内を検索")
        startNew("Uncommitted")
        tap(center(original.text))
        assertClosed()
        assertUnchanged()
        composeRule.onNodeWithContentDescription(original.text)
            .assertExists()
        // If the dismissal had also selected the element this blank tap would only deselect.
        startNew("Second draft")
        tap(formerControl)
        assertClosed()
        assertUnchanged()
        // The former editor/toolbar bounds must no longer block normal admission.
        startNew()
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
        val initial = point(.1f, .23f)
        val originalCenter = center(original.text)
        tap(initial)
        awaitEditor(newEditor)
        // Outside the field's left edge, still within the platform double-tap distance.
        tap(initial - Offset(8f, 0f))
        assertClosed()
        assertEquals(originalCenter, center(original.text))
        assertUnchanged()
        startNew()
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
        recreate()
        awaitEditor(newEditor)
        composeRule.onNodeWithContentDescription(newEditor).assertTextEquals("Keep draft")
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        awaitEditor(newEditor)
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(newEditor).assertTextEquals("Keep draft")
        assertUnchanged()
        composeRule.onNodeWithText("完了").performClick()
        assertClosed()
        composeRule.waitUntil(5_000) { rows().size == 2 }
        val created = rows().single { it.id != original.id }.toModel()
        assertEquals(TextKind.TITLE, created.kind)
        assertEquals(TextColor.VERMILION, created.color)
    }

    @Test fun existingDraftSurvivesRecreationThenCanCancelWithoutSave() = withBoard {
        startExisting()
        recreate()
        awaitEditor(existingEditor)
        composeRule.onNodeWithContentDescription(existingEditor).assertTextEquals("Changed note")
        outside()
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
        composeRule.onNodeWithText("完了").performClick()
        composeRule.waitUntil(5_000) {
            sessions.saveStateFor(1L, BoardSnapshot()).value is BoardSaveState.Running
        }
        outside()
        awaitEditor(newEditor)
        recreate()
        awaitEditor(newEditor)
        outside()
        awaitEditor(newEditor)
        runBlocking { CanvasStore.get(context).save(1L, requested).await() }
        gate.complete(Unit)
        assertClosed()
        assertEquals(1, saves)
        assertEquals(2, rows().size)
        assertEquals(2, board.elements.size)
    }

    @Test fun failedSaveOutsideTapAndRecreationKeepRetryWithoutDuplicateElement() = withBoard {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gate = CompletableDeferred<Unit>()
        var saves = 0
        sessions.setSaveOperation { _, _ -> saves++; gate }
        startNew("Retry once")
        composeRule.onNodeWithText("完了").performClick()
        composeRule.waitUntil(5_000) {
            sessions.saveStateFor(1L, BoardSnapshot()).value is BoardSaveState.Running
        }
        gate.completeExceptionally(IllegalStateException("expected test failure"))
        composeRule.waitUntil(5_000) {
            sessions.saveStateFor(1L, BoardSnapshot()).value is BoardSaveState.Failed
        }
        outside()
        awaitEditor(newEditor)
        recreate()
        awaitEditor(newEditor)
        composeRule.onNodeWithContentDescription(newEditor).assertTextEquals("Retry once")
        outside()
        awaitEditor(newEditor)
        sessions.setSaveOperation { id, snapshot -> saves++; CanvasStore.get(context).save(id, snapshot) }
        composeRule.onNodeWithText("再試行").performClick()
        assertClosed()
        assertEquals(2, saves)
        assertEquals(2, board.elements.size)
        assertEquals(2, rows().size)
        assertEquals(1, rows().count { it.text == "Retry once" })
    }
}
