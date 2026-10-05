package com.thinkcanvas.image

import android.content.Intent
import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinkcanvas.BoardSessionViewModel
import com.thinkcanvas.BoardListActionViewModel
import com.thinkcanvas.BoardListActionState
import com.thinkcanvas.MainActivity
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.WorldPoint
import com.thinkcanvas.data.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageImportInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()

    private inner class Harness(val scenario: ActivityScenario<MainActivity>, val store: CanvasStore,
                                val database: CanvasDatabase, val source: File,
                                val imports: ImageImportViewModel, val sessions: BoardSessionViewModel) {
        fun rows() = runBlocking { database.canvasDao().images(1).map { it.toModel() } }
        val board get() = sessions.stateFor(1, BoardSnapshot())
        fun start(uri: Uri? = Uri.fromFile(source), picker: ImagePickerSource = ImagePickerSource.PHOTO): ImageImportRequest {
            lateinit var request: ImageImportRequest
            scenario.onActivity {
                val focus = checkNotNull(sessions.viewportHistoryFor(1, BoardSnapshot()).focus())
                assertTrue(imports.begin(picker, 1, WorldPoint(focus.centerX, focus.centerY), 400f, 600f))
            }
            compose.waitUntil(10_000) { imports.state.phase == ImageImportPhase.PICKER }
            // Picker callback identity is tested independently of the OS's media provider UI.
            // Consume launch and deliver a real resolver URI atomically on the UI thread.
            scenario.onActivity {
                request = checkNotNull(imports.state.request)
                imports.consumeLaunch(request.requestId)
                imports.receive(request.requestId, picker, uri)
            }
            if (uri != null) {
                compose.waitUntil(10_000) { imports.state.phase == ImageImportPhase.ACCEPTED || imports.state.phase == ImageImportPhase.FAILED }
                if (imports.state.phase == ImageImportPhase.ACCEPTED) scenario.onActivity {
                    val accepted = checkNotNull(imports.state.request)
                    val ack = checkNotNull(sessions.requestImageImport(1, board.snapshot(),
                        ShareImportReceiptRow(accepted.requestId, 1, accepted.elementId), checkNotNull(accepted.accepted)))
                    imports.observeSave(ack, sessions.saveStateFor(1, board.snapshot()))
                }
            }
            return request
        }
        fun completed() {
            compose.waitUntil(10_000) { rows().size == 1 && imports.state.phase == ImageImportPhase.IDLE }
            compose.waitForIdle()
        }
    }

    private fun withBoard(block: Harness.() -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val seed = CanvasDatabase.open(context)
        runBlocking {
            if (seed.canvasDao().board(1) == null) seed.canvasDao().putBoard(BoardRow())
            seed.canvasDao().replaceAll(1, emptyList(), emptyList(), emptyList())
        }
        seed.close()
        showBoardOneAtStartup(context)
        val source = imageFixture(context)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        val database = CanvasDatabase.open(context)
        try {
            lateinit var imports: ImageImportViewModel
            lateinit var sessions: BoardSessionViewModel
            scenario.onActivity {
                sessions = ViewModelProvider(it)[BoardSessionViewModel::class.java]
            }
            compose.waitUntil(10_000) {
                var initialized = false
                scenario.onActivity { initialized = ViewModelProvider(it)[ImageImportViewModel::class.java]
                    .state.phase == ImageImportPhase.IDLE }
                initialized && sessions.viewportHistoryFor(1, BoardSnapshot()).focus() != null
            }
            scenario.onActivity {
                imports = ViewModelProvider(it)["image-intake-test", ImageImportViewModel::class.java]
                imports.initialize(CanvasStore.get(context), null)
            }
            compose.waitUntil(10_000) { imports.state.phase == ImageImportPhase.IDLE }
            compose.waitForIdle()
            Harness(scenario, CanvasStore.get(context), database, source, imports, sessions).block()
        } finally {
            scenario.onActivity {
                ViewModelProvider(it)["image-intake-test", ImageImportViewModel::class.java]
                    .finishTask(ViewModelProvider(it)[BoardSessionViewModel::class.java]) {
                        token -> CanvasStore.get(context).clearImageCheckpoint(token)
                    }
            }
            scenario.close(); database.close(); source.delete()
        }
    }

    @Test fun photoAndFileResultsUsePrivateCopyAndOneDurableReceipt() = withBoard {
        val request = start(picker = ImagePickerSource.FILE)
        completed()
        val image = rows().single()
        assertEquals(request.elementId, image.id)
        assertEquals(request.center.x, image.x + image.width / 2f, .001f)
        assertEquals(request.center.y, image.y + image.height / 2f, .001f)
        assertEquals(2f, image.width / image.height, .0001f)
        source.delete()
        runBlocking { store.withImageAssets(setOf(image.assetId)) { reader -> reader.decode(image.assetId, 256).recycle() } }
        assertEquals(ShareImportReceiptRow(request.requestId, 1, image.id),
            runBlocking { database.canvasDao().shareReceipt(request.requestId) })
    }

    @Test fun pickerCancellationDoesNotChangeContentHistoryOrSave() = withBoard {
        start(uri = null)
        compose.waitUntil(10_000) { imports.state.phase == ImageImportPhase.IDLE }
        assertTrue(rows().isEmpty())
        assertTrue(board.images.isEmpty())
        assertFalse(board.canUndo)
    }

    @Test fun actualFilePickerCallbackFlowsThroughMainIntoOneSavedImage() = withBoard {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT)
                    Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(source)))
                else null
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithContentDescription("図形ツールを開く").performClick()
            compose.onNodeWithContentDescription("画像を追加").performClick()
            compose.onNodeWithText("ファイルから").performClick()
            compose.waitUntil(10_000) { rows().size == 1 }
            compose.waitUntil(10_000) {
                var idle = false
                scenario.onActivity { idle = ViewModelProvider(it)[ImageImportViewModel::class.java]
                    .state.phase == ImageImportPhase.IDLE }
                idle
            }
            assertEquals(1, board.images.size)
            compose.onNodeWithContentDescription("戻す").performClick()
            compose.waitUntil(10_000) { rows().isEmpty() }
            assertFalse(board.canUndo)
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun actualPhotoPickerCancellationReturnsToCanvasWithoutAnEdit() = withBoard {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var launches = 0
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action in setOf("android.provider.action.PICK_IMAGES",
                        "androidx.activity.result.contract.action.PICK_IMAGES", Intent.ACTION_OPEN_DOCUMENT)) {
                    launches++
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithContentDescription("図形ツールを開く").performClick()
            compose.onNodeWithContentDescription("画像を追加").performClick()
            compose.onNodeWithText("写真から").performClick()
            compose.waitUntil(10_000) {
                var idle = false
                scenario.onActivity { idle = ViewModelProvider(it)[ImageImportViewModel::class.java]
                    .state.phase == ImageImportPhase.IDLE }
                launches == 1 && idle
            }
            assertTrue(rows().isEmpty()); assertFalse(board.canUndo)
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun duplicateAndOldResultAfterUndoCannotReplayCompletedRequest() = withBoard {
        val request = start()
        scenario.onActivity { imports.receive(request.requestId, ImagePickerSource.PHOTO, Uri.fromFile(source)) }
        completed()
        compose.onNodeWithContentDescription("戻す").performClick()
        compose.waitUntil(10_000) { rows().isEmpty() }
        scenario.onActivity { imports.receive(request.requestId, ImagePickerSource.PHOTO, Uri.fromFile(source)) }
        compose.waitForIdle()
        assertTrue(rows().isEmpty()); assertTrue(board.canRedo)
        assertEquals(request.elementId, runBlocking { database.canvasDao().shareReceipt(request.requestId) }!!.elementId)
    }

    @Test fun activityRecreationRetainsRequestAndOnlyOneImageAndUndo() = withBoard {
        val gate = CompletableDeferred<Unit>()
        scenario.onActivity { sessions.setShareSaveOperation { id, snapshot, receipt ->
            sessions.viewModelScope.async { gate.await(); store.saveShare(id, snapshot, receipt).await() }
        } }
        val request = start()
        scenario.recreate()
        scenario.onActivity { assertSame(imports, ViewModelProvider(it)["image-intake-test", ImageImportViewModel::class.java]) }
        gate.complete(Unit)
        completed()
        assertEquals(request.elementId, rows().single().id)
        compose.onNodeWithContentDescription("戻す").performClick()
        compose.waitUntil(10_000) { rows().isEmpty() }
        assertFalse(board.canUndo)
    }

    @Test fun failedSaveKeepsFixedImageAndExplicitRetryCommitsWithoutAnotherUndo() = withBoard {
        scenario.onActivity { sessions.setShareSaveOperation { _, _, _ ->
            CompletableDeferred<Unit>().also { it.completeExceptionally(IllegalStateException("故障")) }
        } }
        val request = start()
        compose.waitUntil(10_000) { imports.state.phase == ImageImportPhase.FAILED }
        assertTrue(rows().isEmpty())
        assertEquals(request.elementId, board.images.single().id)
        scenario.onActivity { sessions.setShareSaveOperation(store::saveShare) }
        scenario.onActivity { imports.retry(sessions) }
        completed()
        assertEquals(request.elementId, rows().single().id)
        compose.onNodeWithContentDescription("戻す").performClick()
        compose.waitUntil(10_000) { rows().isEmpty() }
        assertFalse(board.canUndo)
    }

    @Test fun freshOwnerRequiresManualRetryForAcceptedUncommittedCheckpoint() = withBoard {
        scenario.onActivity { sessions.setShareSaveOperation { _, _, _ ->
            CompletableDeferred<Unit>().also { it.completeExceptionally(IllegalStateException("故障")) }
        } }
        val request = start()
        compose.waitUntil(10_000) { imports.state.phase == ImageImportPhase.FAILED }
        lateinit var fresh: ImageImportViewModel
        lateinit var freshSessions: BoardSessionViewModel
        scenario.onActivity {
            // Remove every root from the old owner, including later UI publications.
            sessions.setImageRootsOperation { owner, _ -> store.setSessionImageRoots(owner, emptySet()) }
            fresh = ViewModelProvider(it)["image-restore-test", ImageImportViewModel::class.java]
            freshSessions = ViewModelProvider(it)["image-restore-board", BoardSessionViewModel::class.java]
            freshSessions.setSaveOperation(store::save)
            freshSessions.setShareSaveOperation(store::saveShare)
            freshSessions.setImageRootsOperation(store::setSessionImageRoots)
            fresh.initialize(store, request.taskToken)
        }
        compose.waitUntil(10_000) { fresh.state.phase == ImageImportPhase.FAILED }
        assertTrue(rows().isEmpty())
        assertTrue(freshSessions.stateFor(1, BoardSnapshot()).images.isEmpty())
        assertEquals(imports.state.request, fresh.state.request)
        val assetId = checkNotNull(fresh.state.request?.accepted).assetId
        // prepare performs collection before the reader obtains a lease: checkpoint alone protects it.
        runBlocking {
            store.prepareImageTask(request.taskToken)
            store.withImageAssets(setOf(assetId)) { it.decode(assetId, 256).recycle() }
        }
        scenario.onActivity {
            fresh.retry(freshSessions)
            val accepted = checkNotNull(fresh.state.request)
            val ack = checkNotNull(freshSessions.requestImageImport(1, BoardSnapshot(),
                ShareImportReceiptRow(accepted.requestId, 1, accepted.elementId), checkNotNull(accepted.accepted)))
            fresh.observeSave(ack, freshSessions.saveStateFor(1, BoardSnapshot()))
        }
        compose.waitUntil(10_000) { fresh.state.phase == ImageImportPhase.IDLE && rows().size == 1 }
        assertEquals(request.elementId, rows().single().id)
        val restoredBoard = freshSessions.stateFor(1, BoardSnapshot())
        compose.runOnIdle {
            assertTrue(restoredBoard.undo()); assertFalse(restoredBoard.canUndo)
            freshSessions.requestSave(1, restoredBoard.snapshot())
        }
        compose.waitUntil(10_000) { rows().isEmpty() }
        runBlocking {
            store.prepareImageTask(request.taskToken)
            store.withImageAssets(setOf(assetId)) { it.decode(assetId, 256).recycle() }
        }
    }

    @Test fun restoredCompletedReceiptAfterUndoDoesNotReapplyAcceptedPatch() = withBoard {
        val request = start()
        completed()
        val accepted = request.copy(accepted = rows().single())
        compose.onNodeWithContentDescription("戻す").performClick()
        compose.waitUntil(10_000) { rows().isEmpty() }
        runBlocking { store.writeImageCheckpoint(request.taskToken, accepted) }
        lateinit var fresh: ImageImportViewModel
        scenario.onActivity {
            fresh = ViewModelProvider(it)["image-restore-test", ImageImportViewModel::class.java]
            fresh.initialize(store, request.taskToken)
        }
        compose.waitUntil(10_000) { fresh.state.phase == ImageImportPhase.IDLE }
        assertTrue(rows().isEmpty()); assertTrue(board.canRedo)
    }

    @Test fun restoredImportWithDeletedDestinationRetiresCheckpointAndAllowsAnotherImport() = withBoard {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { assertTrue(store.rename(1, "画像の追加先")) }
        lateinit var primary: ImageImportViewModel
        lateinit var accepted: ImageImportRequest
        scenario.onActivity { primary = ViewModelProvider(it)[ImageImportViewModel::class.java] }
        val autoAdvance = compose.mainClock.autoAdvance
        try {
            // Keep Main from applying the accepted patch before losing all memory
            // owners. The Android saved Bundle retains the trusted task token.
            compose.mainClock.autoAdvance = false
            scenario.onActivity {
                assertTrue(primary.begin(ImagePickerSource.FILE, 1, WorldPoint(200f, 300f), 400f, 600f))
            }
            compose.waitUntil(10_000) { primary.state.phase == ImageImportPhase.PICKER }
            scenario.onActivity {
                val request = checkNotNull(primary.state.request)
                assertTrue(primary.consumeLaunch(request.requestId))
                primary.receive(request.requestId, ImagePickerSource.FILE, Uri.fromFile(source))
            }
            compose.waitUntil(10_000) { primary.state.phase == ImageImportPhase.ACCEPTED }
            accepted = checkNotNull(primary.state.request)
            assertTrue(rows().isEmpty())
            scenario.onActivity { it.viewModelStore.clear() }
            scenario.recreate()
        } finally { compose.mainClock.autoAdvance = autoAdvance }
        lateinit var restored: ImageImportViewModel
        lateinit var freshSessions: BoardSessionViewModel
        scenario.onActivity {
            restored = ViewModelProvider(it)[ImageImportViewModel::class.java]
            freshSessions = ViewModelProvider(it)[BoardSessionViewModel::class.java]
        }
        compose.waitUntil(10_000) { restored.state.phase == ImageImportPhase.FAILED }
        compose.waitForIdle()
        assertNotSame(primary, restored)
        assertEquals(accepted, restored.state.request)
        assertTrue(rows().isEmpty())
        compose.onNodeWithText("閉じる").performClick()
        compose.onNodeWithContentDescription("ボード一覧を開く").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("画像の追加先、", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("画像の追加先、", substring = true)
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithText("削除").performClick()
        compose.onNodeWithText("削除").performClick()
        compose.waitUntil(10_000) { runBlocking { database.canvasDao().board(1) == null } }
        compose.waitUntil(10_000) {
            var idle = false
            scenario.onActivity { idle = ViewModelProvider(it)[BoardListActionViewModel::class.java].state.value == BoardListActionState.Idle }
            idle
        }
        compose.waitForIdle()
        val assetId = checkNotNull(accepted.accepted).assetId
        val assetFile = File(context.filesDir, "image-assets/$assetId.img")
        assertTrue(assetFile.exists()) // The accepted checkpoint still protects it.
        compose.onNodeWithContentDescription("新しいボード").performClick()
        compose.waitUntil(10_000) { runBlocking { store.lastOpenedBoard()?.details?.id?.let { it != 1L } == true } }
        val newBoardId = checkNotNull(runBlocking { store.lastOpenedBoard() }).details.id
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("図形ツールを開く").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("図形ツールを開く").performClick()
        compose.onNodeWithContentDescription("画像を追加").performClick()
        compose.onNodeWithText("ファイルから").performClick()
        compose.onNodeWithText("再試行").performClick()
        compose.waitUntil(10_000) {
            restored.state.phase == ImageImportPhase.FAILED && restored.state.request == null
        }
        assertFalse(assetFile.exists())
        assertNull(runBlocking { store.prepareImageTask(accepted.taskToken) })
        assertTrue(runBlocking { database.canvasDao().images(newBoardId) }.isEmpty())
        assertFalse(freshSessions.stateFor(newBoardId, BoardSnapshot()).canUndo)
        compose.onNodeWithText("閉じて選び直す").performClick()
        compose.waitUntil(10_000) { restored.state.phase == ImageImportPhase.IDLE }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("画像を取り込めません").fetchSemanticsNodes().isEmpty() }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT)
                    Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(source)))
                else null
        }
        instrumentation.addMonitor(monitor)
        try {
            // The prior image action closed its tool menu; start a new intake.
            compose.onNodeWithContentDescription("図形ツールを開く").performClick()
            compose.onNodeWithContentDescription("画像を追加").performClick()
            compose.onNodeWithText("ファイルから").performClick()
            compose.waitUntil(10_000) {
                restored.state.phase == ImageImportPhase.IDLE &&
                    runBlocking { database.canvasDao().images(newBoardId) }.size == 1
            }
            val image = runBlocking { database.canvasDao().images(newBoardId) }.single().toModel()
            assertNotEquals(accepted.elementId, image.id)
            assertNotEquals(assetId, image.assetId)
            assertNull(runBlocking { database.canvasDao().shareReceipt(accepted.requestId) })
            scenario.onActivity { restored.receive(accepted.requestId, ImagePickerSource.FILE, Uri.fromFile(source)) }
            compose.waitForIdle()
            assertEquals(listOf(image), runBlocking { database.canvasDao().images(newBoardId) }.map { it.toModel() })
            compose.onNodeWithContentDescription("戻す").performClick()
            compose.waitUntil(10_000) { runBlocking { database.canvasDao().images(newBoardId) }.isEmpty() }
            assertFalse(freshSessions.stateFor(newBoardId, BoardSnapshot()).canUndo)
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
