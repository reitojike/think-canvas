package com.thinkcanvas.image

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinkcanvas.BoardSaveAcknowledgement
import com.thinkcanvas.BoardSaveState
import com.thinkcanvas.BoardSessionViewModel
import com.thinkcanvas.canvas.WorldPoint
import com.thinkcanvas.canvas.isCanonicalUuid
import com.thinkcanvas.data.CanvasStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class ImageImportPhase { INITIALIZING, IDLE, PREPARING, PICKER, READING, ACCEPTED, SAVING, FAILED }

data class ImageImportState(
    val phase: ImageImportPhase = ImageImportPhase.INITIALIZING,
    val request: ImageImportRequest? = null,
    val launchNeeded: Boolean = false,
    val hidden: Boolean = false,
    val manualRetry: Boolean = false,
    val message: String = "画像を取り込めませんでした。もう一度お試しください",
) {
    val blocksCanvas: Boolean get() = phase != ImageImportPhase.IDLE && !hidden
}

/** Owns pending intake only. BoardSession remains the sole edit/save owner. */
class ImageImportViewModel : ViewModel() {
    var state by mutableStateOf(ImageImportState())
        private set
    var taskToken = UUID.randomUUID().toString()
        private set
    private var store: CanvasStore? = null
    private var initialized = false
    private var operation: Job? = null
    private var copying: Deferred<ImportedImageAsset>? = null
    private var watching: Job? = null
    private var acknowledgement: BoardSaveAcknowledgement? = null
    private var finishing = false

