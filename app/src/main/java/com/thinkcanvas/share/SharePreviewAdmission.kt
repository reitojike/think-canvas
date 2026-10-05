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

/** Recheck the captured picker/preview after store I/O before changing its destination. */
internal suspend fun loadShareBoardsIfCurrent(
    isCurrent: () -> Boolean,
    loadBoards: suspend () -> List<BoardRow>,
    publish: (List<BoardRow>) -> Unit,
) {
    if (!isCurrent()) return
    val boards = loadBoards()
    if (isCurrent()) publish(boards)
}
