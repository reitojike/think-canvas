package com.thinkcanvas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.BoardState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface BoardSaveState {
    data object Idle : BoardSaveState
    data class Running(val snapshot: BoardSnapshot) : BoardSaveState
    data class Failed(val snapshot: BoardSnapshot) : BoardSaveState
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
    }

    private val sessions = mutableMapOf<Long, Session>()
    private var saveOperation: ((Long, BoardSnapshot) -> kotlinx.coroutines.Deferred<Unit>)? = null

    fun stateFor(boardId: Long, initial: BoardSnapshot): BoardState =
        sessionFor(boardId, initial).board

    fun saveStateFor(boardId: Long, initial: BoardSnapshot): StateFlow<BoardSaveState> =
        sessionFor(boardId, initial).saveState

    fun setSaveOperation(operation: (Long, BoardSnapshot) -> kotlinx.coroutines.Deferred<Unit>) {
        saveOperation = operation
    }

    fun requestSave(boardId: Long, snapshot: BoardSnapshot) {
        val session = sessions[boardId] ?: return
        when (session.saveState.value) {
            is BoardSaveState.Running -> {
                val running = session.saveState.value as BoardSaveState.Running
                session.latestDirtySnapshot = snapshot.takeUnless { it == running.snapshot }
            }
            is BoardSaveState.Failed -> {
                session.saveState.value = BoardSaveState.Failed(snapshot)
            }
            BoardSaveState.Idle -> startSave(boardId, session, snapshot)
        }
    }

    fun retrySave(boardId: Long) {
        val session = sessions[boardId] ?: return
        if (session.saveState.value !is BoardSaveState.Failed) return
        startSave(boardId, session, session.board.snapshot())
    }

    fun discard(boardId: Long) {
        sessions.remove(boardId)?.inFlight?.cancel()
    }

    private fun sessionFor(boardId: Long, initial: BoardSnapshot): Session =
        sessions.getOrPut(boardId) { Session(initial) }

    private fun startSave(boardId: Long, session: Session, first: BoardSnapshot) {
        val operation = saveOperation ?: error("Board save operation is not configured")
        if (session.saveState.value is BoardSaveState.Running) return
        session.inFlight = saveScope.launch {
            var snapshot = first
            while (sessions[boardId] === session) {
                session.saveState.value = BoardSaveState.Running(snapshot)
                try {
                    operation(boardId, snapshot).await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (sessions[boardId] === session) {
                        val retrySnapshot = session.latestDirtySnapshot ?: snapshot
                        session.latestDirtySnapshot = null
                        session.saveState.value = BoardSaveState.Failed(retrySnapshot)
                        session.inFlight = null
                    }
                    return@launch
                }
                if (sessions[boardId] !== session) return@launch
                val newest = session.latestDirtySnapshot
                session.latestDirtySnapshot = null
                if (newest != null && newest != snapshot) {
                    snapshot = newest
                } else {
                    session.saveState.value = BoardSaveState.Idle
                    session.inFlight = null
                    return@launch
                }
            }
        }
    }
}
