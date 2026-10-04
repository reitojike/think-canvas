package com.thinkcanvas.share

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinkcanvas.BoardSaveAcknowledgement
import com.thinkcanvas.BoardSaveState
import com.thinkcanvas.canvas.WorldPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Task-local admission and durable checkpoint; it never owns a second board writer. */
class ShareImportViewModel : ViewModel() {
    var state by mutableStateOf(ShareImportState())
        private set
    var noticeMessage by mutableStateOf<String?>(null)
        private set
    var taskToken = UUID.randomUUID().toString()
        private set
    private var checkpoint: ShareImportCheckpoint? = null
    private var initialization: Job? = null
    private var initialized = false
    private var watchedRequestId: String? = null

    fun initialize(directory: File, restored: Boolean, savedToken: String?, initialText: String?) {
        if (initialized) return
        initialized = true
        val validSaved = ShareImportCheckpoint.validToken(savedToken)
        taskToken = if (restored && validSaved != null) validSaved else taskToken
        val record = ShareImportCheckpoint(directory, taskToken)
        checkpoint = record
        initialization = viewModelScope.launch {
            try {
                val request = withContext(Dispatchers.IO) {
                    if (restored && validSaved != null) record.read()
                    else {
                        val incoming = if (restored) null else initialText?.let { ShareImportRequest(text = it) }
                        record.write(incoming)
                        ShareImportCheckpoint.discardOtherTasks(directory, taskToken)
                        incoming
                    }
                }
                state = when {
                    request == null -> ShareImportState(ShareImportPhase.EMPTY)
                    request.accepted -> ShareImportState(ShareImportPhase.OPENING, request,
                        restoredUncertain = true)
                    else -> ShareImportState(ShareImportPhase.DEFERRED, request)
                }
                if (restored && validSaved == null) noticeMessage = "共有状態を復元できません。もう一度共有してください"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A missing/corrupt restored record must never replay the old launch Intent.
                state = ShareImportState(ShareImportPhase.EMPTY)
                noticeMessage = "共有状態を復元できません。もう一度共有してください"
            }
        }
    }

    suspend fun awaitInitialized() { initialization?.join() }

    fun consumeNotice() { noticeMessage = null }

    /** Wait for outstanding atomic IO before erasing a finishing task's private body. */
    fun finishTask(clearRecord: (ShareImportCheckpoint) -> Unit) {
        val record = checkpoint ?: return
        val scopeJob = checkNotNull(viewModelScope.coroutineContext[Job])
        scopeJob.invokeOnCompletion { clearRecord(record) }
        viewModelScope.cancel()
    }

    fun receive(text: String): Boolean {
        if (!initialized || state.phase != ShareImportPhase.EMPTY || state.writing) return false
        val request = ShareImportRequest(text = text)
        persist(ShareImportState(ShareImportPhase.DEFERRED, request), request)
        return true
    }

    fun present(candidateId: Long?) {
        val current = state
        if (current.phase != ShareImportPhase.DEFERRED || current.writing) return
        val request = checkNotNull(current.request).copy(destinationId =
            current.request.destinationId ?: candidateId)
        val phase = if (request.destinationId == null) ShareImportPhase.PICKER else ShareImportPhase.PREVIEW
        persist(current.copy(phase = phase, request = request), request, onFailure = { fail() })
    }

    fun pickBoard(boardId: Long) {
        val current = state
        if (current.phase != ShareImportPhase.PICKER || current.writing) return
        val request = checkNotNull(current.request).copy(destinationId = boardId)
        persist(current.copy(phase = ShareImportPhase.PREVIEW, request = request), request)
    }

    fun showPicker() {
        if (state.phase == ShareImportPhase.PREVIEW && !state.writing)
            state = state.copy(phase = ShareImportPhase.PICKER)
    }

    fun confirm(): Boolean {
        if (state.phase != ShareImportPhase.PREVIEW || state.writing || state.request?.destinationId == null)
            return false
        state = state.copy(phase = ShareImportPhase.OPENING)
        return true
    }

    fun accept(position: WorldPoint) {
        val current = state
        if (current.phase != ShareImportPhase.OPENING || current.writing) return
        val request = checkNotNull(current.request)
        if (request.accepted) state = current.copy(phase = ShareImportPhase.ACCEPTED)
        else {
            val accepted = request.copy(position = position)
            persist(current.copy(phase = ShareImportPhase.ACCEPTED, request = accepted), accepted,
                onFailure = { state = current.copy(phase = ShareImportPhase.PREVIEW) })
        }
    }

    fun missingDestination() {
        val current = state
        if (current.request?.accepted == true) fail()
        else if (current.request != null) {
            val request = current.request.copy(destinationId = null)
            persist(current.copy(phase = ShareImportPhase.PICKER, request = request), request,
                onFailure = { fail() })
            noticeMessage = "ボードが見つかりません。取り込み先を選んでください"
        }
    }

    fun fail() {
        if (state.request != null) state = state.copy(phase = ShareImportPhase.FAILED, writing = false)
    }

    fun retryOpening() {
        if (state.phase == ShareImportPhase.FAILED && watchedRequestId == null)
            state = state.copy(phase = if (state.request?.accepted == true)
                ShareImportPhase.OPENING else ShareImportPhase.DEFERRED)
    }

    fun cancel() {
        if (state.phase !in setOf(ShareImportPhase.PREVIEW, ShareImportPhase.PICKER,
                ShareImportPhase.DEFERRED) || state.writing) return
        persist(ShareImportState(ShareImportPhase.EMPTY), null)
    }

    fun completeFromReceipt() {
        if (state.request != null && !state.writing)
            persist(ShareImportState(ShareImportPhase.EMPTY), null, onFailure = { fail() })
    }

    fun observeSave(acknowledgement: BoardSaveAcknowledgement, saveState: StateFlow<BoardSaveState>) {
        val current = state
        val requestId = current.request?.requestId ?: return
        if (watchedRequestId == requestId) return
        watchedRequestId = requestId
        state = current.copy(phase = if (saveState.value is BoardSaveState.Failed)
            ShareImportPhase.FAILED else ShareImportPhase.SAVING)
        viewModelScope.launch {
            coroutineScope {
                val observer = launch {
                    saveState.collect { saved ->
                        if (state.request?.requestId == requestId) {
                            when (saved) {
                                is BoardSaveState.Failed -> state = state.copy(phase = ShareImportPhase.FAILED)
                                is BoardSaveState.Running -> state = state.copy(phase = ShareImportPhase.SAVING)
                                BoardSaveState.Idle -> Unit
                            }
                        }
                    }
                }
                try {
                    acknowledgement.await()
                    withContext(Dispatchers.IO) { checkNotNull(checkpoint).write(null) }
                    if (state.request?.requestId == requestId) {
                        state = ShareImportState(ShareImportPhase.EMPTY)
                        noticeMessage = "テキストを取り込みました"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The content may already be durable. Receipt reconciliation precedes any retry.
                    if (state.request?.requestId == requestId) fail()
                } finally {
                    observer.cancel()
                    watchedRequestId = null
                }
            }
        }
    }

    private fun persist(next: ShareImportState, request: ShareImportRequest?,
                        onFailure: (() -> Unit)? = null) {
        val before = state
        state = next.copy(writing = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { checkNotNull(checkpoint).write(request) }
                state = next.copy(writing = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state = before.copy(writing = false)
                onFailure?.invoke()
                noticeMessage = "共有内容を準備できません。もう一度お試しください"
            }
        }
    }
}
