package com.thinkcanvas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.withStateAtLeast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.thinkcanvas.board.BoardListScreen
import com.thinkcanvas.board.GuideSheet
import com.thinkcanvas.canvas.BoardState
import com.thinkcanvas.canvas.CanvasScreen
import com.thinkcanvas.data.CanvasStore
import com.thinkcanvas.data.StoredBoard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface Page {
    data object Loading : Page
    data object List : Page
    data class Board(val id: Long, val name: String, val state: BoardState) : Page
}

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
        val boardSessions = ViewModelProvider(this)[BoardSessionViewModel::class.java]
        boardSessions.setSaveOperation { boardId, snapshot -> store.save(boardId, snapshot) }
        val boardListActions = ViewModelProvider(this)[BoardListActionViewModel::class.java]
        val page = mutableStateOf<Page>(Page.Loading)
        val cards = mutableStateOf<List<StoredBoard>>(emptyList())
        val guideVisible = mutableStateOf(false)
        val transientPending = mutableStateOf(false)
        val errorMessage = mutableStateOf<String?>(null)

        fun openBoard(stored: StoredBoard) {
            navigationTargetIsList = false
            page.value = Page.Board(stored.details.id, stored.details.name,
                boardSessions.stateFor(stored.details.id, stored.snapshot))
            guideVisible.value = store.shouldShowGuide(stored.details.id, stored.snapshot)
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
            if (transientPending.value || boardListActions.state.value != BoardListActionState.Idle)
                return
            start()
        }

        fun dismissListActionFailure() {
            val failed = boardListActions.state.value as? BoardListActionState.Failed
            if (failed == null) {
                errorMessage.value = null
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

        setContent {
            val listActionState = boardListActions.state.value
            LaunchedEffect(listActionState) {
                when (val current = listActionState) {
                    is BoardListActionState.Running -> page.value = Page.Loading
                    is BoardListActionState.Completed -> when (val outcome = current.outcome) {
                        is BoardListActionOutcome.OpenBoard -> {
                            lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {
                                if (boardListActions.state.value == current) {
                                    openBoard(outcome.board)
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
                when (val current = page.value) {
                    Page.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.loading_board))
                    }
                    Page.List -> BoardListScreen(cards.value,
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
                        onHelp = { guideVisible.value = true },
                    )
                    is Page.Board -> key(current.id) {
                        CanvasScreen(current.state,
                            boardName = current.name.ifBlank { "無題のボード" },
                            saveState = boardSessions.saveStateFor(current.id, current.state.snapshot()),
                            onRequestSave = { snapshot -> boardSessions.requestSave(current.id, snapshot) },
                            onRetrySave = { boardSessions.retrySave(current.id) },
                            onOpenList = {
                                if (boardSessions.saveStateFor(current.id, current.state.snapshot()).value ==
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
                            )
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
                errorMessage.value?.let { message ->
                    AlertDialog(onDismissRequest = { dismissListActionFailure() },
                        title = { Text("操作を完了できません") },
                        text = { Text(message) },
                        confirmButton = { TextButton(onClick = { dismissListActionFailure() }) {
                            Text("閉じる")
                        } })
                }
            }
        }

        if (boardListActions.state.value == BoardListActionState.Idle) {
            performTransient {
                if (navigationTargetIsList) showList()
                else {
                    val restored = store.restore()
                    if (restored == null) showList() else openBoard(restored)
                }
            }
        }
    }
}
