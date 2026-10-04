package com.thinkcanvas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.BoardState
import com.thinkcanvas.canvas.TextEditorSession
import com.thinkcanvas.canvas.ViewportHistory
import com.thinkcanvas.canvas.TextElement
import com.thinkcanvas.data.ShareImportReceiptRow
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
        val textEditor = TextEditorSession()
        val viewportHistory = ViewportHistory()
        var latestDirtySnapshot: BoardSnapshot? = null
        var inFlight: Job? = null
        var nextRequestId = 0L
        var nextAttemptId = 0L
        var activeAttemptId: Long? = null
        var activeSnapshot: BoardSnapshot? = null
        var activeOperation: Deferred<Unit>? = null
        var activeShareId: String? = null
        val imports = mutableMapOf<String, BoardSaveAcknowledgement>()
        val pendingAcknowledgements = linkedMapOf<Long, PendingAcknowledgement>()
    }

    private data class PendingAcknowledgement(
        val acknowledgement: BoardSaveAcknowledgement,
        val completion: CompletableDeferred<Unit>,
        var targetAttemptId: Long,
        val shareReceipt: ShareImportReceiptRow? = null,
    )

    private val sessions = mutableMapOf<Long, Session>()
    private var saveOperation: ((Long, BoardSnapshot) -> kotlinx.coroutines.Deferred<Unit>)? = null
    private var shareSaveOperation: ((Long, BoardSnapshot, ShareImportReceiptRow) -> Deferred<Unit>)? = null

    fun stateFor(boardId: Long, initial: BoardSnapshot): BoardState =
        sessionFor(boardId, initial).board

    fun saveStateFor(boardId: Long, initial: BoardSnapshot): StateFlow<BoardSaveState> =
        sessionFor(boardId, initial).saveState

    fun textEditorFor(boardId: Long, initial: BoardSnapshot): TextEditorSession =
        sessionFor(boardId, initial).textEditor

    fun viewportHistoryFor(boardId: Long, initial: BoardSnapshot): ViewportHistory =
        sessionFor(boardId, initial).viewportHistory

    fun setSaveOperation(operation: (Long, BoardSnapshot) -> kotlinx.coroutines.Deferred<Unit>) {
        saveOperation = operation
    }

    fun requestSave(boardId: Long, snapshot: BoardSnapshot): BoardSaveAcknowledgement? {
        return enqueueSave(boardId, snapshot)
    }

    fun setShareSaveOperation(operation: (Long, BoardSnapshot, ShareImportReceiptRow) -> Deferred<Unit>) {
        shareSaveOperation = operation
    }

    /** Re-entry by the same owner reuses the ack, including after a successful undo. */
    fun requestShareImport(boardId: Long, initial: BoardSnapshot, receipt: ShareImportReceiptRow,
                           element: TextElement, restoreUncertain: Boolean = false): BoardSaveAcknowledgement? {
        require(receipt.boardId == boardId && receipt.elementId == element.id)
        val session = sessionFor(boardId, initial)
        session.imports[receipt.requestId]?.let { return it }
        if (session.saveState.value != BoardSaveState.Idle) return null
        if (session.board.elements.none { it.id == element.id })
            session.board.apply(session.board.snapshot().copy(texts = session.board.elements + element))
        val snapshot = session.board.snapshot()
        if (restoreUncertain) session.saveState.value = BoardSaveState.Failed(snapshot)
        val acknowledgement = checkNotNull(enqueueSave(boardId, snapshot, receipt))
        session.imports[receipt.requestId] = acknowledgement
        return acknowledgement
    }

    fun cancelShareImport(boardId: Long, requestId: String) {
        val session = sessions[boardId] ?: return
        if (session.activeShareId == requestId) {
            session.activeOperation?.cancel()
            session.inFlight?.cancel()
        }
    }

    private fun enqueueSave(boardId: Long, snapshot: BoardSnapshot,
                            receipt: ShareImportReceiptRow? = null): BoardSaveAcknowledgement? {
        val session = sessions[boardId] ?: return null
        val requestId = ++session.nextRequestId
        val completion = CompletableDeferred<Unit>()
        val acknowledgement = BoardSaveAcknowledgement(boardId, requestId, completion)
        val pending = PendingAcknowledgement(acknowledgement, completion, 0L, receipt)
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
        sessions.remove(boardId)?.let { session ->
            if (session.activeShareId != null) session.activeOperation?.cancel()
            session.inFlight?.cancel()
        }
    }

    override fun onCleared() {
        sessions.values.filter { it.activeShareId != null }.forEach { it.activeOperation?.cancel() }
        super.onCleared()
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
                    val receipts = nextAcknowledgements.mapNotNull { it.shareReceipt }.distinct()
                    check(receipts.size <= 1) { "Only one share import may own a save attempt" }
                    val receipt = receipts.singleOrNull()
                    session.activeShareId = receipt?.requestId
                    val submitted = if (receipt == null) operation(boardId, snapshot)
                        else checkNotNull(shareSaveOperation)(boardId, snapshot, receipt)
                    session.activeOperation = submitted
                    submitted.await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (sessions[boardId] === session) {
                        val retrySnapshot = session.latestDirtySnapshot ?: snapshot
                        session.latestDirtySnapshot = null
                        session.activeAttemptId = null
                        session.activeSnapshot = null
                        session.activeOperation = null
                        session.activeShareId = null
                        session.saveState.value = BoardSaveState.Failed(retrySnapshot)
                        session.inFlight = null
                    }
                    return@launch
                }
                if (sessions[boardId] !== session) return@launch
                session.activeOperation = null
                session.activeShareId = null
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
