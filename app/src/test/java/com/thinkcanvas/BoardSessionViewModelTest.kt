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
