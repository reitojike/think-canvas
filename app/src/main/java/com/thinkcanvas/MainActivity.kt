package com.thinkcanvas

import android.graphics.Bitmap
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.withStateAtLeast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.thinkcanvas.board.BoardImageRenderer
import com.thinkcanvas.board.BoardListScreen
import com.thinkcanvas.board.ExportTypography
import com.thinkcanvas.board.GuideSheet
import com.thinkcanvas.board.ImageDelivery
import com.thinkcanvas.board.ShareSheet
import com.thinkcanvas.board.planShare
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.BoardState
import com.thinkcanvas.canvas.CanvasScreen
import com.thinkcanvas.data.CanvasStore
import com.thinkcanvas.data.StoredBoard
import com.thinkcanvas.data.BoardRow
import com.thinkcanvas.data.ShareImportReceiptRow
import com.thinkcanvas.canvas.WorldPoint
import com.thinkcanvas.share.ShareImportDialog
import com.thinkcanvas.share.ShareImportPhase
import com.thinkcanvas.share.ShareImportViewModel
import com.thinkcanvas.share.sharedPlainText
import com.thinkcanvas.share.loadSharePreviewIfReady
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class ShareDialogState(
    val requestId: Long,
    val title: String,
    val bitmap: Bitmap? = null,
    val message: String? = null,
)

private sealed interface Page {
    data object Loading : Page
    data object List : Page
    data class Board(val id: Long, val name: String, val state: BoardState) : Page
}

class MainActivity : ComponentActivity() {
    private var navigationTargetIsList = false
    private lateinit var shareImports: ShareImportViewModel
    private lateinit var activeBoardSessions: BoardSessionViewModel
    private lateinit var imageImports: com.thinkcanvas.image.ImageImportViewModel
    private var photoImageRequestId: String? = null
    private var fileImageRequestId: String? = null

    @Suppress("DEPRECATION")
    private fun incomingText(intent: Intent): String? = try {
        sharedPlainText(intent.action, intent.type, intent.extras?.get(Intent.EXTRA_TEXT))
    } catch (_: RuntimeException) { null }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Admission uses this delivery directly; the original task launch Intent stays unchanged.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        val text = incomingText(intent)
        val message = when {
            text == null -> "この共有内容は取り込めません"
            !shareImports.receive(text) -> "先の共有を完了してから、もう一度共有してください"
            else -> "共有を受け取りました。現在の操作が終わると確認できます"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        if (isFinishing && ::shareImports.isInitialized && ::activeBoardSessions.isInitialized) {
            val request = shareImports.state.request
            if (request?.accepted == true)
                activeBoardSessions.cancelShareImport(checkNotNull(request.destinationId), request.requestId)
            val store = CanvasStore.get(applicationContext)
            shareImports.finishTask { record -> store.clearShareCheckpoint(record) }
            if (::imageImports.isInitialized)
                imageImports.finishTask(activeBoardSessions) { token -> store.clearImageCheckpoint(token) }
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("navigationTargetIsList", navigationTargetIsList)
        if (::shareImports.isInitialized) outState.putString("shareTaskToken", shareImports.taskToken)
        if (::imageImports.isInitialized) outState.putString("imageTaskToken", imageImports.taskToken)
        outState.putString("photoImageRequestId", photoImageRequestId)
        outState.putString("fileImageRequestId", fileImageRequestId)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigationTargetIsList = savedInstanceState?.getBoolean("navigationTargetIsList") == true
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = SystemBarStyle.light(transparent, transparent),
        )
        val store = CanvasStore.get(this)
        val boardSessions = ViewModelProvider(this)[BoardSessionViewModel::class.java]
        activeBoardSessions = boardSessions
        boardSessions.setSaveOperation { boardId, snapshot -> store.save(boardId, snapshot) }
        boardSessions.setShareSaveOperation { boardId, snapshot, receipt -> store.saveShare(boardId, snapshot, receipt) }
        boardSessions.setImageRootsOperation(store::setSessionImageRoots)
        shareImports = ViewModelProvider(this)[ShareImportViewModel::class.java]
        val historyLaunch = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val restoringTask = savedInstanceState != null || historyLaunch
        imageImports = ViewModelProvider(this)[com.thinkcanvas.image.ImageImportViewModel::class.java]
        imageImports.initialize(store, savedInstanceState?.getString("imageTaskToken"))
        photoImageRequestId = savedInstanceState?.getString("photoImageRequestId")
        fileImageRequestId = savedInstanceState?.getString("fileImageRequestId")
        val imageResources = com.thinkcanvas.image.ImageResources.get(store)
        val initialShareAttempt = intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)
        val initialText = if (!restoringTask) incomingText(intent) else null
        shareImports.initialize(File(filesDir, "share-import"), restoringTask,
            savedInstanceState?.getString("shareTaskToken"), initialText)
        val boardListActions = ViewModelProvider(this)[BoardListActionViewModel::class.java]
        val page = mutableStateOf<Page>(Page.Loading)
        val cards = mutableStateOf<List<StoredBoard>>(emptyList())
        val guideVisible = mutableStateOf(false)
        val transientPending = mutableStateOf(false)
        val errorMessage = mutableStateOf<String?>(null)
        val startupLoadFailed = mutableStateOf(false)
        val shareDialog = mutableStateOf<ShareDialogState?>(null)
        val shareBusy = mutableStateOf(false)
        val importBoards = mutableStateOf<List<BoardRow>>(emptyList())
        val canvasReady = mutableStateOf(false)
        val listReady = mutableStateOf(true)
        val canvasOwnerId = mutableStateOf<Long?>(null)
        var canvasNeutralGuard: (() -> Boolean)? = null
        var listNeutralGuard: (() -> Boolean)? = null
        var shareRequestId = 0L
        val pendingDocument = File(cacheDir, "pending-board-image.png")

