package com.thinkcanvas.share

import com.thinkcanvas.data.BoardRow

/** Store I/O does not reserve the UI; publish only while its captured owner remains ready. */
internal suspend fun loadSharePreviewIfReady(
    isReady: () -> Boolean,
    loadBoards: suspend () -> List<BoardRow>,
    candidateId: suspend () -> Long?,
    present: (List<BoardRow>, Long?) -> Unit,
) {
    if (!isReady()) return
    val boards = loadBoards()
    val candidate = candidateId()
    if (isReady()) present(boards, candidate)
}
