package com.thinkcanvas.canvas

import android.content.Intent
import android.app.Dialog
import android.os.SystemClock
import android.widget.TextView
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
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
import com.thinkcanvas.test.PrSmoke
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
        fun dialogBack(hideKeyboard: Boolean = true) {
            // Read the focused dialog window, not the underlying Activity's insets.
            if (hideKeyboard) Espresso.onView(isRoot()).inRoot(isDialog())
                .perform(ViewActions.closeSoftKeyboard())
            composeRule.waitUntil(5_000) {
                var hidden = false
                Espresso.onView(isRoot()).inRoot(isDialog()).check { view, failure ->
                    if (failure != null) throw failure
                    val insets = checkNotNull(view.rootWindowInsets)
                    hidden = !insets.isVisible(WindowInsets.Type.ime()) &&
                        insets.getInsets(WindowInsets.Type.ime()).bottom == 0
                }
                hidden
            }
            Espresso.onView(isRoot()).inRoot(isDialog()).perform(ViewActions.pressBack())
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("編集内容を破棄しますか？")
                    .fetchSemanticsNodes().isEmpty()
            }
            waitEditorReady()
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
            assertNoDialog()
            waitEditorReady()
        }
        fun blockingWindow(aboveIme: Boolean = false): Dialog {
            lateinit var dialog: Dialog
            scenario.onActivity { activity ->
                dialog = Dialog(activity).apply {
                    setContentView(TextView(activity).apply { text = "Another window" })
                    if (aboveIme) window!!.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    show()
                }
            }
            composeRule.waitUntil(5_000) {
                var ownsFocus = false
                scenario.onActivity {
                    ownsFocus = dialog.window?.decorView?.hasWindowFocus() == true &&
                        !it.window.decorView.hasWindowFocus()
                }
                ownsFocus
            }
            return dialog
        }
        fun waitEditorReady(label: String = editor.draft.value?.let {
                if (it.id == null) "新しいテキスト" else "テキストを編集"
            } ?: "囲みの名前") {
            try { composeRule.waitUntil(10_000) {
                val nodes = composeRule.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
                val focused = nodes.singleOrNull()?.config?.getOrNull(SemanticsProperties.Focused) == true
                var ready = false
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    val input = activity.getSystemService(InputMethodManager::class.java)
                    val view = root.findFocus()
                    val insets = root.rootWindowInsets
                    ready = focused && root.hasWindowFocus() && view != null &&
                        insets?.isVisible(WindowInsets.Type.ime()) == true &&
                        insets.getInsets(WindowInsets.Type.ime()).bottom > 0 &&
                        input.isActive(view) && input.isAcceptingText
                }
                focused && ready
            } } catch (timeout: ComposeTimeoutException) {
                val nodes = composeRule.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
                val focused = nodes.singleOrNull()?.config?.getOrNull(SemanticsProperties.Focused)
                var native = ""
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    val input = activity.getSystemService(InputMethodManager::class.java)
                    val view = root.findFocus()
                    val insets = root.rootWindowInsets
                    native = "windowFocus=${root.hasWindowFocus()}, view=${view?.javaClass?.simpleName}, " +
                        "active=${view?.let { input.isActive(it) }}, accepting=${input.isAcceptingText}, " +
                        "imeVisible=${insets?.isVisible(WindowInsets.Type.ime())}, " +
                        "imeBottom=${insets?.getInsets(WindowInsets.Type.ime())?.bottom}"
                }
                throw AssertionError("Editor readiness: nodes=${nodes.size}, focused=$focused, $native", timeout)
            }
            composeRule.waitForIdle()
        }
        fun continueEditing() {
            composeRule.onNodeWithText("編集を続ける").performClick()
            waitEditorReady()
        }
        fun hideImeIfVisible() {
            composeRule.waitForIdle()
            Espresso.closeSoftKeyboard()
            composeRule.waitUntil(5_000) {
                var hidden = false
                scenario.onActivity {
                    val insets = checkNotNull(it.window.decorView.rootWindowInsets)
                    hidden = !insets.isVisible(WindowInsets.Type.ime()) &&
                        insets.getInsets(WindowInsets.Type.ime()).bottom == 0
                }
                hidden
            }
            composeRule.waitForIdle()
        }
        fun waitEditor(label: String) {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()
            }
            waitEditorReady()
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
            val actions = composeRule.onAllNodesWithContentDescription("囲み: Cluster")
                .fetchSemanticsNodes().single { it.config.contains(SemanticsActions.CustomActions) }
                .config[SemanticsActions.CustomActions]
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
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("編集内容を破棄しますか？")
                    .fetchSemanticsNodes().isNotEmpty()
            }
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

    @PrSmoke
    @androidx.test.filters.FlakyTest(bugId = 106)
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

    @androidx.test.filters.FlakyTest(bugId = 106)
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

    @PrSmoke
    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test
    fun changedNewTextContinueDialogBackAndOutsideKeepDraft() = withBoard {
        startNew("Unsaved note")
        back()
        assertDiscardDialog()
        continueEditing()
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

    @androidx.test.filters.FlakyTest(bugId = 106)
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

    @androidx.test.filters.FlakyTest(bugId = 106)
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

    @androidx.test.filters.FlakyTest(bugId = 106)
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

    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test
    fun changedRegionNameContinuesAndBackDiscardsOnlyName() = withBoard {
        renameRegion()
        composeRule.onNodeWithContentDescription("囲みの名前").performTextReplacement("Renamed")
        back()
        assertDiscardDialog()
        continueEditing()
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

    @androidx.test.filters.FlakyTest(bugId = 106)
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

    @PrSmoke
    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test
    fun textAndDiscardConfirmationSurviveActivityRecreation() = withBoard {
        startNew("Survives recreation")
        back()
        scenario.recreate()
        composeRule.waitForIdle()
        assertDiscardDialog()
        assertEquals("Survives recreation", editor.draft.value?.text)
        composeRule.onNodeWithContentDescription("新しいテキスト").assertIsNotFocused()
        dialogBack(hideKeyboard = false)
        assertNoDialog()
        assertEquals("Survives recreation", editor.draft.value?.text)
        assertEquals(listOf(note), rows().map { it.toModel() })
    }

    @androidx.test.filters.FlakyTest(bugId = 106)
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
        back()
        assertDiscardDialog()
        scenario.recreate()
        composeRule.waitForIdle()
        assertDiscardDialog()
        composeRule.onNodeWithContentDescription("囲みの名前").assertIsNotFocused()
        dialogBack(hideKeyboard = false)
        assertNoDialog()
        assertEquals("Draft name", editor.regionNameDraft.value?.name)
        assertEquals("Cluster", shapes().single().toModel().name)
    }

    @androidx.test.filters.FlakyTest(bugId = 106)
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

    @androidx.test.filters.FlakyTest(bugId = 106)
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

    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test
    fun penAndMarkerKeepArmedAcrossConsecutiveStrokes() = withBoard {
        composeRule.onNodeWithContentDescription("図形ツールを開く").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("ペン").performClick()
        fun draw(offset: Float) {
            composeRule.waitForIdle()
            val expectedCount = runBlocking { database.canvasDao().inkStrokes(1L).size } + 1
            val canvas = composeRule.onNodeWithContentDescription("キャンバス")
            val bounds = canvas.fetchSemanticsNode().boundsInRoot
            canvas.performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(bounds.width * .22f + offset, bounds.height * .38f),
                    androidx.compose.ui.geometry.Offset(bounds.width * .30f + offset, bounds.height * .46f),
                    durationMillis = 200)
            }
            composeRule.waitUntil(10_000) {
                runBlocking { database.canvasDao().inkStrokes(1L).size == expectedCount } &&
                    sessions.saveStateFor(1L, BoardSnapshot()).value is BoardSaveState.Idle
            }
            composeRule.waitForIdle()
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
        tapCanvas()
        waitEditor("新しいテキスト")
    }

    @androidx.test.filters.FlakyTest(bugId = 106)
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

    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test fun backDuringSpatialPreviewCancelsLaterUpAndAllowsNextBlankTap() = withBoard {
        verifyPreviewCancellation("四角")
        tapCanvas(.78f, .74f)
        waitEditor("新しいテキスト")
        assertEquals(1, board.shapes.size)
    }

    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test fun backBeforeSelectionPreviewRejectsOldTapMoveAndResize() = withBoard {
        for (label in listOf(note.text, "要素を移動", "移動", "サイズ変更")) {
            if (label == note.text || label == "要素を移動") {
                composeRule.onNodeWithContentDescription(note.text).performClick()
            } else {
                val target = composeRule.onAllNodesWithContentDescription("囲み: Cluster")
                    .fetchSemanticsNodes().single { it.config.contains(SemanticsActions.CustomActions) }
                composeRule.runOnUiThread { assertTrue(target.config[SemanticsActions.OnClick].action!!()) }
            }
            composeRule.waitForIdle()
            val point = composeRule.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInWindow.center
            stalePointerAfterBack(point, move = label != note.text)
            assertEquals(null, editor.draft.value)
            assertEquals("未選択",
                composeRule.onNodeWithContentDescription(note.text).fetchSemanticsNode().config
                    .getOrNull(SemanticsProperties.StateDescription))
            assertEquals(0, composeRule.onAllNodesWithContentDescription("移動").fetchSemanticsNodes().size)
            assertOriginalContent()
        }
        tapCanvas(.78f, .74f)
        waitEditor("新しいテキスト")
    }

    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test fun backBeforeCanvasTapClosesMenuSearchAndExpandedToolsWithoutForwarding() = withBoard {
        for (stage in listOf("menu", "search", "expanded")) {
            when (stage) {
                "menu" -> composeRule.onNodeWithContentDescription(note.text)
                    .performTouchInput { longClick() }
                "search" -> composeRule.onNodeWithContentDescription("ボード内を検索").performClick()
                else -> composeRule.onNodeWithContentDescription("図形ツールを開く").performClick()
            }
            composeRule.waitForIdle()
            if (stage == "search") waitEditorReady("ボード内を探す")
            hideImeIfVisible()
            val bounds = composeRule.onNodeWithContentDescription("キャンバス")
                .fetchSemanticsNode().boundsInWindow
            stalePointerAfterBack(Offset(bounds.left + bounds.width * .78f,
                bounds.top + bounds.height * .74f), move = false)
            assertEquals(null, editor.draft.value)
            val closedLabel = when (stage) { "menu" -> "削除"; "search" -> "ボード内を探す"; else -> "ペン" }
            assertEquals(0, composeRule.onAllNodesWithContentDescription(closedLabel).fetchSemanticsNodes().size)
            assertOriginalContent()
        }
        tapCanvas(.78f, .74f)
        waitEditor("新しいテキスト")
    }

    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test fun backDuringOutsideDownKeepsChangedDraftInConfirmationWithoutCommitting() = withBoard {
        startNew("Keep the pending outside tap")
        hideImeIfVisible()
        val bounds = composeRule.onNodeWithContentDescription("キャンバス")
            .fetchSemanticsNode().boundsInWindow
        stalePointerAfterBack(Offset(bounds.left + bounds.width * .78f,
            bounds.top + bounds.height * .74f), move = false)
        assertDiscardDialog()
        assertEquals("Keep the pending outside tap", editor.draft.value?.text)
        assertOriginalContent()
        continueEditing()
        assertEquals("Keep the pending outside tap", editor.draft.value?.text)
    }

    @PrSmoke
    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test fun editorWaitsForWindowOwnerAndDoesNotReshowHiddenImeOnWindowReturn() = withBoard {
        val first = blockingWindow()
        try {
            scenario.onActivity { editor.draft.value = Draft(null, 500f, 1200f, "Window owner draft") }
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("新しいテキスト").assertIsNotFocused()
            assertOriginalContent()
        } finally { scenario.onActivity { first.dismiss() } }
        waitEditorReady()
        assertEquals("Window owner draft", editor.draft.value?.text)

        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        composeRule.waitUntil(5_000) {
            var hidden = false
            scenario.onActivity {
                hidden = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == false
            }
            hidden
        }
        val second = blockingWindow()
        try { assertEquals("Window owner draft", editor.draft.value?.text) }
        finally { scenario.onActivity { second.dismiss() } }
        composeRule.waitUntil(5_000) {
            var ready = false
            scenario.onActivity {
                val root = it.window.decorView
                ready = root.hasWindowFocus() && root.findFocus()?.let { view ->
                    it.getSystemService(InputMethodManager::class.java).isActive(view)
                } == true
            }
            ready
        }
        composeRule.waitForIdle()
        scenario.onActivity {
            assertFalse(it.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()))
        }
        assertEquals("Window owner draft", editor.draft.value?.text)
        assertOriginalContent()
    }

    @PrSmoke
    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test fun lateWindowLossDuringInputFrameResumesTextRegionAndSearch() {
        for (label in listOf("新しいテキスト", "囲みの名前", "ボード内を探す")) withBoard {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val first = blockingWindow()
            var second: Dialog? = null
            var controlMask = 0
            var controller: WindowInsetsController? = null
            val listener = WindowInsetsController.OnControllableInsetsChangedListener { _, mask ->
                controlMask = mask
            }
            try {
                if (label == "ボード内を探す") {
                    composeRule.onNodeWithContentDescription("ボード内を検索").performClick()
                } else scenario.onActivity {
                    if (label == "新しいテキスト") {
                        editor.draft.value = Draft(null, 500f, 1200f, "Late window draft")
                    } else {
                        editor.regionNameDraft.value = RegionNameDraft(region.id, region.name)
                            .copy(name = "Late window name")
                    }
                }
                composeRule.waitForIdle()
                composeRule.onNodeWithContentDescription(label).assertIsNotFocused()
                val draft = editor.draft.value
                val regionDraft = editor.regionNameDraft.value
                composeRule.mainClock.autoAdvance = false
                scenario.onActivity {
                    controller = checkNotNull(it.window.decorView.windowInsetsController)
                    controller?.addOnControllableInsetsChangedListener(listener)
                    first.dismiss()
                }
                // The field is focused and the platform IME is controllable, while the
                // Compose frame awaited by the unfinished input request is still paused.
                composeRule.waitUntil(5_000) {
                    val focused = composeRule.onAllNodesWithContentDescription(label)
                        .fetchSemanticsNodes().singleOrNull()?.config
                        ?.getOrNull(SemanticsProperties.Focused) == true
                    var ready = false
                    scenario.onActivity {
                        ready = it.window.decorView.hasWindowFocus() &&
                            controlMask and WindowInsets.Type.ime() != 0
                    }
                    focused && ready
                }
                instrumentation.waitForIdleSync()
                second = blockingWindow()
                composeRule.mainClock.advanceTimeByFrame()
                instrumentation.waitForIdleSync()
                assertEquals(draft, editor.draft.value)
                assertEquals(regionDraft, editor.regionNameDraft.value)
                assertOriginalContent()
                scenario.onActivity { second?.dismiss() }
                composeRule.mainClock.autoAdvance = true
                waitEditorReady(label)
                assertEquals(draft, editor.draft.value)
                assertEquals(regionDraft, editor.regionNameDraft.value)
                assertOriginalContent()
            } finally {
                scenario.onActivity {
                    first.dismiss()
                    second?.dismiss()
                    controller?.removeOnControllableInsetsChangedListener(listener)
                }
                composeRule.mainClock.autoAdvance = true
                composeRule.waitForIdle()
            }
        }
    }

    @PrSmoke
    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test fun endedPendingEditorDoesNotRegainFocusBeforeRecomposition() = withBoard {
        val blocker = blockingWindow()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            scenario.onActivity { editor.draft.value = Draft(null, 500f, 1200f, "Pending input") }
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("新しいテキスト").assertIsNotFocused()
            composeRule.mainClock.autoAdvance = false
            scenario.onActivity {
                editor.draft.value = null
                blocker.dismiss()
            }
            composeRule.waitUntil(5_000) {
                var ownsFocus = false
                scenario.onActivity { ownsFocus = it.window.decorView.hasWindowFocus() }
                ownsFocus
            }
            instrumentation.waitForIdleSync()
            composeRule.waitForIdle()
            // The old field is still mounted until the next composition frame.
            composeRule.onNodeWithContentDescription("新しいテキスト").assertIsNotFocused()
            scenario.onActivity {
                assertFalse(it.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()))
            }
            assertEquals(null, editor.draft.value)
            assertOriginalContent()
        } finally {
            scenario.onActivity { blocker.dismiss() }
            composeRule.mainClock.autoAdvance = true
            composeRule.waitForIdle()
        }
        assertEquals(0, composeRule.onAllNodesWithContentDescription("新しいテキスト")
            .fetchSemanticsNodes().size)

        val replacementWindow = blockingWindow()
        try {
            scenario.onActivity { editor.draft.value = Draft(null, 500f, 1200f, "Old session") }
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("新しいテキスト").assertIsNotFocused()
            composeRule.mainClock.autoAdvance = false
            scenario.onActivity {
                editor.draft.value = Draft(null, 500f, 1200f, "Replacement session")
                replacementWindow.dismiss()
            }
            composeRule.waitUntil(5_000) {
                var ownsFocus = false
                scenario.onActivity { ownsFocus = it.window.decorView.hasWindowFocus() }
                ownsFocus
            }
            instrumentation.waitForIdleSync()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("新しいテキスト").assertIsNotFocused()
            assertOriginalContent()
        } finally {
            scenario.onActivity { replacementWindow.dismiss() }
            composeRule.mainClock.autoAdvance = true
            composeRule.waitForIdle()
        }
        waitEditorReady()
        assertEquals("Replacement session", editor.draft.value?.text)
        assertOriginalContent()
    }

    @PrSmoke
    @androidx.test.filters.FlakyTest(bugId = 106)
    @Test fun pendingInputIsCanceledByConfirmationAndAcceptedImeBack() = withBoard {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val blocker = blockingWindow()
        try {
            composeRule.onNodeWithContentDescription("ボード内を検索").performClick()
            scenario.onActivity {
                editor.regionNameDraft.value = RegionNameDraft(region.id, region.name).copy(name = "Pending name")
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("ボード内を探す").assertIsNotFocused()
            composeRule.onNodeWithContentDescription("囲みの名前").assertIsNotFocused()
            scenario.onActivity {
                assertFalse(it.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()))
                assertEquals(0, it.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom)
            }
            composeRule.mainClock.autoAdvance = false
            scenario.onActivity {
                it.onBackPressedDispatcher.onBackPressed()
                blocker.dismiss()
            }
            composeRule.waitUntil(5_000) {
                var focused = false
                scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
                focused
            }
            instrumentation.waitForIdleSync()
            composeRule.waitForIdle()
            // Confirmation was accepted, but its native window is not composed yet.
            composeRule.onNodeWithContentDescription("ボード内を探す").assertIsNotFocused()
            composeRule.onNodeWithContentDescription("囲みの名前").assertIsNotFocused()
            scenario.onActivity {
                assertFalse(it.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()))
            }
            assertEquals("Pending name", editor.regionNameDraft.value?.name)
            assertOriginalContent()
        } finally {
            scenario.onActivity { blocker.dismiss() }
            composeRule.mainClock.autoAdvance = true
            composeRule.waitForIdle()
        }
        assertDiscardDialog()
        dialogBack()

        // Keep the existing IME visible while another native window owns focus.
        val aboveIme = blockingWindow(aboveIme = true)
        lateinit var pending: RegionNameDraft
        try {
            scenario.onActivity {
                assertTrue(it.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()))
                pending = RegionNameDraft(region.id, "Pending name", originalName = region.name)
                editor.regionNameDraft.value = pending
            }
            composeRule.waitForIdle()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitUntil(5_000) {
                var hidden = false
                scenario.onActivity {
                    val insets = it.window.decorView.rootWindowInsets
                    hidden = !insets.isVisible(WindowInsets.Type.ime()) &&
                        insets.getInsets(WindowInsets.Type.ime()).bottom == 0
                }
                hidden
            }
            assertEquals(pending, editor.regionNameDraft.value)
            assertNoDialog()
        } finally { scenario.onActivity { aboveIme.dismiss() } }
        composeRule.waitUntil(5_000) {
            var ready = false
            scenario.onActivity {
                val root = it.window.decorView
                ready = root.hasWindowFocus() && root.findFocus()?.let { view ->
                    it.getSystemService(InputMethodManager::class.java).isActive(view)
                } == true
            }
            ready
        }
        composeRule.waitForIdle()
        scenario.onActivity {
            val insets = it.window.decorView.rootWindowInsets
            assertFalse(insets.isVisible(WindowInsets.Type.ime()))
            assertEquals(0, insets.getInsets(WindowInsets.Type.ime()).bottom)
        }
        assertEquals(pending, editor.regionNameDraft.value)
        assertOriginalContent()
    }

    private fun Harness.assertOriginalContent() {
        assertEquals(BoardSnapshot(listOf(note), listOf(region)), board.snapshot())
        assertEquals(listOf(note), rows().map { it.toModel() })
        assertEquals(listOf(region), shapes().map { it.toModel() })
        assertFalse(board.canUndo)
        assertCanvasStillOpen()
    }

    private fun Harness.stalePointerAfterBack(local: Offset, move: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val origin = IntArray(2)
        scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
        val screen = local + Offset(origin[0].toFloat(), origin[1].toFloat())
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, point: Offset, activity: MainActivity? = null) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                if (activity == null) assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                else assertTrue(activity.dispatchTouchEvent(event))
            } finally { event.recycle() }
        }
        try {
            send(MotionEvent.ACTION_DOWN, screen)
            composeRule.waitForIdle()
            scenario.onActivity {
                it.onBackPressedDispatcher.onBackPressed()
                // No recomposition/idle between Back and the stale MOVE/UP.
                if (move) send(MotionEvent.ACTION_MOVE, local + Offset(90f, 90f), it)
                send(MotionEvent.ACTION_UP, if (move) local + Offset(90f, 90f) else local, it)
            }
        } finally {
            send(MotionEvent.ACTION_CANCEL, screen)
        }
        composeRule.waitForIdle()
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
        fun send(action: Int, px: Float, py: Float, activity: MainActivity? = null) {
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = px; this.y = py; pressure = 1f; size = 1f
            })
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                if (activity == null) assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                else assertTrue(activity.dispatchTouchEvent(event))
            }
            finally { event.recycle() }
        }
        var downSent = false
        var terminalSent = false
        try {
            send(MotionEvent.ACTION_DOWN, x, y)
            downSent = true
            send(MotionEvent.ACTION_MOVE, x + 45f, y + 45f)
            composeRule.waitForIdle()
            scenario.onActivity {
                it.onBackPressedDispatcher.onBackPressed()
                // Dispatch through the Activity in the same UI turn: key cancellation
                // cannot run before this release. Coordinates are window-local here.
                send(MotionEvent.ACTION_UP, local.x + 90f, local.y + 90f, it)
            }
            // Clear the native input dispatcher's DOWN; the UI received its UP above.
            send(MotionEvent.ACTION_CANCEL, x + 90f, y + 90f)
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
