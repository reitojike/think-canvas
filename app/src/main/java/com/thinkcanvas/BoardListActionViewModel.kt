package com.thinkcanvas

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thinkcanvas.data.CanvasStore
import com.thinkcanvas.data.StoredBoard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal sealed interface BoardListAction {
    data class Open(val boardId: Long) : BoardListAction
    data object Create : BoardListAction
    data class Rename(val boardId: Long, val name: String) : BoardListAction
    data class Duplicate(val boardId: Long) : BoardListAction
    data class Delete(val boardId: Long) : BoardListAction
}

internal sealed interface BoardListActionOutcome {
    data class OpenBoard(val board: StoredBoard) : BoardListActionOutcome
    data object ReloadList : BoardListActionOutcome
}

internal sealed interface BoardListActionState {
    data object Idle : BoardListActionState
    data class Running(val action: BoardListAction) : BoardListActionState
    data class Completed(val action: BoardListAction, val outcome: BoardListActionOutcome) : BoardListActionState
    data class Failed(val action: BoardListAction, val message: String) : BoardListActionState
}

class BoardListActionViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState: MutableState<BoardListActionState> = mutableStateOf(BoardListActionState.Idle)
    internal val state: MutableState<BoardListActionState> get() = mutableState
    internal var execute: suspend (BoardListAction) -> BoardListActionOutcome = { action ->
        val store = CanvasStore.get(getApplication())
        when (action) {
            is BoardListAction.Open -> BoardListActionOutcome.OpenBoard(
                store.open(action.boardId) ?: error("ボードが見つかりません"))
            BoardListAction.Create -> BoardListActionOutcome.OpenBoard(store.create())
            is BoardListAction.Rename -> {
                check(store.rename(action.boardId, action.name)) { "ボードが見つかりません" }
                BoardListActionOutcome.ReloadList
            }
            is BoardListAction.Duplicate -> {
                check(store.duplicate(action.boardId) != null) { "ボードが見つかりません" }
                BoardListActionOutcome.ReloadList
            }
            is BoardListAction.Delete -> {
                check(store.delete(action.boardId)) { "ボードが見つかりません" }
                BoardListActionOutcome.ReloadList
            }
        }
    }

    internal fun start(action: BoardListAction): Boolean {
        if (mutableState.value != BoardListActionState.Idle) return false
        mutableState.value = BoardListActionState.Running(action)
        viewModelScope.launch {
            try {
                mutableState.value = BoardListActionState.Completed(action, execute(action))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = BoardListActionState.Failed(
                    action, error.message ?: "操作を完了できません")
            }
        }
        return true
    }

    internal fun consume(completed: BoardListActionState.Completed) {
        if (mutableState.value == completed) mutableState.value = BoardListActionState.Idle
    }

    internal fun acknowledgeFailure(failed: BoardListActionState.Failed) {
        if (mutableState.value == failed) mutableState.value = BoardListActionState.Idle
    }

    internal fun failContinuation(completed: BoardListActionState.Completed, message: String) {
        if (mutableState.value == completed)
            mutableState.value = BoardListActionState.Failed(completed.action, message)
    }
}