        fun notice(message: String) = Toast.makeText(this@MainActivity, message,
            Toast.LENGTH_SHORT).show()

        if (!restoringTask && initialShareAttempt && initialText == null)
            notice("この共有内容は取り込めません")

        val createDocument = registerForActivityResult(
            ActivityResultContracts.CreateDocument("image/png")) { uri ->
            if (uri == null) {
                shareBusy.value = false
                pendingDocument.delete()
                notice("画像の保存を取り消しました")
            } else if (pendingDocument.isFile) {
                lifecycleScope.launch {
                    try {
                        ImageDelivery.writeDocument(this@MainActivity, uri,
                            withContext(Dispatchers.IO) { pendingDocument.readBytes() })
                        shareDialog.value = null
                        notice("画像を保存しました")
                    } catch (error: Exception) {
                        errorMessage.value = error.message ?: "画像を保存できません"
                    } finally {
                        pendingDocument.delete()
                        shareBusy.value = false
                    }
                }
            } else {
                shareBusy.value = false
                errorMessage.value = "画像の準備が失われました。もう一度お試しください"
            }
        }
        val shareResult = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()) {
            notice("共有メニューを閉じました")
        }
        val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val requestId = photoImageRequestId
            photoImageRequestId = null
            imageImports.receive(requestId, com.thinkcanvas.image.ImagePickerSource.PHOTO, uri)
        }
        val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val requestId = fileImageRequestId
            fileImageRequestId = null
            imageImports.receive(requestId, com.thinkcanvas.image.ImagePickerSource.FILE, uri)
        }

        fun dismissShare() {
            // 公開済み Bitmap は Compose の描画が参照し得るため、解放は GC に任せる。
            shareDialog.value = null
        }

        fun showShare(title: String, snapshot: BoardSnapshot, typography: ExportTypography,
                      selectedIds: Set<String>? = null) {
            val request = ++shareRequestId
            dismissShare()
            shareDialog.value = ShareDialogState(request, title)
            lifecycleScope.launch {
                try {
                    val plan = withContext(Dispatchers.Default) {
                        val bounds = BoardImageRenderer.renderedBounds(snapshot, typography)
                        planShare(snapshot, selectedIds, bounds, typography)
                    }
                    val bitmap = store.withImageAssets(snapshot.images.filter { it.id in plan.includedIds }
                        .map { it.assetId }.toSet()) { BoardImageRenderer.render(plan, it) }
                    if (shareDialog.value?.requestId == request)
                        shareDialog.value = ShareDialogState(request, title, bitmap)
                    // この render 結果は shareDialog に公開されていない。
                    else bitmap.recycle()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IllegalArgumentException) {
                    if (shareDialog.value?.requestId == request)
                        shareDialog.value = ShareDialogState(request, title,
                            message = error.message ?: "画像を作成できません")
                } catch (_: OutOfMemoryError) {
                    if (shareDialog.value?.requestId == request)
                        shareDialog.value = ShareDialogState(request, title,
                            message = "画像が大きすぎるため作成できません")
                } catch (error: Exception) {
                    if (shareDialog.value?.requestId == request)
                        shareDialog.value = ShareDialogState(request, title,
                            message = error.message ?: "画像を作成できません")
                }
            }
        }

        fun deliver(kind: Int) {
            if (shareBusy.value) return
            val bitmap = shareDialog.value?.bitmap ?: return
            shareBusy.value = true
            lifecycleScope.launch {
                var waitingForDocument = false
                try {
                    val bytes = ImageDelivery.png(bitmap)
                    when (kind) {
                        0 -> if (Build.VERSION.SDK_INT >= 29) {
                            ImageDelivery.saveToPhotos(this@MainActivity, bytes)
                            dismissShare()
                            notice("画像を保存しました")
                        } else {
                            withContext(Dispatchers.IO) { pendingDocument.writeBytes(bytes) }
                            createDocument.launch("think-canvas-${System.currentTimeMillis()}.png")
                            waitingForDocument = true
                        }
                        1 -> {
                            val uri = ImageDelivery.cacheUri(this@MainActivity, bytes)
                            ImageDelivery.copy(this@MainActivity, uri)
                            dismissShare()
                            notice("画像をコピーしました")
                        }
                        else -> {
                            val uri = ImageDelivery.cacheUri(this@MainActivity, bytes)
                            shareResult.launch(ImageDelivery.shareIntent(this@MainActivity, uri))
                            dismissShare()
                            notice("共有メニューを開きました")
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: OutOfMemoryError) {
                    errorMessage.value = "画像を準備するメモリが不足しています"
                } catch (error: Exception) {
                    errorMessage.value = error.message ?: "画像を渡せません"
                } finally {
                    if (!waitingForDocument) {
                        if (kind == 0 && Build.VERSION.SDK_INT < 29) pendingDocument.delete()
                        shareBusy.value = false
                    }
                }
            }
        }

        fun openBoard(stored: StoredBoard) {
            navigationTargetIsList = false
            page.value = Page.Board(stored.details.id, stored.details.name,
                boardSessions.stateFor(stored.details.id, stored.snapshot))
            guideVisible.value = shareImports.state.request == null &&
                imageImports.state.phase == com.thinkcanvas.image.ImageImportPhase.IDLE &&
                store.shouldShowGuide(stored.details.id, stored.snapshot)
        }

        suspend fun showList() {
            cards.value = store.boardsWithContent()
            navigationTargetIsList = true
            guideVisible.value = false
            page.value = Page.List
        }

        fun performTransient(action: suspend () -> Unit) {
            if (transientPending.value) return
            transientPending.value = true
            lifecycleScope.launch {
                try {
                    action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    errorMessage.value = error.message ?: "操作を完了できません"
                } finally {
                    transientPending.value = false
                }
            }
        }

        fun admitListOperation(start: () -> Unit) {
            if (shareImports.state.blocksCanvas || imageImports.state.blocksCanvas || transientPending.value ||
                boardListActions.state.value != BoardListActionState.Idle)
                return
            start()
        }

        fun dismissListActionFailure() {
            val failed = boardListActions.state.value as? BoardListActionState.Failed
            if (failed == null) {
                errorMessage.value = null
                startupLoadFailed.value = false
                return
            }
            if (transientPending.value) {
                errorMessage.value = failed.message
                return
            }
            transientPending.value = true
            lifecycleScope.launch {
                try {
                    val restoredCards = store.boardsWithContent()
                    lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
                        if (boardListActions.state.value == failed) {
                            cards.value = restoredCards
                            navigationTargetIsList = true
                            guideVisible.value = false
                            page.value = Page.List
                            boardListActions.acknowledgeFailure(failed)
                            errorMessage.value = null
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    errorMessage.value = error.message ?: "一覧を読み込めません"
                } finally {
                    transientPending.value = false
                }
            }
        }

        fun retryStartupLoad() {
            if (transientPending.value) return
            errorMessage.value = null
            startupLoadFailed.value = false
            performTransient {
                try {
                    showList()
                } catch (error: Exception) {
                    startupLoadFailed.value = true
                    navigationTargetIsList = true
                    page.value = Page.List
                    throw error
                }
            }
        }

        setContent {
            val listActionState = boardListActions.state.value
            val importState = shareImports.state
            val imageState = imageImports.state
            fun baseImportReady(ignoreImage: Boolean = false): Boolean {
                if (!ignoreImage && imageImports.state.blocksCanvas) return false
                if (transientPending.value || guideVisible.value || errorMessage.value != null ||
                    shareDialog.value != null || shareBusy.value ||
                    boardListActions.state.value != BoardListActionState.Idle) return false
                return when (val shown = page.value) {
                    is Page.Board -> canvasOwnerId.value == shown.id && canvasNeutralGuard?.invoke() == true
                    Page.List -> listNeutralGuard?.invoke() ?: listReady.value
                    Page.Loading -> false
                }
            }
            LaunchedEffect(imageState.phase, imageState.request?.requestId, imageState.launchNeeded,
                page.value, canvasReady.value, canvasOwnerId.value, transientPending.value,
                guideVisible.value, errorMessage.value, shareDialog.value, shareBusy.value, listActionState) {
                val request = imageImports.state.request ?: return@LaunchedEffect
                try {
                    when (imageImports.state.phase) {
                        com.thinkcanvas.image.ImageImportPhase.PICKER -> {
                            if (imageImports.consumeLaunch(request.requestId)) {
                                when (request.source) {
                                    com.thinkcanvas.image.ImagePickerSource.PHOTO -> {
                                        photoImageRequestId = request.requestId
                                        photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    }
                                    com.thinkcanvas.image.ImagePickerSource.FILE -> {
                                        fileImageRequestId = request.requestId
                                        filePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                                    }
                                }
                            }
                        }
                        com.thinkcanvas.image.ImageImportPhase.ACCEPTED -> {
                            if (page.value == Page.Loading) return@LaunchedEffect
                            val receipt = store.shareReceipt(request.requestId)
                            if (receipt != null) {
                                check(receipt.boardId == request.boardId && receipt.elementId == request.elementId)
                                imageImports.completeFromReceipt(request.requestId)
                                return@LaunchedEffect
                            }
                            if (shareImports.state.blocksCanvas || !baseImportReady(ignoreImage = true)) return@LaunchedEffect
                            val candidate = page.value
                            val stored = store.savedBoard(request.boardId)
                            if (stored == null) { imageImports.fail(request.requestId); return@LaunchedEffect }
                            if (page.value !== candidate || !baseImportReady(ignoreImage = true) ||
                                imageImports.state.phase != com.thinkcanvas.image.ImageImportPhase.ACCEPTED ||
                                imageImports.state.request?.requestId != request.requestId) return@LaunchedEffect
                            val shown = candidate as? Page.Board
                            if (shown?.id != request.boardId) {
                                if (imageImports.state.manualRetry) openBoard(stored) else imageImports.fail(request.requestId)
                                return@LaunchedEffect
                            }
                            val ack = boardSessions.requestImageImport(shown.id, shown.state.snapshot(),
                                ShareImportReceiptRow(request.requestId, shown.id, request.elementId),
                                checkNotNull(request.accepted)) ?: return@LaunchedEffect
                            imageImports.observeSave(ack, boardSessions.saveStateFor(shown.id, shown.state.snapshot()))
                        }
                        else -> Unit
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { imageImports.fail(request.requestId) }
            }
            LaunchedEffect(shareImports.noticeMessage) {
                shareImports.noticeMessage?.let { notice(it); shareImports.consumeNotice() }
            }
            LaunchedEffect(importState.phase, importState.request?.requestId,
                importState.request?.destinationId, importState.writing, imageState.phase, imageState.hidden, page.value,
                canvasReady.value, canvasOwnerId.value, listReady.value, transientPending.value, guideVisible.value,
                errorMessage.value, shareDialog.value, shareBusy.value, listActionState) {
                val request = shareImports.state.request ?: return@LaunchedEffect
                if (shareImports.state.writing) return@LaunchedEffect
                try {
                    if (shareImports.state.phase == ShareImportPhase.DEFERRED ||
                        shareImports.state.phase == ShareImportPhase.OPENING) {
                        val receipt = store.shareReceipt(request.requestId)
                        if (receipt != null) {
                            check(receipt.elementId == request.elementId)
                            // An old preview's candidate is not the accepted destination authority.
                            check(!request.accepted || receipt.boardId == request.destinationId)
                            shareImports.completeFromReceipt()
                            return@LaunchedEffect
                        }
                    }
                    when (shareImports.state.phase) {
                        ShareImportPhase.DEFERRED -> {
                            val candidatePage = page.value
                            loadSharePreviewIfReady(
                                isReady = { page.value === candidatePage &&
                                    shareImports.state.phase == ShareImportPhase.DEFERRED &&
                                    shareImports.state.request?.requestId == request.requestId &&
                                    !shareImports.state.writing && baseImportReady() },
                                loadBoards = { store.boards() },
                                candidateId = { (candidatePage as? Page.Board)?.id ?:
                                    store.lastOpenedBoard()?.details?.id },
                                present = { boards, candidate ->
                                    importBoards.value = boards
                                    shareImports.present(candidate)
                                },
                            )
                        }
                        ShareImportPhase.PREVIEW, ShareImportPhase.PICKER -> {
                            importBoards.value = store.boards()
                            if (shareImports.state.phase == ShareImportPhase.PREVIEW &&
                                importBoards.value.none { it.id == request.destinationId })
                                shareImports.missingDestination()
                        }
                        ShareImportPhase.OPENING -> {
                            if (page.value == Page.Loading || transientPending.value) return@LaunchedEffect
                            val destination = request.destinationId ?: return@LaunchedEffect
                            val shown = page.value as? Page.Board
                            if (shown?.id != destination) {
                                val stored = store.open(destination)
                                if (stored == null) shareImports.missingDestination() else openBoard(stored)
                            } else if (baseImportReady()) {
                                val focus = boardSessions.viewportHistoryFor(shown.id, shown.state.snapshot()).focus()
                                    ?: return@LaunchedEffect
                                shareImports.accept(WorldPoint(focus.centerX, focus.centerY))
                            }
                        }
                        ShareImportPhase.ACCEPTED -> {
                            val shown = page.value as? Page.Board ?: return@LaunchedEffect
                            if (shown.id != request.destinationId) return@LaunchedEffect
                            val acknowledgement = boardSessions.requestShareImport(shown.id, shown.state.snapshot(),
                                ShareImportReceiptRow(request.requestId, shown.id, request.elementId), request.element(),
                                restoreUncertain = importState.restoredUncertain) ?: return@LaunchedEffect
                            shareImports.observeSave(acknowledgement,
                                boardSessions.saveStateFor(shown.id, shown.state.snapshot()))
                        }
                        else -> Unit
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { shareImports.fail() }
            }
            LaunchedEffect(listActionState) {
                when (val current = listActionState) {
                    is BoardListActionState.Running -> page.value = Page.Loading
                    is BoardListActionState.Completed -> when (val outcome = current.outcome) {
                        is BoardListActionOutcome.OpenBoard -> {
                            lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
                                if (boardListActions.state.value == current) {
                                    openBoard(outcome.board)
                                    if (current.action == BoardListAction.Create &&
                                        shareImports.state.phase == ShareImportPhase.PICKER)
                                        shareImports.pickBoard(outcome.board.details.id)
                                    boardListActions.consume(current)
                                }
                            }
                        }
                        BoardListActionOutcome.ReloadList -> {
                            val restoredCards = try {
                                store.boardsWithContent()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
                                    if (boardListActions.state.value == current) {
                                        boardListActions.failContinuation(current,
                                            error.message ?: "一覧を読み込めません")
                                    }
                                }
                                return@LaunchedEffect
                            }
                            lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
                                if (boardListActions.state.value == current) {
                                    cards.value = restoredCards
                                    navigationTargetIsList = true
                                    guideVisible.value = false
                                    page.value = Page.List
                                    if (current.action is BoardListAction.Delete)
                                        boardSessions.discard(current.action.boardId)
                                    boardListActions.consume(current)
                                }
                            }
                        }
                    }
                    is BoardListActionState.Failed -> errorMessage.value = current.message
                    BoardListActionState.Idle -> Unit
                }
            }
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF23211E),
                secondary = Color(0xFFC54B32),
                background = Color(0xFFFCFCFB),
                surface = Color(0xFFFCFCFB),
            )) {
                val shareTypography = ExportTypography.from(LocalDensity.current,
                    LocalTextStyle.current)
                androidx.compose.runtime.DisposableEffect(Unit) {
                    onDispose { dismissShare() }
                }
                when (val current = page.value) {
                    Page.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.loading_board))
                    }
                    Page.List -> BoardListScreen(cards.value,
                        imageResources = imageResources,
                        externalInteractionBlocked = { shareImports.state.blocksCanvas || imageImports.state.blocksCanvas },
                        onImportReadiness = { ready, guard ->
                            if (page.value == Page.List) { listReady.value = ready; listNeutralGuard = guard }
                        },
                        onOpen = { id -> admitListOperation {
                            boardListActions.start(BoardListAction.Open(id))
                        } },
                        onCreate = { admitListOperation {
                            boardListActions.start(BoardListAction.Create)
                        } },
                        onRename = { id, name ->
                            admitListOperation {
                                boardListActions.start(BoardListAction.Rename(id, name))
                            }
                        },
                        onDuplicate = { id ->
                            admitListOperation {
                                boardListActions.start(BoardListAction.Duplicate(id))
                            }
                        },
                        onDelete = { id -> admitListOperation {
                            boardListActions.start(BoardListAction.Delete(id))
                        } },
                        onShare = { id -> admitListOperation {
                            performTransient {
                                val stored = store.savedBoard(id) ?: error("ボードが見つかりません")
                                showShare(stored.details.name, stored.snapshot, shareTypography)
                            }
                        } },
                        onHelp = { if (!shareImports.state.blocksCanvas && !imageImports.state.blocksCanvas) guideVisible.value = true },
                    )
                    is Page.Board -> key(current.id) {
                        CanvasScreen(current.state,
                            boardName = current.name.ifBlank { "無題のボード" },
                            editorSession = boardSessions.textEditorFor(current.id, current.state.snapshot()),
                            viewportHistory = boardSessions.viewportHistoryFor(current.id, current.state.snapshot()),
                            imageResources = imageResources,
                            externalInteractionBlocked = { shareImports.state.blocksCanvas || imageImports.state.blocksCanvas ||
                                (page.value as? Page.Board)?.id != current.id },
                            onAddImage = { source, center, width, height ->
                                if (imageImports.state.phase == com.thinkcanvas.image.ImageImportPhase.FAILED)
                                    imageImports.showFailure()
                                else if (!shareImports.state.blocksCanvas && baseImportReady())
                                    imageImports.begin(source, current.id, center, width, height)
                            },
                            onImportReadiness = { ready, guard ->
                                if ((page.value as? Page.Board)?.id == current.id) {
                                    canvasOwnerId.value = current.id
                                    canvasNeutralGuard = guard
                                    canvasReady.value = ready
                                }
                            },
                            saveState = boardSessions.saveStateFor(current.id, current.state.snapshot()),
                            onRequestSave = { snapshot -> boardSessions.requestSave(current.id, snapshot) },
                            onRetrySave = { if (!shareImports.state.blocksCanvas) boardSessions.retrySave(current.id) },
                            onOpenList = {
                                if (!shareImports.state.blocksCanvas && !imageImports.state.blocksCanvas &&
                                    boardSessions.saveStateFor(current.id, current.state.snapshot()).value ==
                                    BoardSaveState.Idle && !transientPending.value &&
                                    listActionState == BoardListActionState.Idle) {
                                    navigationTargetIsList = true
                                    page.value = Page.Loading
                                    performTransient {
                                        try { showList() }
                                        catch (error: Exception) {
                                            navigationTargetIsList = false
                                            page.value = current
                                            throw error
                                        }
                                    }
                                }
                            },
                            onShareSelection = { ids -> if (!shareImports.state.blocksCanvas && !imageImports.state.blocksCanvas) performTransient {
                                val stored = store.savedBoard(current.id)
                                    ?: error("ボードが見つかりません")
                                showShare(stored.details.name, stored.snapshot, shareTypography, ids)
                            } },
                            )
                    }
                }
                if (imageState.phase == com.thinkcanvas.image.ImageImportPhase.FAILED && !imageState.hidden) {
                    AlertDialog(onDismissRequest = { imageImports.hideFailure() }, title = { Text("画像を取り込めません") },
                        text = { Text(imageState.message) },
                        confirmButton = { TextButton(onClick = {
                            val shown = page.value as? Page.Board
                            val request = imageImports.state.request
                            val savingFailed = shown != null && shown.id == request?.boardId &&
                                boardSessions.saveStateFor(shown.id, shown.state.snapshot()).value is BoardSaveState.Failed
                            if (savingFailed || baseImportReady(ignoreImage = true)) imageImports.retry(boardSessions)
                        }) { Text(if (imageState.request?.accepted == null) "閉じて選び直す" else "再試行") } },
                        dismissButton = { TextButton(onClick = { imageImports.hideFailure() }) { Text("閉じる") } })
                }
                if (!guideVisible.value && errorMessage.value == null && shareDialog.value == null && !imageState.blocksCanvas) {
                    ShareImportDialog(importState, importBoards.value,
                    destinationName = importBoards.value.firstOrNull { it.id == importState.request?.destinationId }?.name,
                    destinationReady = baseImportReady(),
                    onPick = { if (shareImports.state.phase == ShareImportPhase.PICKER &&
                        boardListActions.state.value == BoardListActionState.Idle && !transientPending.value)
                        shareImports.pickBoard(it) },
                    onChange = { shareImports.showPicker() },
                    onCreate = {
                        if (shareImports.state.phase == ShareImportPhase.PICKER && !shareImports.state.writing &&
                            boardListActions.state.value == BoardListActionState.Idle && !transientPending.value)
                            boardListActions.start(BoardListAction.Create)
                    },
                    onConfirm = { if (baseImportReady()) shareImports.confirm() },
                    onCancel = { shareImports.cancel() },
                    onRetry = {
                        val request = shareImports.state.request
                        if (shareImports.state.phase == ShareImportPhase.FAILED) {
                            request?.destinationId?.let { boardSessions.retrySave(it) }
                            shareImports.retryOpening()
                        }
                    })
                }
                shareDialog.value?.let { sharing ->
                    ShareSheet(sharing.title, sharing.bitmap, sharing.message, shareBusy.value,
                        onSave = { deliver(0) },
                        onCopy = { deliver(1) },
                        onShare = { deliver(2) },
                        onDismiss = { if (!shareBusy.value) dismissShare() })
                }
                if (guideVisible.value) GuideSheet(
                    onStart = {
                        try {
                            store.dismissGuide()
                            guideVisible.value = false
                        } catch (error: Exception) {
                            errorMessage.value = error.message ?: "案内の状態を保存できません"
                        }
                    },
                    onDismiss = { guideVisible.value = false },
                )
                errorMessage.value?.let { message ->
                    AlertDialog(onDismissRequest = { dismissListActionFailure() },
                        title = { Text("操作を完了できません") },
                        text = { Text(message) },
                        confirmButton = { TextButton(onClick = { dismissListActionFailure() }) {
                            Text(if (startupLoadFailed.value) "一覧へ" else "閉じる")
                        } },
                        dismissButton = if (startupLoadFailed.value) ({
                            TextButton(onClick = { retryStartupLoad() }) { Text("再試行") }
                        }) else null,
                    )
                }
            }
        }

        if (boardListActions.state.value == BoardListActionState.Idle) {
            performTransient {
                try {
                    shareImports.awaitInitialized()
                    if (navigationTargetIsList) showList()
                    else {
                        val restored = if (shareImports.state.request != null || initialShareAttempt)
                            store.lastOpenedBoard() else store.restore()
                        if (restored == null) showList() else openBoard(restored)
                    }
                } catch (error: Exception) {
                    startupLoadFailed.value = true
                    navigationTargetIsList = true
                    page.value = Page.List
                    throw error
                }
            }
        }
    }
}
