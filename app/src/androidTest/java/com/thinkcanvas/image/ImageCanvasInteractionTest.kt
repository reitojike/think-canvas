package com.thinkcanvas.image

import android.content.Intent
import android.view.WindowInsets
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.BoardSessionViewModel
import com.thinkcanvas.MainActivity
import com.thinkcanvas.canvas.*
import com.thinkcanvas.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageCanvasInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()

    private inner class Harness(val scenario: ActivityScenario<MainActivity>, val database: CanvasDatabase,
                                val sessions: BoardSessionViewModel, val original: ImageElement) {
        val board get() = sessions.stateFor(1, BoardSnapshot())
        fun rows() = runBlocking { database.canvasDao().images(1).map { it.toModel() } }
        fun saved() { compose.waitUntil(10_000) { rows() == board.images }; compose.waitForIdle() }
        fun actions(description: String = board.images.single().altText.ifBlank { "画像" }) =
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes()
                .first { it.config.getOrNull(SemanticsActions.CustomActions) != null }
                .config[SemanticsActions.CustomActions]
        fun action(label: String, description: String = board.images.single().altText.ifBlank { "画像" }) {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().any { node ->
                    node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == label } == true
                }
            }
            val action = actions(description).first { it.label == label }
            compose.runOnIdle { assertTrue(action.action()) }; saved()
        }
    }

    private fun withBoard(grouped: Boolean = false, block: Harness.() -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = seedImage(context)
        val seed = CanvasDatabase.open(context)
        if (grouped) runBlocking {
            val region = ShapeElement(id = "image-region", kind = ShapeKind.REGION, x = 100f, y = 200f,
                width = 400f, height = 300f, name = "画像のまとまり")
            val text = TextElement(id = "image-note", text = "注釈", x = 150f, y = 260f)
            val arrow = ArrowElement(id = "image-arrow", from = ArrowEnd.Attached(original.id, 1f, .5f),
                to = ArrowEnd.Free(450f, 350f))
            seed.canvasDao().replaceAll(1, listOf(TextElementRow.fromModel(1, text)),
                listOf(SpatialElementRow.fromModel(1, region)), listOf(ArrowElementRow.fromModel(1, arrow)),
                images = listOf(ImageElementRow.fromModel(1, original)))
        }
        seed.close()
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        val database = CanvasDatabase.open(context)
        try {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("テスト画像").fetchSemanticsNodes().any {
                    it.config.getOrNull(SemanticsActions.CustomActions) != null
                }
            }
            compose.waitForIdle()
            lateinit var sessions: BoardSessionViewModel
            scenario.onActivity { sessions = ViewModelProvider(it)[BoardSessionViewModel::class.java] }
            Harness(scenario, database, sessions, original).block()
        } finally { scenario.close(); database.close() }
    }

    @Test fun nativeMoveResizeDeleteAndUndoPreserveImageAndAspect() = withBoard {
        compose.onNodeWithContentDescription("テスト画像").performClick()
        action("右へ移動")
        assertEquals(original.x + 16f, rows().single().x, 0f)
        action("大きくする")
        assertEquals(2f, rows().single().width / rows().single().height, .0001f)
        action("小さくする")
        assertEquals(original.width, rows().single().width, .001f)
        action("削除")
        assertTrue(rows().isEmpty())
        compose.onNodeWithContentDescription("戻す").performClick()
        saved()
        assertEquals(original.assetId, rows().single().assetId)
        assertEquals(2f, rows().single().width / rows().single().height, .0001f)
    }

    @Test fun nativeLongPressMoveAndResizeKeepAspectWhileOrdinaryDragPans() = withBoard {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun drag(start: Offset, end: Offset, hold: Long) {
            val down = SystemClock.uptimeMillis()
            var ended = false
            val origin = IntArray(2)
            scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
            fun send(action: Int, point: Offset) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    point.x + origin[0], point.y + origin[1], 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, start)
            try {
                SystemClock.sleep(hold)
                repeat(4) { step ->
                    send(MotionEvent.ACTION_MOVE, start + (end - start) * ((step + 1) / 4f))
                    SystemClock.sleep(25)
                }
                send(MotionEvent.ACTION_UP, end)
                ended = true
            } finally { if (!ended) send(MotionEvent.ACTION_CANCEL, end) }
            compose.waitForIdle()
        }
        val center = compose.onNodeWithContentDescription("テスト画像").fetchSemanticsNode().boundsInWindow.center
        drag(center, center + Offset(70f, 35f), 650)
        saved()
        assertNotEquals(original.x, rows().single().x)
        val moved = rows().single()
        // Long-press moving an unselected element does not implicitly select it.
        val movedCenter = compose.onNodeWithContentDescription("テスト画像").fetchSemanticsNode().boundsInWindow.center
        drag(movedCenter, movedCenter, 30)
        val handle = compose.onNodeWithContentDescription("画像のサイズ変更").fetchSemanticsNode().boundsInWindow.center
        drag(handle, handle + Offset(70f, 35f), 30)
        saved()
        val resized = rows().single()
        assertEquals(moved.x, resized.x, .001f); assertEquals(moved.y, resized.y, .001f)
        assertTrue(resized.width > moved.width)
        assertEquals(2f, resized.width / resized.height, .0001f)
        val before = board.snapshot()
        val focus = sessions.viewportHistoryFor(1, before).focus()
        val at = compose.onNodeWithContentDescription("テスト画像").fetchSemanticsNode().boundsInWindow.center
        drag(at, at + Offset(50f, -40f), 30)
        saved()
        assertEquals(before, board.snapshot())
        assertNotEquals(focus, sessions.viewportHistoryFor(1, before).focus())
    }

    @Test fun oldImageActionsRejectRunningAndFailedSavesUntilExistingRetrySucceeds() = withBoard {
        val old = actions()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        scenario.onActivity { sessions.setSaveOperation { _, _ -> gate } }
        compose.runOnIdle { assertTrue(old.first { it.label == "右へ移動" }.action()) }
        compose.waitUntil(10_000) { sessions.saveStateFor(1, board.snapshot()).value is com.thinkcanvas.BoardSaveState.Running }
        val before = board.snapshot()
        fun rejected() = compose.runOnIdle {
            listOf("右へ移動", "大きくする", "削除", "代替テキストを編集").forEach { label ->
                assertFalse(old.first { it.label == label }.action())
            }
        }
        rejected()
        gate.completeExceptionally(IllegalStateException("故障"))
        compose.waitUntil(10_000) { sessions.saveStateFor(1, before).value is com.thinkcanvas.BoardSaveState.Failed }
        rejected(); assertEquals(before, board.snapshot())
        scenario.onActivity {
            sessions.setSaveOperation(CanvasStore.get(it)::save)
            sessions.retrySave(1)
        }
        saved()
        assertEquals(before, board.snapshot())
    }

    @Test fun selectedRegionAndImageMoveOnceAndAttachedArrowSurvivesDeleteUndo() = withBoard(grouped = true) {
        compose.onAllNodesWithContentDescription("囲み: 画像のまとまり").fetchSemanticsNodes()
            .first { it.config.getOrNull(SemanticsActions.CustomActions) != null }
            .config[SemanticsActions.OnClick].action!!.let { select -> compose.runOnIdle { assertTrue(select()) } }
        action("選択に追加")
        action("右へ移動")
        assertEquals(original.x + 16f, rows().single().x, 0f)
        assertEquals(166f, runBlocking { database.canvasDao().elements(1).single().x }, 0f)
        assertEquals(ArrowEnd.Free(450f, 350f), board.arrows.single().to)
        action("削除")
        assertTrue(board.arrows.isEmpty())
        compose.onNodeWithContentDescription("戻す").performClick(); saved()
        assertEquals(original.id, (board.arrows.single().from as ArrowEnd.Attached).targetId)
        assertEquals(original.assetId, rows().single().assetId)
    }

    @Test fun descriptionCancelDirtyBackEmptyCommitUndoAndRecreationKeepExpectedText() = withBoard {
        action("代替テキストを編集")
        compose.onNode(hasSetTextAction()).performTextReplacement("一時説明")
        compose.onNodeWithText("キャンセル").performClick()
        assertEquals(original.altText, rows().single().altText)
        assertFalse(board.canUndo)
        action("代替テキストを編集")
        val oldInput = checkNotNull(compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsActions.SetText].action)
        val oldDone = checkNotNull(compose.onNodeWithText("完了").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val oldCancel = checkNotNull(compose.onNodeWithText("キャンセル").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.onNode(hasSetTextAction()).performTextReplacement("破棄する説明")
        compose.runOnIdle {
            val editor = sessions.textEditorFor(1, board.snapshot())
            assertTrue(editor.imageDescriptionDraft.value!!.changed)
            assertEquals("破棄する説明", editor.imageDescriptionDraft.value!!.text)
        }
        // Observe the focused dialog's IME before testing hide-only Back. An initial
        // zero inset can precede a pending show and is not proof that hiding settled.
        compose.waitUntil(5_000) {
            var visible = false
            Espresso.onView(isRoot()).inRoot(isDialog()).check { view, failure ->
                if (failure != null) throw failure
                visible = checkNotNull(view.rootWindowInsets).isVisible(WindowInsets.Type.ime())
            }
            visible
        }
        Espresso.onView(isRoot()).inRoot(isDialog()).perform(ViewActions.pressBack())
        compose.waitUntil(5_000) {
            var hidden = false
            Espresso.onView(isRoot()).inRoot(isDialog()).check { view, failure ->
                if (failure != null) throw failure
                val insets = checkNotNull(view.rootWindowInsets)
                hidden = !insets.isVisible(WindowInsets.Type.ime()) &&
                    insets.getInsets(WindowInsets.Type.ime()).bottom == 0
            }
            hidden
        }
        compose.runOnIdle {
            assertEquals("破棄する説明", sessions.textEditorFor(1, board.snapshot()).imageDescriptionDraft.value?.text)
        }
        assertTrue(compose.onAllNodesWithText("編集内容を破棄しますか？").fetchSemanticsNodes().isEmpty())
        Espresso.onView(isRoot()).inRoot(isDialog()).perform(ViewActions.pressBack())
        compose.waitUntil(5_000) { compose.onAllNodesWithText("編集内容を破棄しますか？").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            oldInput(AnnotatedString("確認中の変更")); oldDone(); oldCancel()
            assertEquals("破棄する説明", sessions.textEditorFor(1, board.snapshot()).imageDescriptionDraft.value?.text)
            assertEquals(original.altText, board.images.single().altText)
            assertFalse(board.canUndo)
        }
        assertTrue(compose.onAllNodesWithText("編集内容を破棄しますか？").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("破棄する").performClick()
        assertEquals(original.altText, rows().single().altText)
        assertFalse(board.canUndo)
        action("代替テキストを編集")
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        compose.onNodeWithText("完了").performClick(); saved()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("代替テキストを編集").fetchSemanticsNodes().isEmpty() }
        assertEquals("", rows().single().altText)
        scenario.recreate(); compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("画像").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("戻す").performClick(); saved()
        assertEquals(original.altText, rows().single().altText)
        action("代替テキストを編集")
        val earlierDone = checkNotNull(compose.onNodeWithText("完了").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.onNode(hasSetTextAction()).performTextReplacement("最新の説明")
        compose.runOnIdle { earlierDone() }; saved()
        assertEquals("最新の説明", rows().single().altText)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("代替テキストを編集").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("戻す").performClick(); saved()
        assertEquals(original.altText, rows().single().altText)
    }

    @Test fun pendingBlankSingleCannotStartTextAfterImageDescriptionAction() = withBoard {
        val describe = actions().first { it.label == "代替テキストを編集" }
        val canvas = compose.onNodeWithContentDescription("キャンバス").fetchSemanticsNode()
        val configuration = canvas.layoutInfo.viewConfiguration
        val location = canvas.boundsInWindow.let { Offset(it.left + it.width * .1f, it.top + it.height * .23f) }
        val editor = sessions.textEditorFor(1, board.snapshot())
        val before = board.snapshot()
        val clock = compose.mainClock
        val autoAdvance = clock.autoAdvance
        val started = clock.currentTime
        try {
            clock.autoAdvance = false
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val origin = IntArray(2)
            scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    location.x + origin[0], location.y + origin[1], 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
            repeat(3) { clock.advanceTimeByFrame() }
            clock.advanceTimeBy(configuration.doubleTapTimeoutMillis - (clock.currentTime - started) - 1,
                ignoreFrameDuration = true)
            scenario.onActivity {
                assertNull(editor.draft.value)
                assertTrue(describe.action())
            }
            clock.advanceTimeBy(1, ignoreFrameDuration = true)
            scenario.onActivity { assertNull(editor.draft.value); assertNotNull(editor.imageDescriptionDraft.value) }
            clock.advanceTimeBy(configuration.doubleTapTimeoutMillis + 1)
        } finally { clock.autoAdvance = autoAdvance }
        compose.waitForIdle()
        assertNull(editor.draft.value)
        assertEquals(before, board.snapshot())
        assertFalse(board.canUndo)
        compose.onNodeWithText("キャンセル").performClick()
        assertEquals(before, board.snapshot())
    }

    @Test fun staleImageActionsAreRejectedWhileDescriptionEditorOwnsTheCanvas() = withBoard {
        val oldActions = actions()
        action("代替テキストを編集")
        val before = board.snapshot()
        compose.runOnIdle {
            listOf("右へ移動", "大きくする", "削除", "選択に追加").forEach { label ->
                assertFalse(oldActions.first { it.label == label }.action())
            }
        }
        assertEquals(before, board.snapshot())
        compose.onNodeWithText("キャンセル").performClick()
        assertEquals(before, board.snapshot())
    }

    @Test fun textShareWaitsForDescriptionEditorAndResumesAfterExplicitCancel() = withBoard {
        action("代替テキストを編集")
        compose.onNode(hasSetTextAction()).performTextReplacement("未確定の説明")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "画像説明の後に確認する共有")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        compose.waitUntil(10_000) {
            var deferred = false
            scenario.onActivity { deferred = ViewModelProvider(it)[com.thinkcanvas.share.ShareImportViewModel::class.java]
                .state.phase == com.thinkcanvas.share.ShareImportPhase.DEFERRED }
            deferred
        }
        assertTrue(compose.onAllNodesWithText("取り込む").fetchSemanticsNodes().isEmpty())
        assertEquals(original.altText, rows().single().altText)
        compose.onNodeWithText("キャンセル").performClick()
        compose.waitUntil(10_000) {
            var preview = false
            scenario.onActivity { preview = ViewModelProvider(it)[com.thinkcanvas.share.ShareImportViewModel::class.java]
                .state.phase == com.thinkcanvas.share.ShareImportPhase.PREVIEW }
            preview
        }
        assertEquals(original.altText, rows().single().altText)
        assertFalse(board.canUndo)
        compose.onNodeWithText("キャンセル").performClick()
    }
}
