package com.thinkcanvas.share

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityService
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.BoardListActionState
import com.thinkcanvas.BoardListActionViewModel
import com.thinkcanvas.BoardSaveState
import com.thinkcanvas.BoardSessionViewModel
import com.thinkcanvas.MainActivity
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.Draft
import com.thinkcanvas.canvas.TextElement
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.CanvasDatabase
import com.thinkcanvas.data.CanvasStore
import com.thinkcanvas.data.ShareImportReceiptRow
import com.thinkcanvas.data.TextElementRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual MainActivity delivery/UI/Room, plus fresh-owner restoration (not an OS kill). */
@RunWith(AndroidJUnit4::class)
class ShareImportInteractionTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val note = TextElement(id = "share-baseline", text = "保存済みの考え", x = 140f, y = 210f)

    private fun shareIntent(text: String) = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    private inner class Harness(val scenario: ActivityScenario<MainActivity>, val database: CanvasDatabase) {
        var sessions: BoardSessionViewModel = owner()
        fun owner(): BoardSessionViewModel {
            lateinit var result: BoardSessionViewModel
            scenario.onActivity { result = ViewModelProvider(it)[BoardSessionViewModel::class.java] }
            return result
        }
        fun imports(): ShareImportViewModel {
            lateinit var result: ShareImportViewModel
            scenario.onActivity { result = ViewModelProvider(it)[ShareImportViewModel::class.java] }
            return result
        }
        val board get() = sessions.stateFor(1L, BoardSnapshot())
        fun rows(boardId: Long = 1) = runBlocking { database.canvasDao().elements(boardId) }
        fun send(text: String) = send(shareIntent(text))
        fun send(intent: Intent) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        fun awaitPreview(name: String = "一つ目") {
            awaitText("取り込み先: $name")
            composeRule.waitUntil(10_000) {
                var ready = false
                scenario.onActivity {
                    val state = ViewModelProvider(it)[ShareImportViewModel::class.java].state
                    ready = state.phase == ShareImportPhase.PREVIEW && !state.writing
                }
                ready
            }
            composeRule.onNodeWithText("取り込む").assertIsEnabled()
        }
        fun awaitPicker() {
            awaitText("取り込み先を選択")
            composeRule.waitUntil(10_000) {
                val state = imports().state
                state.phase == ShareImportPhase.PICKER && !state.writing
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("新しいボード").assertIsEnabled()
        }
        fun awaitEmpty() {
            composeRule.waitUntil(10_000) {
                var empty = false
                scenario.onActivity {
                    val state = ViewModelProvider(it)[ShareImportViewModel::class.java].state
                    empty = state.phase == ShareImportPhase.EMPTY && !state.writing
                }
                empty
            }
            composeRule.waitForIdle()
        }
        fun confirm() { composeRule.onNodeWithText("取り込む").performClick() }
        fun freshOwners() {
            // Retain the Android saved Bundle/task token but remove every in-memory owner.
            scenario.onActivity { it.viewModelStore.clear() }
            scenario.recreate()
            val newOwner = owner()
            assertNotSame(sessions, newOwner)
            sessions = newOwner
        }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
    }

    private fun withBoards(last: Long? = 1, payload: String? = null, empty: Boolean = false,
                           launchIntent: Intent? = null, initialized: Boolean = true,
                           block: Harness.() -> Unit) {
        runBlocking { CanvasStore.get(context).boards() } // Drain prior store commands before seeding.
        val database = CanvasDatabase.open(context)
        runBlocking {
            val dao = database.canvasDao()
            dao.boards().forEach { dao.deleteBoard(it.id) }
            if (!empty) {
                dao.putBoard(BoardRow(1, "一つ目", 1))
                dao.putBoard(BoardRow(2, "二つ目", 2))
                dao.replaceAll(1, listOf(TextElementRow.fromModel(1, note)), emptyList(), emptyList())
            }
        }
        val settings = context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE).edit()
            .putBoolean("initialized", initialized).putBoolean("guideDismissed", true)
        if (last == null) settings.remove("lastOpenedBoardId") else settings.putLong("lastOpenedBoardId", last)
        assertTrue(settings.commit())
        val scenario = ActivityScenario.launch<MainActivity>(launchIntent ?: payload?.let(::shareIntent)
            ?: Intent(context, MainActivity::class.java))
        var failure: Throwable? = null
        try { Harness(scenario, database).block() }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            try { scenario.close() }
            catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
            finally { database.close() }
        }
    }

    private fun back() {
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
    }

    private fun accessible(text: String): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.text?.toString() == text) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        var result: AccessibilityNodeInfo? = null
        composeRule.waitUntil(10_000) {
            result = find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
            result != null
        }
        return checkNotNull(result)
    }

    private fun clickAccessible(text: String) {
        var node: AccessibilityNodeInfo? = accessible(text)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("No native click action for $text", checkNotNull(node)
            .performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun warmShareUsesCurrentBoardAndWorldCenterWithOneUndoAndOneSave() = withBoards {
        awaitText("‹ 一つ目")
        composeRule.waitUntil(10_000) { sessions.viewportHistoryFor(1, BoardSnapshot()).focus() != null }
        val focus = checkNotNull(sessions.viewportHistoryFor(1, BoardSnapshot()).focus())
        // The displayed board must win even when the persisted last-board preference differs.
        context.getSharedPreferences("thinkcanvas.settings", Context.MODE_PRIVATE).edit()
            .putLong("lastOpenedBoardId", 2).commit()
        var saves = 0
        scenario.onActivity {
            sessions.setShareSaveOperation { id, snapshot, receipt ->
                saves++; CanvasStore.get(context).saveShare(id, snapshot, receipt)
            }
        }
        val existingOwner = sessions
        send("https://example.invalid/\n共有の文章")
        awaitPreview()
        assertSame(existingOwner, owner())
        assertEquals(listOf(note), rows().map { it.toModel() })
        assertFalse(board.canUndo)
        assertNotNull(accessible("https://example.invalid/\n共有の文章"))
        assertNotNull(accessible("取り込み先: 一つ目"))
        clickAccessible("取り込む")
        awaitEmpty()
        val imported = rows().map { it.toModel() }.single { it.id != note.id }
        assertEquals("https://example.invalid/\n共有の文章", imported.text)
        assertEquals(focus.centerX, imported.x, .01f)
        assertEquals(focus.centerY, imported.y, .01f)
        assertEquals(1, saves)
        assertTrue(rows(2).isEmpty())
        composeRule.onNodeWithContentDescription("戻す").performClick()
        composeRule.waitUntil(10_000) { rows().map { it.toModel() } == listOf(note) }
        assertFalse(board.canUndo); assertTrue(board.canRedo)
    }

    @Test fun coldShareUsesLastBoardAndAllowsDestinationChangeBeforeMutation() =
        withBoards(last = 2, payload = "cold text") {
            awaitPreview("二つ目")
            assertEquals(listOf(note), rows().map { it.toModel() }); assertTrue(rows(2).isEmpty())
            composeRule.onNodeWithText("取り込み先を変更").performClick()
            awaitPicker()
            composeRule.onNodeWithText("一つ目").performClick()
            awaitPreview()
            confirm(); awaitEmpty()
            assertEquals(2, rows().size); assertTrue(rows(2).isEmpty())
        }

    @Test fun missingLastBoardShowsPickerAndCancelPreservesContentAndHistory() =
        withBoards(last = null, payload = "cancel me") {
            awaitPicker()
            back(); awaitEmpty()
            assertEquals(listOf(note), rows().map { it.toModel() }); assertTrue(rows(2).isEmpty())
            assertFalse(board.canUndo); assertFalse(board.canRedo)
            scenario.recreate(); awaitEmpty()
            assertEquals(listOf(note), rows().map { it.toModel() })
        }

    @Test fun emptyShareStartDoesNotCreateBoardUntilExplicitCreate() =
        withBoards(last = null, payload = "first shared text", empty = true) {
            awaitPicker()
            awaitText("ボードがありません")
            assertTrue(runBlocking { database.canvasDao().boards() }.isEmpty())
            composeRule.onNodeWithText("新しいボード").assertIsEnabled().performClick()
            awaitPreview("無題のボード")
            val destination = checkNotNull(imports().state.request?.destinationId)
            assertTrue(rows(destination).isEmpty())
            confirm(); awaitEmpty()
            assertEquals("first shared text", rows(destination).single().text)
        }

    @Test fun busySecondShareDoesNotOverwriteAndFreshSameBodyIsASeparateRequest() = withBoards {
        awaitText("‹ 一つ目")
        val original = sessions
        val documentFlags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        send(shareIntent("same body").addFlags(documentFlags)); awaitPreview()
        assertSame(original, owner())
        val first = checkNotNull(imports().state.request)
        send(shareIntent("different busy body").addFlags(documentFlags))
        composeRule.waitForIdle()
        assertSame(original, owner())
        assertEquals(first.requestId, imports().state.request?.requestId)
        assertEquals("same body", imports().state.request?.text)
        confirm(); awaitEmpty()
        send(shareIntent("same body").addFlags(documentFlags)); awaitPreview()
        assertSame(original, owner())
        assertNotEquals(first.requestId, imports().state.request?.requestId)
        confirm(); awaitEmpty()
        assertEquals(2, rows().count { it.text == "same body" })
    }

    @Test fun retainedActivityDuringRunningKeepsOneApplicationAndCompletion() = withBoards(payload = "rotate") {
        awaitPreview()
        val gate = CompletableDeferred<Unit>()
        var snapshot: BoardSnapshot? = null
        var receipt: ShareImportReceiptRow? = null
        var calls = 0
        scenario.onActivity { sessions.setShareSaveOperation { _, value, imported ->
            calls++; snapshot = value; receipt = imported; gate
        } }
        confirm(); awaitText("保存しています")
        val original = sessions
        val request = checkNotNull(imports().state.request)
        scenario.recreate()
        assertSame(original, owner())
        assertEquals(request, imports().state.request)
        assertEquals(1, calls); assertEquals(2, board.elements.size)
        assertEquals(1, rows().size)
        runBlocking { CanvasStore.get(context).saveShare(1, checkNotNull(snapshot), checkNotNull(receipt)).await() }
        scenario.onActivity { gate.complete(Unit) }
        awaitEmpty()
        assertEquals(2, rows().size)
    }

    @Test fun freshOwnerWithUncommittedAcceptedPatchWaitsForManualRetry() = withBoards(payload = "restore retry") {
        awaitPreview()
        val gate = CompletableDeferred<Unit>()
        scenario.onActivity { sessions.setShareSaveOperation { _, _, _ -> gate } }
        confirm(); awaitText("保存しています")
        val accepted = checkNotNull(imports().state.request)
        freshOwners()
        awaitText("保存を再試行")
        assertTrue(gate.isCancelled)
        assertEquals(accepted, imports().state.request)
        assertEquals(1, rows().size)
        assertTrue(sessions.saveStateFor(1, BoardSnapshot()).value is BoardSaveState.Failed)
        composeRule.onNodeWithText("保存を再試行").performClick(); awaitEmpty()
        assertEquals(accepted.element(), rows().map { it.toModel() }.single { it.id == accepted.elementId })
        assertTrue(board.undo()); assertFalse(board.canUndo)
    }

    @Test fun durableReceiptBeforeUiAckPreventsReplayIntoAFreshOwner() = withBoards(payload = "ack gap") {
        awaitPreview()
        val gate = CompletableDeferred<Unit>()
        var snapshot: BoardSnapshot? = null
        var receipt: ShareImportReceiptRow? = null
        scenario.onActivity { sessions.setShareSaveOperation { _, value, imported ->
            snapshot = value; receipt = imported; gate
        } }
        confirm(); awaitText("保存しています")
        runBlocking { CanvasStore.get(context).saveShare(1, checkNotNull(snapshot), checkNotNull(receipt)).await() }
        assertEquals(2, rows().size)
        freshOwners(); awaitEmpty()
        assertEquals(2, rows().size)
        assertFalse(board.canUndo)
        assertEquals(checkNotNull(receipt), runBlocking { database.canvasDao().shareReceipt(checkNotNull(receipt).requestId) })
    }

    @Test fun completedReceiptOverridesStalePreviewAfterUndoWithoutReapplyingTheElement() =
        withBoards(payload = "do not resurrect") {
            awaitPreview()
            val preview = checkNotNull(imports().state.request)
            val token = imports().taskToken
            confirm(); awaitEmpty()
            assertEquals(2, rows().size)
            val receipt = checkNotNull(runBlocking { database.canvasDao().shareReceipt(preview.requestId) })
            composeRule.onNodeWithContentDescription("戻す").performClick()
            composeRule.waitUntil(10_000) { rows().map { it.toModel() } == listOf(note) }
            // Even a stale, unaccepted candidate for another board cannot override durable completion.
            runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                ShareImportCheckpoint(java.io.File(context.filesDir, "share-import"), token)
                    .write(preview.copy(destinationId = 2L))
            }
            freshOwners(); awaitEmpty()
            awaitText("‹ 一つ目")
            assertEquals(listOf(note), rows().map { it.toModel() })
            assertEquals(listOf(note), board.elements.toList())
            assertFalse(board.canUndo); assertFalse(board.canRedo)
            assertTrue(rows(2).isEmpty())
            assertEquals(receipt, runBlocking { database.canvasDao().shareReceipt(preview.requestId) })
            assertTrue(composeRule.onAllNodesWithText("取り込む").fetchSemanticsNodes().isEmpty())
        }

    @Test fun failedSaveRetriesTheSamePatchWithoutAddingAnotherUndo() = withBoards(payload = "failed") {
        awaitPreview()
        var calls = 0
        scenario.onActivity { sessions.setShareSaveOperation { id, snapshot, receipt ->
            calls++
            if (calls == 1) CompletableDeferred<Unit>().also { it.completeExceptionally(IllegalStateException("test failure")) }
            else CanvasStore.get(context).saveShare(id, snapshot, receipt)
        } }
        confirm(); awaitText("保存を再試行")
        val accepted = checkNotNull(imports().state.request)
        assertEquals(1, calls); assertEquals(1, rows().size)
        composeRule.onNodeWithText("保存を再試行").performClick(); awaitEmpty()
        assertEquals(2, calls)
        assertEquals(accepted.elementId, rows().single { it.text == "failed" }.id)
        assertTrue(board.undo()); assertFalse(board.canUndo)
    }

    @Test fun explicitFinishDoesNotCarryUnsavedImportIntoANewTask() = withBoards(payload = "discard on task close") {
        awaitPreview()
        val gate = CompletableDeferred<Unit>()
        scenario.onActivity { sessions.setShareSaveOperation { _, _, _ -> gate } }
        confirm(); awaitText("保存しています")
        val token = imports().taskToken
        scenario.close()
        assertTrue(gate.isCancelled)
        composeRule.waitUntil(10_000) {
            val record = java.io.File(context.filesDir, "share-import/$token.pending")
            record.isFile && record.length() == 5L
        }
        val next = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            awaitText("‹ 一つ目")
            lateinit var fresh: ShareImportViewModel
            next.onActivity { fresh = ViewModelProvider(it)[ShareImportViewModel::class.java] }
            composeRule.waitUntil(10_000) { fresh.state.phase == ShareImportPhase.EMPTY }
            assertNotEquals(token, fresh.taskToken)
            assertNull(fresh.state.request)
            assertEquals(listOf(note), rows().map { it.toModel() })
        } finally { next.close() }
    }

    @Test fun editorAndImeKeepTheirDraftUntilTheOriginalEditFinishes() = withBoards {
        awaitText("‹ 一つ目")
        val editor = sessions.textEditorFor(1, BoardSnapshot())
        scenario.onActivity { editor.draft.value = Draft(null, 60f, 100f, "未確定の編集") }
        composeRule.waitUntil(10_000) {
            var shown = false
            scenario.onActivity { shown = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            shown
        }
        val oldInput = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsActions.SetText].action!!
        send("deferred share")
        composeRule.waitUntil(10_000) { imports().state.phase == ShareImportPhase.DEFERRED && !imports().state.writing }
        assertEquals("未確定の編集", editor.draft.value?.text)
        assertTrue(composeRule.onAllNodesWithText("取り込む").fetchSemanticsNodes().isEmpty())
        assertEquals(1, rows().size)
        composeRule.onNodeWithContentDescription("完了").performClick()
        awaitPreview()
        scenario.onActivity { oldInput(AnnotatedString("古い入力callback")) }
        assertNull(editor.draft.value)
        assertEquals(2, rows().size)
        composeRule.onNodeWithText("キャンセル").performClick(); awaitEmpty()
        assertEquals(2, rows().size)
    }

    @Test fun capturedConfirmRechecksLiveSaveStateAndDoesNotImportWhileBlocked() = withBoards {
        awaitText("‹ 一つ目")
        send("stale confirm"); awaitPreview()
        val action = composeRule.onNodeWithText("取り込む").fetchSemanticsNode().config[SemanticsActions.OnClick]
        val gate = CompletableDeferred<Unit>()
        var shareCalls = 0
        scenario.onActivity {
            sessions.setSaveOperation { _, _ -> gate }
            sessions.setShareSaveOperation { id, snapshot, receipt ->
                shareCalls++; CanvasStore.get(context).saveShare(id, snapshot, receipt)
            }
            sessions.requestSave(1, board.snapshot())
            action.action?.invoke()
        }
        composeRule.waitForIdle()
        assertEquals(ShareImportPhase.PREVIEW, imports().state.phase)
        assertFalse(checkNotNull(imports().state.request).accepted)
        assertEquals(0, shareCalls); assertEquals(1, rows().size)
        scenario.onActivity { gate.complete(Unit) }
        composeRule.onNodeWithText("キャンセル").performClick(); awaitEmpty()
    }

    @Test fun unsupportedColdShareDoesNotCreateAnInitialBoard() = withBoards(
        last = null, empty = true, initialized = false,
        launchIntent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("image/png").putExtra(Intent.EXTRA_TEXT, "not plain text"),
    ) {
        awaitText("ボードはまだありません"); awaitEmpty()
        assertTrue(runBlocking { database.canvasDao().boards() }.isEmpty())
        scenario.recreate(); awaitEmpty()
        assertTrue(runBlocking { database.canvasDao().boards() }.isEmpty())
    }

    @Test fun manifestResolvesPlainTextShareAndLauncherWithoutClaimingImagesOrMultipleItems() {
        fun targets(action: String, type: String? = null, category: String = Intent.CATEGORY_DEFAULT) =
            context.packageManager.queryIntentActivities(Intent(action).setPackage(context.packageName)
                .setType(type).addCategory(category), PackageManager.MATCH_DEFAULT_ONLY)
                .filter { it.activityInfo.name == MainActivity::class.java.name }
        val plainTargets = targets(Intent.ACTION_SEND, "text/plain")
        assertEquals(1, plainTargets.size)
        assertEquals(ActivityInfo.DOCUMENT_LAUNCH_NEVER, plainTargets.single().activityInfo.documentLaunchMode)
        assertTrue(targets(Intent.ACTION_SEND, "image/png").isEmpty())
        assertTrue(targets(Intent.ACTION_SEND_MULTIPLE, "text/plain").isEmpty())
        assertNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
    }

    @Test fun checkpointWriteFailurePresentsManualRetryInsteadOfLoopingOrAcknowledging() {
        val directory = java.io.File(context.cacheDir, "share-write-failure-${java.util.UUID.randomUUID()}")
        val model = ShareImportViewModel()
        val owners = ViewModelStore()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owners.put("fault-test", model)
            model.initialize(directory, false, null, "private fault body")
        }
        fun awaitPhase(phase: ShareImportPhase) = composeRule.waitUntil(10_000) {
            model.state.phase == phase && !model.state.writing
        }
        fun breakDirectory() {
            checkNotNull(directory.listFiles()).forEach { assertTrue(it.delete()) }
            assertTrue(directory.delete())
            assertTrue(directory.createNewFile())
        }
        fun restoreDirectory() { assertTrue(directory.delete()); assertTrue(directory.mkdir()) }
        try {
            awaitPhase(ShareImportPhase.DEFERRED)
            breakDirectory()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.present(1) }
            awaitPhase(ShareImportPhase.FAILED)
            assertFalse(checkNotNull(model.state.request).accepted)
            restoreDirectory()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.retryOpening(); model.present(1) }
            awaitPhase(ShareImportPhase.PREVIEW)
            val preview = model.state.request
            breakDirectory()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.missingDestination() }
            awaitPhase(ShareImportPhase.FAILED)
            assertEquals(preview, model.state.request)
            restoreDirectory()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.retryOpening(); model.present(1) }
            awaitPhase(ShareImportPhase.PREVIEW)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                model.confirm(); model.accept(com.thinkcanvas.canvas.WorldPoint(10f, 20f))
            }
            awaitPhase(ShareImportPhase.ACCEPTED)
            val accepted = model.state.request
            breakDirectory()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.completeFromReceipt() }
            awaitPhase(ShareImportPhase.FAILED)
            assertEquals(accepted, model.state.request)
            restoreDirectory()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.completeFromReceipt() }
            awaitPhase(ShareImportPhase.EMPTY)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { owners.clear() }
            if (directory.isDirectory) checkNotNull(directory.listFiles()).forEach { assertTrue(it.delete()) }
            assertTrue(directory.delete())
        }
    }

    @Test fun historyLaunchWithoutSavedTokenDoesNotReplayOldPayload() = withBoards(
        payload = null, launchIntent = shareIntent("old history body")
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY),
    ) {
        awaitText("‹ 一つ目"); awaitEmpty()
        assertEquals(listOf(note), rows().map { it.toModel() })
        assertNull(imports().state.request)
        send(shareIntent("old history body").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        awaitEmpty()
        assertEquals(listOf(note), rows().map { it.toModel() })
        send("old history body"); awaitPreview(); confirm(); awaitEmpty()
        assertEquals(1, rows().count { it.text == "old history body" })
    }

    @Test fun invalidWarmExtrasAndMultipleItemsDoNotMutateOrOpenPreview() = withBoards {
        awaitText("‹ 一つ目")
        val invalid = listOf(
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain"),
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, 123),
            shareIntent(" \n\t"),
            shareIntent("multiple").setAction(Intent.ACTION_SEND_MULTIPLE),
        )
        invalid.forEach { send(it); awaitEmpty() }
        assertEquals(listOf(note), rows().map { it.toModel() })
        assertFalse(board.canUndo); assertFalse(board.canRedo)
        assertTrue(composeRule.onAllNodesWithText("取り込む").fetchSemanticsNodes().isEmpty())
    }

    @Test fun listRenameModalKeepsItsTextUntilSaveThenPresentsTheShare() = withBoards {
        awaitText("‹ 一つ目")
        composeRule.onNodeWithContentDescription("ボード一覧を開く").performClick()
        composeRule.onNodeWithContentDescription("一つ目、", substring = true)
            .performSemanticsAction(SemanticsActions.OnLongClick)
        awaitText("名前を変える")
        composeRule.onNodeWithText("名前を変える").performClick()
        awaitText("保存")
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.waitUntil(10_000) {
            var shown = false
            scenario.onActivity { shown = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            shown
        }
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        send("wait for rename")
        composeRule.waitUntil(10_000) { imports().state.phase == ShareImportPhase.DEFERRED && !imports().state.writing }
        assertTrue(composeRule.onAllNodesWithText("取り込む").fetchSemanticsNodes().isEmpty())
        assertEquals(listOf(note), rows().map { it.toModel() })
        composeRule.onNodeWithText("保存").performClick()
        awaitPreview("無題のボード")
        scenario.onActivity {
            assertFalse(it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true)
        }
        back(); awaitEmpty()
        assertEquals(listOf(note), rows().map { it.toModel() })
    }

    @Test fun ordinarySaveFailureKeepsTheShareDeferredUntilTheExistingRetryCompletes() = withBoards {
        awaitText("‹ 一つ目")
        var attempts = 0
        scenario.onActivity {
            sessions.setSaveOperation { id, snapshot ->
                attempts++
                if (attempts == 1) CompletableDeferred<Unit>().also {
                    it.completeExceptionally(IllegalStateException("ordinary test failure"))
                } else CanvasStore.get(context).save(id, snapshot)
            }
            sessions.requestSave(1, board.snapshot())
        }
        awaitText("保存できません。再試行")
        send("wait for normal retry")
        composeRule.waitUntil(10_000) { imports().state.phase == ShareImportPhase.DEFERRED && !imports().state.writing }
        assertTrue(sessions.saveStateFor(1, BoardSnapshot()).value is BoardSaveState.Failed)
        assertEquals(1, attempts)
        composeRule.onNodeWithText("保存できません。再試行").performClick()
        awaitPreview()
        assertEquals(2, attempts)
        assertEquals(listOf(note), rows().map { it.toModel() })
        composeRule.onNodeWithText("キャンセル").performClick(); awaitEmpty()
    }

    @Test fun searchAndToolRemainUsableWhileReceptionIsDeferred() = withBoards {
        awaitText("‹ 一つ目")
        composeRule.onNodeWithContentDescription("ボード内を検索").performClick()
        composeRule.onNodeWithContentDescription("ボード内を探す").performTextInput("考え")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("倍率を切り替える、", substring = true).performClick()
        composeRule.waitForIdle()
        val navigation = sessions.viewportHistoryFor(1, board.snapshot())
        val blockedFocus = navigation.focus()
        val nextResult = composeRule.onNodeWithContentDescription("次の検索結果").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        val gate = CompletableDeferred<Unit>()
        scenario.onActivity {
            sessions.setSaveOperation { _, _ -> gate }
            sessions.requestSave(1, board.snapshot())
            nextResult()
        }
        composeRule.waitForIdle()
        assertEquals(blockedFocus, navigation.focus())
        assertEquals(listOf(note), rows().map { it.toModel() })
        gate.complete(Unit)
        composeRule.waitUntil(10_000) { sessions.saveStateFor(1, board.snapshot()).value == BoardSaveState.Idle }
        scenario.onActivity { nextResult() }
        composeRule.waitForIdle()
        assertNotEquals(blockedFocus, navigation.focus())
        send("after search")
        composeRule.waitUntil(10_000) { imports().state.phase == ShareImportPhase.DEFERRED && !imports().state.writing }
        composeRule.onNodeWithContentDescription("検索を閉じる").performClick()
        awaitPreview(); composeRule.onNodeWithText("キャンセル").performClick(); awaitEmpty()
        composeRule.onNodeWithContentDescription("図形ツールを開く").performClick()
        composeRule.onNodeWithContentDescription("まとめて選ぶ").performClick()
        send("after selection tool")
        composeRule.waitUntil(10_000) { imports().state.phase == ShareImportPhase.DEFERRED && !imports().state.writing }
        back()
        awaitPreview(); composeRule.onNodeWithText("キャンセル").performClick(); awaitEmpty()
        assertEquals(listOf(note), rows().map { it.toModel() })
        assertFalse(board.canUndo)
    }

    @Test fun capturedCanvasActionsCannotChangeContentOrStartToolsBehindPreview() = withBoards {
        awaitText("‹ 一つ目")
        composeRule.waitUntil(10_000) { sessions.viewportHistoryFor(1, board.snapshot()).focus() != null }
        val navigation = sessions.viewportHistoryFor(1, board.snapshot())
        val focus = navigation.focus()
        val couldGoBack = navigation.canBack
        val couldGoForward = navigation.canForward
        val zoom = composeRule.onNodeWithContentDescription("倍率を切り替える、", substring = true)
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val search = composeRule.onNodeWithContentDescription("ボード内を検索").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        val tools = composeRule.onNodeWithContentDescription("図形ツールを開く").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        val gap = composeRule.onNodeWithContentDescription("キャンバス").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "中央から右に余白を作る" }.action
        val addSelection = composeRule.onNodeWithContentDescription(note.text).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "選択に追加" }.action
        send("block stale canvas"); awaitPreview()
        scenario.onActivity { zoom(); search(); tools(); assertFalse(gap()); assertFalse(addSelection()) }
        composeRule.waitForIdle()
        assertEquals(focus, navigation.focus())
        assertEquals(couldGoBack, navigation.canBack)
        assertEquals(couldGoForward, navigation.canForward)
        assertEquals(listOf(note), rows().map { it.toModel() }); assertFalse(board.canUndo)
        assertTrue(composeRule.onAllNodesWithText("取り込む").fetchSemanticsNodes().isNotEmpty())
        confirm(); awaitEmpty() // Starting a hidden tool/search would make admission stay blocked.
        assertEquals(2, rows().size)
    }

    @Test fun imagePreviewAndChooserReturnToTheSameOwnerBeforeTextImport() = withBoards {
        awaitText("‹ 一つ目")
        val original = sessions
        fun openImagePreview() {
            composeRule.waitUntil(10_000) {
                var idle = false
                scenario.onActivity {
                    idle = ViewModelProvider(it)[BoardListActionViewModel::class.java].state.value == BoardListActionState.Idle
                }
                idle
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("ボード一覧を開く").performClick()
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithContentDescription("一つ目、", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("一つ目、", substring = true)
                .performSemanticsAction(SemanticsActions.OnLongClick)
            composeRule.onNodeWithText("画像で共有").performClick()
            awaitText("ほかのアプリ")
            composeRule.waitUntil(10_000) {
                !composeRule.onNodeWithText("ほかのアプリ").fetchSemanticsNode().config
                    .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        openImagePreview()
        send("wait for image preview")
        composeRule.waitUntil(10_000) { imports().state.phase == ShareImportPhase.DEFERRED && !imports().state.writing }
        back(); awaitPreview(); composeRule.onNodeWithText("キャンセル").performClick(); awaitEmpty()
        // The preceding cancel leaves the list as the original navigation target.
        composeRule.onNodeWithContentDescription("一つ目、", substring = true).performClick()
        awaitText("‹ 一つ目")
        openImagePreview()
        composeRule.onNodeWithText("ほかのアプリ").performClick()
        composeRule.waitUntil(10_000) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            root != null && root.packageName?.toString() != context.packageName
        }
        send("from external chooser")
        awaitPreview()
        assertSame(original, owner())
        assertEquals(listOf(note), rows().map { it.toModel() })
        confirm(); awaitEmpty()
        assertEquals(2, rows().size)
    }
}
