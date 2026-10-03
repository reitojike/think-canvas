package com.thinkcanvas.canvas

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowInsets
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.BoardSaveState
import com.thinkcanvas.BoardSessionViewModel
import com.thinkcanvas.MainActivity
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.SpatialElementRow
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

/** Spec 007 のBack終了・通常状態への復帰をUI、BoardState、Roomで確認する。 */
@RunWith(AndroidJUnit4::class)
class NeutralInteractionTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    private val note = TextElement(id = "neutral-note", text = "Original note", x = 500f, y = 1200f)
    private val region = ShapeElement(id = "neutral-region", kind = ShapeKind.REGION,
        x = 400f, y = 1000f, width = 500f, height = 400f, name = "Cluster")

    private inner class Harness(
        val scenario: ActivityScenario<MainActivity>,
        val database: CanvasDatabase,
        val sessions: BoardSessionViewModel,
    ) {
        val board get() = sessions.stateFor(1L, BoardSnapshot())
        val editor get() = sessions.textEditorFor(1L, BoardSnapshot())
        fun rows() = runBlocking { database.canvasDao().elements(1L) }
        fun shapes() = runBlocking { database.canvasDao().spatialElements(1L) }
        fun back() {
            hideImeIfVisible()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()
        }
        fun dialogBack() {
            hideImeIfVisible()
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            composeRule.waitForIdle()
        }
        fun tapOutsideDialog() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val metrics = instrumentation.targetContext.resources.displayMetrics
            val x = 2f
            val y = metrics.heightPixels * .5f
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
            instrumentation.waitForIdleSync()
            composeRule.waitForIdle()
        }
        fun hideImeIfVisible() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            fun visible(): Boolean {
                var shown = false
                scenario.onActivity {
                    shown = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                }
                return shown
            }
            if (visible()) instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            composeRule.waitUntil(5_000) { !visible() }
        }
        fun waitEditor(label: String) {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()
            }
        }
        fun tapCanvas(xFraction: Float = .15f, yFraction: Float = .35f) {
            val canvas = composeRule.onNodeWithContentDescription("キャンバス")
            val bounds = canvas.fetchSemanticsNode().boundsInRoot
            canvas.performTouchInput {
                click(androidx.compose.ui.geometry.Offset(bounds.width * xFraction, bounds.height * yFraction))
            }
            composeRule.waitForIdle()
        }
        fun startNew(text: String = "") {
            tapCanvas()
            waitEditor("新しいテキスト")
            if (text.isNotEmpty()) composeRule.onNodeWithContentDescription("新しいテキスト")
                .performTextReplacement(text)
            composeRule.waitForIdle()
        }
        fun startExisting() {
            val selected = composeRule.onNodeWithContentDescription(note.text)
            // Existing edit opens by double click; preserve the selection-aware tap contract.
            val selection = selected.fetchSemanticsNode().config
                .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription)
            if (selection != "選択中") selected.performClick()
            composeRule.onNodeWithContentDescription(note.text).performClick()
            waitEditor("テキストを編集")
        }
        fun renameRegion() {
            val actions = composeRule.onNodeWithContentDescription("囲み: Cluster")
                .fetchSemanticsNode().config[SemanticsActions.CustomActions]
            val rename = actions.first { it.label == "囲みの名前を編集" }
            composeRule.runOnUiThread { assertTrue(rename.action()) }
            waitEditor("囲みの名前")
        }
        fun assertCanvasStillOpen() {
            assertTrue(composeRule.onAllNodesWithContentDescription("キャンバス")
                .fetchSemanticsNodes().isNotEmpty())
        }
        fun assertNoDialog() {
            assertEquals(0, composeRule.onAllNodesWithText("編集内容を破棄しますか？")
                .fetchSemanticsNodes().size)
        }
        fun assertDiscardDialog() {
            composeRule.onNodeWithText("編集内容を破棄しますか？").assertExists()
            composeRule.onNodeWithText("破棄する").assertExists()
            composeRule.onNodeWithText("編集を続ける").assertExists()
        }
    }

    private fun withBoard(block: Harness.() -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val seed = CanvasDatabase.open(context)
        runBlocking {
            if (seed.canvasDao().board(1L) == null) seed.canvasDao().putBoard(BoardRow())
            seed.canvasDao().replaceAll(1L, listOf(TextElementRow.fromModel(1L, note)),
                listOf(SpatialElementRow.fromModel(1L, region)), emptyList())
        }
        seed.close()
        showBoardOneAtStartup(context)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        val database = CanvasDatabase.open(context)
        try {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithContentDescription(note.text).fetchSemanticsNodes().isNotEmpty()
            }
            lateinit var sessions: BoardSessionViewModel
            scenario.onActivity { sessions = ViewModelProvider(it)[BoardSessionViewModel::class.java] }
            Harness(scenario, database, sessions).block()
        } finally {
            database.close()
            scenario.close()
        }
    }

    @Test
    fun systemBackWhileImeVisibleOnlyHidesKeyboardAndRetainsDraft() = withBoard {
        startNew("Keep this draft")
        composeRule.waitUntil(5_000) {
            var shown = false
            scenario.onActivity {
                shown = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            }
            shown
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        composeRule.waitUntil(5_000) {
            var shown = true
            scenario.onActivity {
                shown = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            }
            !shown
        }
        assertEquals("Keep this draft", editor.draft.value?.text)
        assertTrue(composeRule.onAllNodesWithContentDescription("新しいテキスト")
            .fetchSemanticsNodes().isNotEmpty())
        assertNoDialog()
        assertEquals(listOf(note), rows().map { it.toModel() })
    }

    @Test
    fun blankNewAndUnchangedExistingEditorsCloseWithoutSavingOrNavigating() = withBoard {
        startNew()
        back()
        assertEquals(null, editor.draft.value)
        assertEquals(listOf(note), rows().map { it.toModel() })
        assertCanvasStillOpen()
        startExisting()
        back()
        assertEquals(null, editor.draft.value)
        assertEquals(listOf(note), rows().map { it.toModel() })
        assertFalse(board.canUndo)
        assertCanvasStillOpen()
    }

    @Test
    fun changedNewTextContinueDialogBackAndOutsideKeepDraft() = withBoard {
        startNew("Unsaved note")
        back()
        assertDiscardDialog()
        composeRule.onNodeWithText("編集を続ける").performClick()
        assertEquals("Unsaved note", editor.draft.value?.text)
        back()
        assertDiscardDialog()
        tapOutsideDialog()
        assertEquals("Unsaved note", editor.draft.value?.text)
        assertNoDialog()
        back()
        assertDiscardDialog()
        dialogBack()
        assertNoDialog()
        assertEquals("Unsaved note", editor.draft.value?.text)
        assertEquals(listOf(note), rows().map { it.toModel() })
    }

    @Test
    fun changedNewWhitespaceRequiresConfirmationAndDiscardDoesNotCreateHistory() = withBoard {
        startNew("   \n")
        back()
        assertDiscardDialog()
        composeRule.onNodeWithText("破棄する").performClick()
        composeRule.waitForIdle()
        assertEquals(null, editor.draft.value)
        assertEquals(listOf(note), rows().map { it.toModel() })
        assertEquals(1, board.shapes.size)
        assertEquals(1, shapes().size)
        assertFalse(board.canUndo)
        assertFalse(board.canRedo)
        assertCanvasStillOpen()
    }

    @Test
    fun existingTextKindAndColorChangesDiscardWithoutChangingElementOrHistory() = withBoard {
        startExisting()
        composeRule.onNodeWithContentDescription("テキストを編集").performTextReplacement("Changed")
        composeRule.onNodeWithText("見出し").performClick()
        composeRule.onNodeWithText("朱").performClick()
        back()
        assertDiscardDialog()
        composeRule.onNodeWithText("破棄する").performClick()
        composeRule.waitForIdle()
        assertEquals(null, editor.draft.value)
        assertEquals(listOf(note), board.elements)
        assertEquals(listOf(note), rows().map { it.toModel() })
        assertFalse(board.canUndo)
    }

    @Test
    fun existingDraftRevertedToOriginalClosesWithoutConfirmation() = withBoard {
        startExisting()
        composeRule.onNodeWithContentDescription("テキストを編集").performTextReplacement("Temporary")
        composeRule.onNodeWithContentDescription("テキストを編集").performTextReplacement(note.text)
        back()
        assertNoDialog()
        assertEquals(null, editor.draft.value)
        assertEquals(listOf(note), rows().map { it.toModel() })
        assertFalse(board.canUndo)
    }

    @Test
    fun changedRegionNameContinuesAndBackDiscardsOnlyName() = withBoard {
        renameRegion()
        composeRule.onNodeWithContentDescription("囲みの名前").performTextReplacement("Renamed")
        back()
        assertDiscardDialog()
        composeRule.onNodeWithText("編集を続ける").performClick()
        assertEquals("Renamed", editor.regionNameDraft.value?.name)
        back()
        assertDiscardDialog()
        composeRule.onNodeWithText("破棄する").performClick()
        composeRule.waitForIdle()
        assertEquals(null, editor.draft.value)
        assertEquals("Cluster", board.shapes.single().name)
        assertEquals("Cluster", shapes().single().toModel().name)
        assertFalse(board.canUndo)
    }

    @Test
    fun newRegionNameDiscardLeavesCreatedRegionPersisted() = withBoard {
        composeRule.onNodeWithContentDescription("図形ツールを開く").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("囲み").performClick()
        tapCanvas(.78f, .74f)
        waitEditor("囲みの名前")
        composeRule.waitUntil(10_000) {
            shapes().size == 2 && sessions.saveStateFor(1L, BoardSnapshot()).value is BoardSaveState.Idle
        }
        composeRule.onNodeWithContentDescription("囲みの名前").performTextReplacement("New name")
        back()
        assertDiscardDialog()
        composeRule.onNodeWithText("破棄する").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) { shapes().size == 2 }
        assertEquals("The shape creation is saved independently of the draft name",
            2, board.shapes.size)
        assertEquals(2, shapes().size)
        assertEquals("", board.shapes.single { it.id != region.id }.name)
        assertTrue(board.canUndo)
    }

    @Test
    fun textAndDiscardConfirmationSurviveActivityRecreation() = withBoard {
        startNew("Survives recreation")
        back()
        scenario.recreate()
        composeRule.waitForIdle()
        assertDiscardDialog()
        assertEquals("Survives recreation", editor.draft.value?.text)
        dialogBack()
        assertNoDialog()
        assertEquals("Survives recreation", editor.draft.value?.text)
        assertEquals(listOf(note), rows().map { it.toModel() })
    }

    @Test
    fun regionNameSessionSurvivesRecreationWithoutAutoCommit() = withBoard {
        renameRegion()
        composeRule.onNodeWithContentDescription("囲みの名前").performTextReplacement("Draft name")
        scenario.recreate()
        composeRule.waitForIdle()
        waitEditor("囲みの名前")
        assertEquals("Draft name", editor.regionNameDraft.value?.name)
        assertEquals("Cluster", board.shapes.single().name)
        assertEquals("Cluster", shapes().single().toModel().name)
    }

    @Test
    fun runningSaveAndPendingAcknowledgementRejectBackWithoutRetryOrNavigation() = withBoard {
        val blocked = CompletableDeferred<Unit>()
        sessions.setSaveOperation { _, _ -> blocked }
        startNew("Saving")
        composeRule.onNodeWithText("完了").performClick()
        composeRule.waitUntil(5_000) { sessions.saveStateFor(1L, BoardSnapshot()).value is BoardSaveState.Running }
        val acknowledgement = editor.pendingDraftAcknowledgement.value
        assertTrue(acknowledgement != null && !acknowledgement.isCompleted)
        back()
        assertTrue(sessions.saveStateFor(1L, BoardSnapshot()).value is BoardSaveState.Running)
        assertEquals(acknowledgement, editor.pendingDraftAcknowledgement.value)
        assertCanvasStillOpen()
        assertEquals("Saving", board.elements.single { it.id != note.id }.text)
        blocked.complete(Unit)
    }

    @Test
    fun failedSaveBackDoesNotCloseOrAutomaticallyRetry() = withBoard {
        sessions.setSaveOperation { _, _ -> CompletableDeferred<Unit>().also {
            it.completeExceptionally(IllegalStateException("fixture failure"))
        } }
        startNew("Failed save")
        composeRule.onNodeWithText("完了").performClick()
        composeRule.waitUntil(5_000) { sessions.saveStateFor(1L, BoardSnapshot()).value is BoardSaveState.Failed }
        val failed = sessions.saveStateFor(1L, BoardSnapshot()).value
        back()
        assertEquals(failed, sessions.saveStateFor(1L, BoardSnapshot()).value)
        assertEquals("Failed save", board.elements.single { it.id != note.id }.text)
        assertTrue(composeRule.onAllNodesWithText("再試行").fetchSemanticsNodes().isNotEmpty())
        assertCanvasStillOpen()
    }

    @Test
    fun expandedToolsAndArmedInkExitInSeparateBackStages() = withBoard {
        composeRule.onNodeWithContentDescription("図形ツールを開く").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("ペン").assertExists()
        back()
        composeRule.onNodeWithContentDescription("図形ツールを開く").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("ペン").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1本指で描く ・ 2本指で移動").assertExists()
        back()
        composeRule.onNodeWithContentDescription("図形ツールを開く").assertExists()
        assertCanvasStillOpen()
    }

    @Test
    fun contextMenuSearchAndSelectionEachConsumeOneBack() = withBoard {
        composeRule.onNodeWithContentDescription(note.text).performTouchInput { longClick() }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("削除").fetchSemanticsNodes().isNotEmpty()
        }
        back()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("削除").fetchSemanticsNodes().size)
        assertCanvasStillOpen()

        composeRule.onNodeWithContentDescription("ボード内を検索").performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithContentDescription("ボード内を探す")
            .fetchSemanticsNodes().isNotEmpty())
        back()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("ボード内を探す")
            .fetchSemanticsNodes().size)
        assertCanvasStillOpen()

        composeRule.onNodeWithContentDescription(note.text).performClick()
        back()
        val state = composeRule.onNodeWithContentDescription(note.text).fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription)
        assertEquals("未選択", state)
        assertCanvasStillOpen()
    }

    @Test
    fun armedSpatialToolAndInkToolSurviveActivityRecreation() = withBoard {
        composeRule.onNodeWithContentDescription("図形ツールを開く").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("四角").performClick()
        composeRule.waitForIdle()
        scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("四角ツールを閉じる").assertExists()
        back()
        composeRule.onNodeWithContentDescription("図形ツールを開く").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("マーカー").performClick()
        scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1本指で描く ・ 2本指で移動").assertExists()
        back()
        assertCanvasStillOpen()
    }

    @Test
    fun fourSpatialToolsAndLassoRemainOneShotAfterAccessibleCompletion() = withBoard {
        fun runAction(label: String, actionLabel: String) {
            composeRule.waitUntil(10_000) {
                sessions.saveStateFor(1L, BoardSnapshot()).value is BoardSaveState.Idle
            }
            composeRule.onNodeWithContentDescription("図形ツールを開く").performClick()
            composeRule.waitForIdle()
            val node = composeRule.onNodeWithContentDescription(label).fetchSemanticsNode()
            val action = node.config[SemanticsActions.CustomActions]
                .first { it.label == actionLabel }
            composeRule.runOnUiThread { assertTrue(action.action()) }
            composeRule.waitForIdle()
            composeRule.waitUntil(10_000) {
                sessions.saveStateFor(1L, BoardSnapshot()).value is BoardSaveState.Idle
            }
            composeRule.onNodeWithContentDescription("図形ツールを開く").assertExists()
        }

        runAction("四角", "中央に四角を作成")
        assertEquals(2, board.shapes.size)
        runAction("丸", "中央に丸を作成")
        assertEquals(3, board.shapes.size)
        runAction("矢印", "中央に矢印を作成")
        assertEquals(1, board.arrows.size)
        runAction("まとめて選ぶ", "表示範囲をまとめて選択")
        assertEquals(3, board.shapes.size)
        assertEquals(1, board.arrows.size)
        assertCanvasStillOpen()

        runAction("囲み", "中央に囲みを作成")
        assertEquals(4, board.shapes.size)
        // Creation and its editable name are separate states; completing the name returns to neutral.
        composeRule.onNodeWithText("完了").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("図形ツールを開く").assertExists()
    }

    @Test
    fun penAndMarkerKeepArmedAcrossConsecutiveStrokes() = withBoard {
        composeRule.onNodeWithContentDescription("図形ツールを開く").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("ペン").performClick()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = instrumentation.targetContext.resources.displayMetrics
        fun draw(offset: Float) {
            val downTime = SystemClock.uptimeMillis()
            val points = listOf(
                (metrics.widthPixels * .22f + offset) to (metrics.heightPixels * .38f),
                (metrics.widthPixels * .26f + offset) to (metrics.heightPixels * .42f),
                (metrics.widthPixels * .30f + offset) to (metrics.heightPixels * .46f),
            )
            var downSent = false
            var terminalSent = false
            try {
                points.forEachIndexed { index, (x, y) ->
                    val properties = arrayOf(MotionEvent.PointerProperties().apply {
                        id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
                    })
                    val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                        this.x = x; this.y = y; pressure = 1f; size = 1f
                    })
                    val action = when (index) {
                        0 -> MotionEvent.ACTION_DOWN
                        points.lastIndex -> MotionEvent.ACTION_UP
                        else -> MotionEvent.ACTION_MOVE
                    }
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                        1, properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                        InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try {
                        val accepted = instrumentation.uiAutomation.injectInputEvent(event, true)
                        assertTrue(accepted)
                        if (action == MotionEvent.ACTION_DOWN) downSent = accepted
                        if (action == MotionEvent.ACTION_UP) terminalSent = true
                    } finally { event.recycle() }
                }
            } catch (failure: Throwable) {
                if (downSent && !terminalSent) {
                    try {
                        val point = points.last()
                        val cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_CANCEL, point.first, point.second, 0)
                        try { instrumentation.uiAutomation.injectInputEvent(cancel, true) }
                        finally { cancel.recycle() }
                    } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                }
                throw failure
            }
        }
        draw(0f)
        draw(70f)
        composeRule.waitUntil(10_000) {
            runBlocking { database.canvasDao().inkStrokes(1L).size == 2 } &&
                sessions.saveStateFor(1L, BoardSnapshot()).value is BoardSaveState.Idle
        }
        composeRule.onNodeWithText("マーカー").performClick()
        draw(140f)
        draw(210f)
        composeRule.waitUntil(10_000) {
            runBlocking { database.canvasDao().inkStrokes(1L).size == 4 }
        }
        assertEquals(4, runBlocking { database.canvasDao().inkStrokes(1L).size })
        assertTrue(composeRule.onAllNodesWithText("1本指で描く ・ 2本指で移動")
            .fetchSemanticsNodes().isNotEmpty())
        back()
        assertCanvasStillOpen()
    }

    @Test fun staleDiscardConfirmationCannotCancelReplacementSession() = withBoard {
        startNew("Old draft")
        back()
        assertDiscardDialog()
        scenario.onActivity {
            editor.draft.value = Draft(null, 12f, 34f, text = "Replacement")
        }
        composeRule.waitForIdle()
        assertNoDialog()
        assertEquals("Replacement", editor.draft.value?.text)
        assertEquals(listOf(note), rows().map { it.toModel() })
        assertFalse(board.canUndo)
    }

    @Test fun backDuringWetInkPreviewCancelsGestureAndLaterUpCannotCommit() = withBoard {
        verifyPreviewCancellation("ペン")
    }

    @Test fun backDuringSpatialPreviewCancelsLaterUpAndAllowsNextBlankTap() = withBoard {
        verifyPreviewCancellation("四角")
        tapCanvas(.78f, .74f)
        waitEditor("新しいテキスト")
        assertEquals(1, board.shapes.size)
    }

    private fun Harness.verifyPreviewCancellation(toolLabel: String) {
        val before = board.snapshot()
        composeRule.onNodeWithContentDescription("図形ツールを開く").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(toolLabel).performClick()
        val canvas = composeRule.onNodeWithContentDescription("キャンバス").fetchSemanticsNode()
        val origin = IntArray(2)
        scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
        val local = androidx.compose.ui.geometry.Offset(canvas.boundsInWindow.left + 160f,
            canvas.boundsInWindow.top + 180f)
        val x = origin[0] + local.x
        val y = origin[1] + local.y
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, px: Float, py: Float) {
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = px; this.y = py; pressure = 1f; size = 1f
            })
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        var downSent = false
        var terminalSent = false
        try {
            send(MotionEvent.ACTION_DOWN, x, y)
            downSent = true
            send(MotionEvent.ACTION_MOVE, x + 45f, y + 45f)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()
            send(MotionEvent.ACTION_UP, x + 90f, y + 90f)
            terminalSent = true
        } finally {
            if (downSent && !terminalSent) {
                runCatching { send(MotionEvent.ACTION_CANCEL, x + 45f, y + 45f) }
            }
        }
        composeRule.waitForIdle()
        assertEquals(before, board.snapshot())
        assertEquals(listOf(note), rows().map { it.toModel() })
        assertEquals(listOf(region), shapes().map { it.toModel() })
        assertFalse(board.canUndo)
        assertTrue(runBlocking { database.canvasDao().inkStrokes(1L).isEmpty() })
        assertCanvasStillOpen()
    }
}
