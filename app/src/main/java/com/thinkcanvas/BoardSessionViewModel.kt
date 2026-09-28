package com.thinkcanvas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.BoardState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface BoardSaveState {
    data object Idle : BoardSaveState
    data class Running(val snapshot: BoardSnapshot) : BoardSaveState
    data class Failed(val snapshot: BoardSnapshot) : BoardSaveState
}

/** A request-specific signal that completes only after a durable save covers this request. */
class BoardSaveAcknowledgement internal constructor(
    val boardId: Long,
    val requestId: Long,
    private val completion: Deferred<Unit>,
) {
    val isCompleted: Boolean get() = completion.isCompleted

    suspend fun await() = completion.await()
}

/** Owns per-board edit history and save lifecycle for the Activity's process session. */
class BoardSessionViewModel(
    testSaveScope: CoroutineScope? = null,
) : ViewModel() {
    private val saveScope = testSaveScope ?: viewModelScope
    private class Session(initial: BoardSnapshot) {
        val board = BoardState(initial.texts, initial.shapes, initial.arrows, initial.ink)
        val saveState = MutableStateFlow<BoardSaveState>(BoardSaveState.Idle)
        var latestDirtySnapshot: BoardSnapshot? = null
        var inFlight: Job? = null
        var nextRequestId = 0L
        var nextAttemptId = 0L
        var activeAttemptId: Long? = null
        var activeSnapshot: BoardSnapshot? = null
        val pendingAcknowledgements = linkedMapOf<Long, PendingAcknowledgement>()
    }

    private data class PendingAcknowledgement(
        val acknowledgement: BoardSaveAcknowledgement,
        val completion: CompletableDeferred<Unit>,
        var targetAttemptId: Long,
    )

    private val sessions = mutableMapOf<Long, Session>()
    private var saveOperation: ((Long, BoardSnapshot) -> kotlinx.coroutines.Deferred<Unit>)? = null

    fun stateFor(boardId: Long, initial: BoardSnapshot): BoardState =
        sessionFor(boardId, initial).board

    fun saveStateFor(boardId: Long, initial: BoardSnapshot): StateFlow<BoardSaveState> =
        sessionFor(boardId, initial).saveState

    fun setSaveOperation(operation: (Long, BoardSnapshot) -> kotlinx.coroutines.Deferred<Unit>) {
        saveOperation = operation
    }

    fun requestSave(boardId: Long, snapshot: BoardSnapshot): BoardSaveAcknowledgement? {
        val session = sessions[boardId] ?: return null
        val requestId = ++session.nextRequestId
        val completion = CompletableDeferred<Unit>()
        val acknowledgement = BoardSaveAcknowledgement(boardId, requestId, completion)
        val pending = PendingAcknowledgement(acknowledgement, completion, 0L)
        session.pendingAcknowledgements[requestId] = pending
        when (session.saveState.value) {
            is BoardSaveState.Running -> {
                val activeAttemptId = checkNotNull(session.activeAttemptId)
                if (snapshot == session.activeSnapshot && session.latestDirtySnapshot == null) {
                    pending.targetAttemptId = activeAttemptId
                } else {
                    session.latestDirtySnapshot = snapshot
                    pending.targetAttemptId = activeAttemptId + 1
                }
            }
            is BoardSaveState.Failed -> {
                session.saveState.value = BoardSaveState.Failed(snapshot)
                pending.targetAttemptId = session.nextAttemptId + 1
            }
            BoardSaveState.Idle -> startSave(boardId, session, snapshot, listOf(pending))
        }
        return acknowledgement
    }

    fun retrySave(boardId: Long) {
        val session = sessions[boardId] ?: return
        if (session.saveState.value !is BoardSaveState.Failed) return
        startSave(boardId, session, session.board.snapshot(), session.pendingAcknowledgements.values.toList())
    }

    fun discard(boardId: Long) {
        sessions.remove(boardId)?.inFlight?.cancel()
    }

    private fun sessionFor(boardId: Long, initial: BoardSnapshot): Session =
        sessions.getOrPut(boardId) { Session(initial) }

    private fun startSave(
        boardId: Long,
        session: Session,
        first: BoardSnapshot,
        acknowledgementsToCover: List<PendingAcknowledgement>,
    ) {
        val operation = saveOperation ?: error("Board save operation is not configured")
        if (session.saveState.value is BoardSaveState.Running) return
        session.inFlight = saveScope.launch {
            var snapshot = first
            var nextAcknowledgements = acknowledgementsToCover
            while (sessions[boardId] === session) {
                val attemptId = ++session.nextAttemptId
                session.activeAttemptId = attemptId
                session.activeSnapshot = snapshot
                nextAcknowledgements.forEach { it.targetAttemptId = attemptId }
                session.saveState.value = BoardSaveState.Running(snapshot)
                try {
                    operation(boardId, snapshot).await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (sessions[boardId] === session) {
                        val retrySnapshot = session.latestDirtySnapshot ?: snapshot
                        session.latestDirtySnapshot = null
                        session.activeAttemptId = null
                        session.activeSnapshot = null
                        session.saveState.value = BoardSaveState.Failed(retrySnapshot)
                        session.inFlight = null
                    }
                    return@launch
                }
                if (sessions[boardId] !== session) return@launch
                val covered = session.pendingAcknowledgements.values
                    .filter { it.targetAttemptId <= attemptId }
                covered.forEach { pending ->
                    pending.completion.complete(Unit)
                    session.pendingAcknowledgements.remove(pending.acknowledgement.requestId)
                }
                val newest = session.latestDirtySnapshot
                session.latestDirtySnapshot = null
                if (newest != null) {
                    snapshot = newest
                    nextAcknowledgements = session.pendingAcknowledgements.values
                        .filter { it.targetAttemptId > attemptId }
                } else {
                    session.saveState.value = BoardSaveState.Idle
                    session.activeAttemptId = null
                    session.activeSnapshot = null
                    session.inFlight = null
                    return@launch
                }
            }
        }
    }
}