    fun initialize(store: CanvasStore, savedToken: String?) {
        if (initialized) return
        initialized = true
        this.store = store
        if (savedToken != null && isCanonicalUuid(savedToken)) taskToken = savedToken
        operation = viewModelScope.launch {
            try {
                val request = store.prepareImageTask(taskToken)
                if (request == null) state = ImageImportState(ImageImportPhase.IDLE)
                else {
                    val receipt = store.shareReceipt(request.requestId)
                    if (receipt != null) {
                        check(receipt.boardId == request.boardId && receipt.elementId == request.elementId)
                        store.writeImageCheckpoint(taskToken, null)
                        state = ImageImportState(ImageImportPhase.IDLE)
                    } else state = ImageImportState(ImageImportPhase.FAILED, request,
                        message = if (request.accepted == null) "画像の選択が中断されました。もう一度選んでください"
                            else "画像の取り込みを再試行してください")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state = ImageImportState(ImageImportPhase.FAILED,
                message = "画像の取り込み状態を復元できません。もう一度選んでください") }
        }
    }

    fun begin(source: ImagePickerSource, boardId: Long, center: WorldPoint,
              viewportWidth: Float, viewportHeight: Float): Boolean {
        if (state.phase != ImageImportPhase.IDLE || finishing) return false
        val request = ImageImportRequest(taskToken, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            boardId, center, viewportWidth, viewportHeight, source)
        state = ImageImportState(ImageImportPhase.PREPARING, request)
        operation = viewModelScope.launch {
            try {
                checkNotNull(store).writeImageCheckpoint(taskToken, request)
                if (state.request?.requestId == request.requestId)
                    state = ImageImportState(ImageImportPhase.PICKER, request, launchNeeded = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(request.requestId) }
        }
        return true
    }

    fun consumeLaunch(requestId: String): Boolean {
        if (state.phase != ImageImportPhase.PICKER || state.request?.requestId != requestId || !state.launchNeeded)
            return false
        state = state.copy(launchNeeded = false)
        return true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun receive(requestId: String?, source: ImagePickerSource, uri: Uri?) {
        val request = state.request ?: return
        if (state.phase != ImageImportPhase.PICKER || requestId != request.requestId || request.source != source || finishing)
            return
        state = state.copy(phase = ImageImportPhase.READING, launchNeeded = false)
        operation = viewModelScope.launch {
            if (uri == null) { clear(request.requestId); return@launch }
            var submitted: Deferred<ImportedImageAsset>? = null
            try {
                submitted = checkNotNull(store).importImage(uri)
                copying = submitted
                submitted.await().use { imported ->
                    if (state.request?.requestId != request.requestId || finishing) return@use
                    val accepted = request.accepting(imported.asset)
                    checkNotNull(store).writeImageCheckpoint(taskToken, accepted)
                    if (state.request?.requestId == request.requestId && !finishing)
                        state = ImageImportState(ImageImportPhase.ACCEPTED, accepted)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(request.requestId) }
            finally {
                // await has prompt cancellation: a completed copy can lose its consumer
                // before resumption. Close that successful result too (leases are idempotent).
                submitted?.let { result ->
                    result.cancel()
                    if (result.isCompleted && !result.isCancelled)
                        runCatching { result.getCompleted().close() }
                }
                copying = null
            }
        }
    }

    fun fail(requestId: String?) {
        if (state.request?.requestId == requestId && !finishing)
            state = state.copy(phase = ImageImportPhase.FAILED, launchNeeded = false, hidden = false)
    }

    fun showFailure() { if (state.phase == ImageImportPhase.FAILED) state = state.copy(hidden = false) }
    fun hideFailure() { if (state.phase == ImageImportPhase.FAILED) state = state.copy(hidden = true) }

    fun retry(sessions: BoardSessionViewModel) {
        if (state.phase != ImageImportPhase.FAILED || finishing) return
        val request = state.request
        if (acknowledgement?.isCompleted == true) {
            operation = viewModelScope.launch { clear(request?.requestId) }
            return
        }
        if (request?.accepted != null) {
            if (acknowledgement != null) {
                state = state.copy(phase = ImageImportPhase.SAVING, hidden = false)
                sessions.retrySave(request.boardId)
            } else state = state.copy(phase = ImageImportPhase.ACCEPTED, hidden = false, manualRetry = true)
        } else operation = viewModelScope.launch { clear(request?.requestId) }
    }

    fun observeSave(ack: BoardSaveAcknowledgement, saveState: StateFlow<BoardSaveState>) {
        val request = state.request ?: return
        if (state.phase != ImageImportPhase.ACCEPTED || acknowledgement != null) return
        acknowledgement = ack
        state = state.copy(phase = ImageImportPhase.SAVING)
        watching = viewModelScope.launch {
            coroutineScope {
                val failureObserver = launch {
                    saveState.collect { saved ->
                        if (saved is BoardSaveState.Failed) fail(request.requestId)
                    }
                }
                try { ack.await(); clear(request.requestId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { fail(request.requestId) }
                finally { failureObserver.cancel() }
            }
        }
    }

    suspend fun completeFromReceipt(requestId: String) { clear(requestId) }

    suspend fun rejectUnavailableDestination(requestId: String) {
        // An unapplied restored patch has no save owner to retry. Retire only the
        // same live request; applied/uncertain saves retain their existing owner.
        if (state.phase != ImageImportPhase.ACCEPTED || state.request?.requestId != requestId ||
            acknowledgement != null || finishing) return
        clear(requestId, ImageImportState(ImageImportPhase.FAILED,
            message = "追加先のボードが削除されました。画像を選び直してください"))
    }

    private suspend fun clear(requestId: String?, cleared: ImageImportState = ImageImportState(ImageImportPhase.IDLE)) {
        if (state.request?.requestId != requestId || finishing) return
        try {
            checkNotNull(store).writeImageCheckpoint(taskToken, null)
            if (state.request?.requestId == requestId) {
                acknowledgement = null
                state = cleared
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fail(requestId) }
    }

    fun finishTask(sessions: BoardSessionViewModel, clearRecord: (String) -> Unit) {
        if (finishing) return
        finishing = true
        state.request?.let { sessions.cancelShareImport(it.boardId, it.requestId) }
        copying?.cancel()
        operation?.cancel()
        watching?.cancel()
        val token = taskToken
        // Actor order places this terminal cleanup after any queued checkpoint write.
        clearRecord(token)
    }

    override fun onCleared() { copying?.cancel(); super.onCleared() }
}
