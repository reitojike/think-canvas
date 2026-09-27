package com.thinkcanvas

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Page {
    data object Loading : Page
    data object List : Page
    data class Board(val id: Long, val name: String, val state: BoardState) : Page
}

private data class ShareDialogState(
    val requestId: Long,
    val title: String,
    val bitmap: Bitmap? = null,
    val message: String? = null,
)

class MainActivity : ComponentActivity() {
    private var navigationTargetIsList = false

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("navigationTargetIsList", navigationTargetIsList)
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
        val page = mutableStateOf<Page>(Page.Loading)
        val cards = mutableStateOf<List<StoredBoard>>(emptyList())
        val guideVisible = mutableStateOf(false)
        val shareDialog = mutableStateOf<ShareDialogState?>(null)
        val shareBusy = mutableStateOf(false)
        val operationPending = mutableStateOf(false)
        val errorMessage = mutableStateOf<String?>(null)
        var shareRequestId = 0L
        val pendingDocument = File(cacheDir, "pending-board-image.png")

        fun notice(message: String) = Toast.makeText(this@MainActivity, message,
            Toast.LENGTH_SHORT).show()

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

        fun openBoard(stored: StoredBoard) {
            navigationTargetIsList = false
            page.value = Page.Board(stored.details.id, stored.details.name,
                BoardState(stored.snapshot.texts, stored.snapshot.shapes,
                    stored.snapshot.arrows, stored.snapshot.ink))
            guideVisible.value = store.shouldShowGuide(stored.details.id, stored.snapshot)
        }

        suspend fun showList() {
            cards.value = store.boardsWithContent()
            navigationTargetIsList = true
            guideVisible.value = false
            page.value = Page.List
        }

        fun perform(action: suspend () -> Unit) {
            if (operationPending.value) return
            operationPending.value = true
            lifecycleScope.launch {
                try {
                    action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    errorMessage.value = error.message ?: "操作を完了できません"
                } finally {
                    operationPending.value = false
                }
            }
        }

        fun showShare(title: String, snapshot: BoardSnapshot, typography: ExportTypography,
                      selectedIds: Set<String>? = null) {
            val request = ++shareRequestId
            shareDialog.value = ShareDialogState(request, title)
            lifecycleScope.launch {
                try {
                    val bitmap = withContext(Dispatchers.Default) {
                        val bounds = BoardImageRenderer.renderedBounds(snapshot, typography)
                        val plan = planShare(snapshot, selectedIds, bounds, typography)
                        BoardImageRenderer.render(plan)
                    }
                    if (shareDialog.value?.requestId == request)
                        shareDialog.value = ShareDialogState(request, title, bitmap)
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
                            shareDialog.value = null
                            notice("画像を保存しました")
                        } else {
                            withContext(Dispatchers.IO) { pendingDocument.writeBytes(bytes) }
                            createDocument.launch("think-canvas-${System.currentTimeMillis()}.png")
                            waitingForDocument = true
                        }
                        1 -> {
                            val uri = ImageDelivery.cacheUri(this@MainActivity, bytes)
                            ImageDelivery.copy(this@MainActivity, uri)
                            shareDialog.value = null
                            notice("画像をコピーしました")
                        }
                        else -> {
                            val uri = ImageDelivery.cacheUri(this@MainActivity, bytes)
                            shareResult.launch(ImageDelivery.shareIntent(this@MainActivity, uri))
                            shareDialog.value = null
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

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF23211E),
                secondary = Color(0xFFC54B32),
                background = Color(0xFFFCFCFB),
                surface = Color(0xFFFCFCFB),
            )) {
                val shareTypography = ExportTypography.from(LocalDensity.current,
                    LocalTextStyle.current)
                when (val current = page.value) {
                    Page.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.loading_board))
                    }
                    Page.List -> BoardListScreen(cards.value,
                        onOpen = { id -> perform {
                            openBoard(store.open(id) ?: error("ボードが見つかりません"))
                        } },
                        onCreate = { perform { openBoard(store.create()) } },
                        onRename = { id, name -> perform {
                            check(store.rename(id, name)) { "ボードが見つかりません" }
                            showList()
                        } },
                        onDuplicate = { id -> perform {
                            store.duplicate(id) ?: error("ボードが見つかりません")
                            showList()
                        } },
                        onDelete = { id -> perform {
                            check(store.delete(id)) { "ボードが見つかりません" }
                            showList()
                        } },
                        onShare = { id -> perform {
                            val stored = store.savedBoard(id) ?: error("ボードが見つかりません")
                            showShare(stored.details.name, stored.snapshot, shareTypography)
                        } },
                        onHelp = { guideVisible.value = true },
                    )
                    is Page.Board -> key(current.id) {
                        CanvasScreen(current.state,
                            boardName = current.name.ifBlank { "無題のボード" },
                            onOpenList = {
                                if (!operationPending.value) {
                                    navigationTargetIsList = true
                                    page.value = Page.Loading
                                    perform {
                                        try { showList() }
                                        catch (error: Exception) {
                                            navigationTargetIsList = false
                                            page.value = current
                                            throw error
                                        }
                                    }
                                }
                            },
                            onShareSelection = { ids -> perform {
                                val stored = store.savedBoard(current.id)
                                    ?: error("ボードが見つかりません")
                                showShare(stored.details.name, stored.snapshot, shareTypography, ids)
                            } },
                            onCommittedChange = {
                                store.save(current.id, current.state.snapshot())
                            })
                    }
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
                shareDialog.value?.let { sharing ->
                    ShareSheet(sharing.title, sharing.bitmap, sharing.message, shareBusy.value,
                        onSave = { deliver(0) },
                        onCopy = { deliver(1) },
                        onShare = { deliver(2) },
                        onDismiss = { shareDialog.value = null })
                }
                errorMessage.value?.let { message ->
                    AlertDialog(onDismissRequest = { errorMessage.value = null },
                        title = { Text("操作を完了できません") },
                        text = { Text(message) },
                        confirmButton = { TextButton(onClick = { errorMessage.value = null }) {
                            Text("閉じる")
                        } })
                }
            }
        }

        perform {
            if (navigationTargetIsList) showList()
            else {
                val restored = store.restore()
                if (restored == null) showList() else openBoard(restored)
            }
        }
    }
}
