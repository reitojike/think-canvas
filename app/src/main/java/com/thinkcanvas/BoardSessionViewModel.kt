package com.thinkcanvas

import androidx.lifecycle.ViewModel
import com.thinkcanvas.canvas.BoardSnapshot
import com.thinkcanvas.canvas.BoardState

/** Owns in-memory board edit history for the lifetime of the current Activity's process session. */
class BoardSessionViewModel : ViewModel() {
    private val boards = mutableMapOf<Long, BoardState>()

    fun stateFor(boardId: Long, initial: BoardSnapshot): BoardState =
        boards.getOrPut(boardId) {
            BoardState(initial.texts, initial.shapes, initial.arrows, initial.ink)
        }

    fun discard(boardId: Long) {
        boards.remove(boardId)
    }
}
