package com.thinkcanvas

import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.TextColor
import com.thinkcanvas.canvas.TextKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardSessionViewModelTest {
    private fun image() = com.thinkcanvas.canvas.ImageElement(
        assetId = "8c0a9b01-94fd-404c-944d-62c1249b4d01", x = 1f, y = 2f,
        width = 200f, height = 100f, intrinsicWidth = 400, intrinsicHeight = 200)

    @Test fun imageImportSharesSaveOwnerAndReentryAfterUndoCannotAddAnotherImage() {
        val sessions = testSessions()
        val image = image()
        val receipt = com.thinkcanvas.data.ShareImportReceiptRow("image-request", 1, image.id)
        val durable = CompletableDeferred<Unit>()
        var writes = 0
        sessions.setSaveOperation { _, _ -> error("image used ordinary save") }
        sessions.setShareSaveOperation { _, snapshot, seen ->
            writes++; assertEquals(receipt, seen); assertEquals(listOf(image), snapshot.images); durable
        }
        val ack = sessions.requestImageImport(1, BoardSnapshot(), receipt, image)!!
        assertSame(ack, sessions.requestImageImport(1, BoardSnapshot(), receipt, image))
        durable.complete(Unit)
        val board = sessions.stateFor(1, BoardSnapshot())
        assertTrue(board.undo()); assertFalse(board.canUndo)
        assertSame(ack, sessions.requestImageImport(1, BoardSnapshot(), receipt, image))
        assertTrue(board.images.isEmpty()); assertEquals(1, writes)
    }

    @Test fun imageSaveFailureRetriesSamePatchAndPublishesUndoRootsUntilDiscard() {
        val sessions = testSessions()
        val image = image()
        val receipt = com.thinkcanvas.data.ShareImportReceiptRow("image-request", 1, image.id)
        val roots = mutableMapOf<String, Set<String>>()
        sessions.setSaveOperation { _, _ -> CompletableDeferred(Unit) }
        sessions.setImageRootsOperation { owner, assets -> roots[owner] = assets }
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var writes = 0
        sessions.setShareSaveOperation { _, snapshot, seen ->
            assertEquals(receipt, seen); assertEquals(listOf(image), snapshot.images)
            if (++writes == 1) first else second
        }
        val ack = sessions.requestImageImport(1, BoardSnapshot(), receipt, image)!!
        first.completeExceptionally(IllegalStateException("故障"))
        assertTrue(sessions.saveStateFor(1, BoardSnapshot()).value is BoardSaveState.Failed)
        assertEquals(setOf(image.assetId), roots.values.single())
        sessions.retrySave(1); second.complete(Unit)
        assertTrue(ack.isCompleted)
        val board = sessions.stateFor(1, BoardSnapshot())
        board.undo()
        sessions.setSaveOperation { _, _ -> CompletableDeferred(Unit) }
        sessions.requestSave(1, board.snapshot())
        assertEquals(setOf(image.assetId), roots.values.single())
        sessions.discard(1)
        assertTrue(roots.values.single().isEmpty())
    }

    @Test fun shareAddsOneUndoAndReentryAfterUndoDoesNotReplayTheRequest() {
        val sessions = testSessions()
        val initial = BoardSnapshot()
        val request = com.thinkcanvas.share.ShareImportRequest(text = "shared", destinationId = 1,
            position = com.thinkcanvas.canvas.WorldPoint(30f, -40f))
        val receipt = com.thinkcanvas.data.ShareImportReceiptRow(request.requestId, 1, request.elementId)
        var writes = 0
        val durable = CompletableDeferred<Unit>()
        sessions.setShareSaveOperation { _, snapshot, observed ->
            writes++
            assertEquals(receipt, observed)
            assertEquals(listOf(request.element()), snapshot.texts)
            durable
        }
        sessions.setSaveOperation { _, _ -> error("share used ordinary save") }
        val ack = sessions.requestShareImport(1, initial, receipt, request.element())!!
        assertSame(ack, sessions.requestShareImport(1, initial, receipt, request.element()))
        assertEquals(1, writes)
        assertFalse(ack.isCompleted)
        durable.complete(Unit)
        assertTrue(ack.isCompleted)
        val board = sessions.stateFor(1, initial)
        assertTrue(board.undo())
        assertFalse(board.canUndo)
        assertTrue(board.canRedo)
        assertSame(ack, sessions.requestShareImport(1, initial, receipt, request.element()))
        assertTrue(board.elements.isEmpty())
        assertTrue(board.canRedo)
        assertEquals(1, writes)
    }

    @Test fun uncertainRestoredImportWaitsForManualRetryWithoutAnotherUndoOrRequest() {
        val sessions = testSessions()
        val request = com.thinkcanvas.share.ShareImportRequest(text = "restore", destinationId = 1,
            position = com.thinkcanvas.canvas.WorldPoint(-12f, 20f))
        val receipt = com.thinkcanvas.data.ShareImportReceiptRow(request.requestId, 1, request.elementId)
        val attempts = mutableListOf<BoardSnapshot>()
        val result = CompletableDeferred<Unit>()
        sessions.setSaveOperation { _, _ -> error("share used ordinary save") }
        sessions.setShareSaveOperation { _, snapshot, seen ->
            assertEquals(receipt, seen); attempts += snapshot; result
        }
        val ack = sessions.requestShareImport(1, BoardSnapshot(), receipt, request.element(), true)!!
        assertTrue(attempts.isEmpty())
        assertTrue(sessions.saveStateFor(1, BoardSnapshot()).value is BoardSaveState.Failed)
        assertSame(ack, sessions.requestShareImport(1, BoardSnapshot(), receipt, request.element(), true))
        sessions.retrySave(1)
        assertEquals(1, attempts.size)
        assertEquals(listOf(request.element()), attempts.single().texts)
        result.complete(Unit)
        assertTrue(ack.isCompleted)
        val board = sessions.stateFor(1, BoardSnapshot())
        assertTrue(board.undo())
        assertFalse(board.canUndo)
    }

    @Test fun failedShareRetryKeepsItsFixedElementAndCompletionSignal() {
        val sessions = testSessions()
        val request = com.thinkcanvas.share.ShareImportRequest(text = "retry", destinationId = 1,
            position = com.thinkcanvas.canvas.WorldPoint(8f, 9f))
        val receipt = com.thinkcanvas.data.ShareImportReceiptRow(request.requestId, 1, request.elementId)
        val attempts = mutableListOf<Pair<BoardSnapshot, com.thinkcanvas.data.ShareImportReceiptRow>>()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        sessions.setSaveOperation { _, _ -> error("share used ordinary save") }
        sessions.setShareSaveOperation { _, snapshot, seen ->
            attempts += snapshot to seen
            if (attempts.size == 1) first else second
        }
        val ack = sessions.requestShareImport(1, BoardSnapshot(), receipt, request.element())!!
        first.completeExceptionally(IllegalStateException("synthetic failure"))
        assertFalse(ack.isCompleted)
        assertEquals(1, attempts.size)
        sessions.retrySave(1)
        assertEquals(attempts[0], attempts[1])
        assertSame(ack, sessions.requestShareImport(1, BoardSnapshot(), receipt, request.element()))
        second.complete(Unit)
        assertTrue(ack.isCompleted)
        val board = sessions.stateFor(1, BoardSnapshot())
        assertTrue(board.undo()); assertFalse(board.canUndo)
    }

    @Test fun cancelingShareDoesNotCancelAnotherBoardsOrdinarySave() {
        val sessions = testSessions()
        val normal = CompletableDeferred<Unit>()
        val shared = CompletableDeferred<Unit>()
        sessions.setSaveOperation { _, _ -> normal }
        sessions.setShareSaveOperation { _, _, _ -> shared }
        sessions.stateFor(2, BoardSnapshot())
        sessions.requestSave(2, BoardSnapshot())
        val request = com.thinkcanvas.share.ShareImportRequest(text = "cancel", destinationId = 1,
            position = com.thinkcanvas.canvas.WorldPoint(0f, 0f))
        val receipt = com.thinkcanvas.data.ShareImportReceiptRow(request.requestId, 1, request.elementId)
        sessions.requestShareImport(1, BoardSnapshot(), receipt, request.element())
        sessions.cancelShareImport(1, "another request")
        assertFalse(shared.isCancelled)
        sessions.cancelShareImport(1, request.requestId)
        assertTrue(shared.isCancelled)
        assertFalse(normal.isCancelled)
        normal.complete(Unit)
        assertEquals(BoardSaveState.Idle, sessions.saveStateFor(2, BoardSnapshot()).value)
    }

    @Test fun viewportSessionsAreIsolatedRetainedAndDiscardedWithoutContentChanges() {
        val sessions = BoardSessionViewModel()
        val initial = BoardSnapshot()
        val a = sessions.viewportHistoryFor(1L, initial)
        a.resize(400f, 800f)
        a.initialize(com.thinkcanvas.canvas.Viewport())
        val origin = a.focus()
        a.viewportState.value = a.viewportState.value.pan(100f, 50f)
        assertTrue(a.record(origin))
        assertSame(a, sessions.viewportHistoryFor(1L, initial))
        val b = sessions.viewportHistoryFor(2L, initial)
        assertNotSame(a, b)
        assertFalse(b.canBack)
        assertEquals(initial, sessions.stateFor(1L, initial).snapshot())
        sessions.discard(1L)
        assertNotSame(a, sessions.viewportHistoryFor(1L, initial))
        assertSame(b, sessions.viewportHistoryFor(2L, initial))
    }

    private fun testSessions() = BoardSessionViewModel(
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    @Test fun reopeningBoardReusesUndoAndRedoHistory() {
        val sessions = BoardSessionViewModel()
        val state = sessions.stateFor(1L, BoardSnapshot())
        state.create("committed edit", TextKind.BODY, TextColor.INK, 10f, 20f)

        val reopened = sessions.stateFor(1L, state.snapshot())

        assertSame(state, reopened)
        assertTrue(reopened.canUndo)
        assertTrue(reopened.undo())
        assertTrue(reopened.elements.isEmpty())
        assertTrue(reopened.canRedo)
        assertTrue(reopened.redo())
        assertEquals("committed edit", reopened.elements.single().text)
    }

    @Test fun boardHistoriesRemainIsolatedAcrossSwitches() {
        val sessions = BoardSessionViewModel()
        val boardA = sessions.stateFor(1L, BoardSnapshot())
        val boardB = sessions.stateFor(2L, BoardSnapshot())
        boardA.create("A edit", TextKind.BODY, TextColor.INK, 0f, 0f)
        boardB.create("B edit", TextKind.BODY, TextColor.INK, 0f, 0f)

        assertTrue(sessions.stateFor(1L, boardA.snapshot()).undo())
        assertTrue(boardA.elements.isEmpty())
        assertEquals("B edit", sessions.stateFor(2L, boardB.snapshot()).elements.single().text)
        assertTrue(sessions.stateFor(2L, boardB.snapshot()).canUndo)
        assertTrue(sessions.stateFor(2L, boardB.snapshot()).undo())
        assertTrue(boardB.elements.isEmpty())
        assertTrue(sessions.stateFor(1L, BoardSnapshot()).canRedo)
    }

    @Test fun deletingBoardDiscardsOnlyItsSessionState() {
        val sessions = BoardSessionViewModel()
        val boardA = sessions.stateFor(1L, BoardSnapshot())
        val boardB = sessions.stateFor(2L, BoardSnapshot())
        boardA.create("A edit", TextKind.BODY, TextColor.INK, 0f, 0f)
        boardB.create("B edit", TextKind.BODY, TextColor.INK, 0f, 0f)

        sessions.discard(1L)

        val replacementA = sessions.stateFor(1L, BoardSnapshot())
        assertNotSame(boardA, replacementA)
        assertFalse(replacementA.canUndo)
        assertTrue(sessions.stateFor(2L, boardB.snapshot()).canUndo)
        assertSame(boardB, sessions.stateFor(2L, BoardSnapshot()))
    }

    @Test fun duplicateAndNewBoardStartWithIndependentEmptyHistory() {
        val sessions = BoardSessionViewModel()
        val source = sessions.stateFor(1L, BoardSnapshot())
        source.create("source content", TextKind.BODY, TextColor.INK, 3f, 4f)
        source.create("second edit", TextKind.TITLE, TextColor.VERMILION, 5f, 6f)
        source.undo()

        val duplicateSnapshot = source.snapshot()
        val duplicate = sessions.stateFor(2L, duplicateSnapshot)
        val newBoard = sessions.stateFor(3L, BoardSnapshot())

        assertEquals(duplicateSnapshot, duplicate.snapshot())
        assertFalse(duplicate.canUndo)
        assertFalse(duplicate.canRedo)
        assertTrue(newBoard.elements.isEmpty())
        assertFalse(newBoard.canUndo)
        assertTrue(source.canRedo)
    }

    @Test fun newSessionRestoresContentsWithoutRestoringHistory() {
        val firstSession = BoardSessionViewModel()
        val original = firstSession.stateFor(1L, BoardSnapshot())
        original.create("persisted content", TextKind.BODY, TextColor.INK, 7f, 8f)
        val persisted = original.snapshot()

        val nextSession = BoardSessionViewModel()
        val restored = nextSession.stateFor(1L, persisted)

        assertEquals(persisted, restored.snapshot())
        assertFalse(restored.canUndo)
        assertFalse(restored.canRedo)
    }

    @Test fun runningSaveSurvivesReopeningSessionAndCompletesToIdle() {
        val sessions = testSessions()
        val initial = BoardSnapshot()
        val saved = CompletableDeferred<Unit>()
        sessions.stateFor(1L, initial)
        sessions.setSaveOperation { _, _ -> saved }

        sessions.requestSave(1L, initial)
        assertTrue(sessions.saveStateFor(1L, initial).value is BoardSaveState.Running)

        sessions.stateFor(1L, initial) // Activity recreation reuses this ViewModel session.
        assertTrue(sessions.saveStateFor(1L, initial).value is BoardSaveState.Running)
        saved.complete(Unit)

        assertEquals(BoardSaveState.Idle, sessions.saveStateFor(1L, initial).value)
    }

    @Test fun immediateSuccessCompletesItsRequestAcknowledgement() {
        val sessions = testSessions()
        val snapshot = BoardSnapshot()
        sessions.stateFor(1L, snapshot)
        sessions.setSaveOperation { _, _ -> CompletableDeferred(Unit) }

        val acknowledgement = requireNotNull(sessions.requestSave(1L, snapshot))

        assertEquals(1L, acknowledgement.boardId)
        assertEquals(1L, acknowledgement.requestId)
        assertTrue(acknowledgement.isCompleted)
    }

    @Test fun failureDoesNotCompleteAcknowledgementAndRetryCompletesOriginalRequest() {
        val sessions = testSessions()
        val snapshot = BoardSnapshot()
        val first = CompletableDeferred<Unit>()
        val retry = CompletableDeferred<Unit>()
        var calls = 0
        sessions.stateFor(1L, snapshot)
        sessions.setSaveOperation { _, _ -> if (calls++ == 0) first else retry }

        val acknowledgement = requireNotNull(sessions.requestSave(1L, snapshot))
        first.completeExceptionally(IllegalStateException("write failed"))
        assertFalse(acknowledgement.isCompleted)
        assertTrue(sessions.saveStateFor(1L, snapshot).value is BoardSaveState.Failed)

        sessions.retrySave(1L)
        assertFalse(acknowledgement.isCompleted)
        retry.complete(Unit)

        assertTrue(acknowledgement.isCompleted)
        assertEquals(BoardSaveState.Idle, sessions.saveStateFor(1L, snapshot).value)
    }

    @Test fun coalescedRequestWaitsForTheCoveringSave() {
        val sessions = testSessions()
        val board = sessions.stateFor(1L, BoardSnapshot())
        val first = CompletableDeferred<Unit>()
        val covering = CompletableDeferred<Unit>()
        val calls = mutableListOf<BoardSnapshot>()
        sessions.setSaveOperation { _, snapshot ->
            calls += snapshot
            if (calls.size == 1) first else covering
        }
        board.create("first", TextKind.BODY, TextColor.INK, 1f, 2f)
        val oldSnapshot = board.snapshot()
        val oldAcknowledgement = requireNotNull(sessions.requestSave(1L, oldSnapshot))
        board.create("coalesced", TextKind.BODY, TextColor.INK, 3f, 4f)
        val requested = board.snapshot()
        val acknowledgement = requireNotNull(sessions.requestSave(1L, requested))

        first.complete(Unit)
        assertTrue(oldAcknowledgement.isCompleted)
        assertFalse(acknowledgement.isCompleted)
        assertEquals(listOf(oldSnapshot, requested), calls)

        covering.complete(Unit)
        assertTrue(acknowledgement.isCompleted)
    }

    @Test fun anotherBoardSaveCannotCompleteTheRequest() {
        val sessions = testSessions()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val a = BoardSnapshot()
        val b = BoardSnapshot()
        sessions.stateFor(1L, a)
        sessions.stateFor(2L, b)
        sessions.setSaveOperation { boardId, _ -> if (boardId == 1L) first else second }

        val acknowledgement = requireNotNull(sessions.requestSave(1L, a))
        val unrelated = requireNotNull(sessions.requestSave(2L, b))
        second.complete(Unit)

        assertTrue(unrelated.isCompleted)
        assertFalse(acknowledgement.isCompleted)
        first.complete(Unit)
        assertTrue(acknowledgement.isCompleted)
    }

    @Test fun eachRequestHasAnIndependentExactlyOnceAcknowledgement() = runBlocking {
        val sessions = testSessions()
        val snapshot = BoardSnapshot()
        val first = CompletableDeferred<Unit>()
        var calls = 0
        sessions.stateFor(1L, snapshot)
        sessions.setSaveOperation { _, _ -> calls++; first }

        val one = requireNotNull(sessions.requestSave(1L, snapshot))
        val two = requireNotNull(sessions.requestSave(1L, snapshot))
        assertNotSame(one, two)
        assertEquals(1L, one.requestId)
        assertEquals(2L, two.requestId)
        first.complete(Unit)
        assertTrue(one.isCompleted)
        assertTrue(two.isCompleted)

        two.await()
        two.await()
        one.await()
        assertTrue(one.isCompleted)
        assertTrue(two.isCompleted)
        assertEquals(1, calls)
    }

    @Test fun failureAfterReopeningSessionIsRetainedAndRetryable() {
        val sessions = testSessions()
        val initial = BoardSnapshot()
        val first = CompletableDeferred<Unit>()
        val retry = CompletableDeferred<Unit>()
        val calls = mutableListOf<BoardSnapshot>()
        sessions.stateFor(1L, initial)
        sessions.setSaveOperation { _, snapshot ->
            calls += snapshot
            if (calls.size == 1) first else retry
        }

        sessions.requestSave(1L, initial)
        sessions.stateFor(1L, initial)
        first.completeExceptionally(IllegalStateException("write failed"))
        assertEquals(BoardSaveState.Failed(initial), sessions.saveStateFor(1L, initial).value)

        sessions.retrySave(1L)
        sessions.retrySave(1L)
        assertEquals(2, calls.size)
        assertTrue(sessions.saveStateFor(1L, initial).value is BoardSaveState.Running)
        retry.complete(Unit)
        assertEquals(BoardSaveState.Idle, sessions.saveStateFor(1L, initial).value)
    }

    @Test fun latestDirtySnapshotIsSavedOnceAfterCurrentOperation() {
        val sessions = testSessions()
        val board = sessions.stateFor(1L, BoardSnapshot())
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val calls = mutableListOf<BoardSnapshot>()
        sessions.setSaveOperation { _, snapshot ->
            calls += snapshot
            if (calls.size == 1) first else second
        }
        board.create("A", TextKind.BODY, TextColor.INK, 1f, 2f)
        val snapshotA = board.snapshot()
        sessions.requestSave(1L, snapshotA)
        board.create("B", TextKind.BODY, TextColor.INK, 3f, 4f)
        val snapshotB = board.snapshot()
        board.create("C", TextKind.BODY, TextColor.INK, 5f, 6f)
        val newest = board.snapshot()

        sessions.requestSave(1L, snapshotB)
        sessions.requestSave(1L, newest)
        assertEquals(1, calls.size)
        first.complete(Unit)

        assertEquals(listOf(snapshotA, newest), calls)
        assertTrue(sessions.saveStateFor(1L, newest).value is BoardSaveState.Running)
        second.complete(Unit)
        assertEquals(BoardSaveState.Idle, sessions.saveStateFor(1L, newest).value)
    }

    @Test fun boardSaveLifecyclesRemainIsolated() {
        val sessions = testSessions()
        val a = BoardSnapshot()
        val b = BoardSnapshot()
        val saves = mapOf(1L to CompletableDeferred<Unit>(), 2L to CompletableDeferred<Unit>())
        sessions.stateFor(1L, a)
        sessions.stateFor(2L, b)
        sessions.setSaveOperation { boardId, _ -> requireNotNull(saves[boardId]) }

        sessions.requestSave(1L, a)
        sessions.requestSave(2L, b)
        saves.getValue(2L).complete(Unit)

        assertTrue(sessions.saveStateFor(1L, a).value is BoardSaveState.Running)
        assertEquals(BoardSaveState.Idle, sessions.saveStateFor(2L, b).value)
    }

    @Test fun discardingBoardPreventsDirtyFollowUpSave() {
        val sessions = testSessions()
        val board = sessions.stateFor(1L, BoardSnapshot())
        val first = CompletableDeferred<Unit>()
        var calls = 0
        sessions.setSaveOperation { _, _ -> calls++; first }
        sessions.requestSave(1L, board.snapshot())
        board.create("newest", TextKind.BODY, TextColor.INK, 1f, 2f)
        sessions.requestSave(1L, board.snapshot())

        sessions.discard(1L)
        first.complete(Unit)

        assertEquals(1, calls)
        assertEquals(BoardSaveState.Idle, sessions.saveStateFor(1L, BoardSnapshot()).value)
    }
}
