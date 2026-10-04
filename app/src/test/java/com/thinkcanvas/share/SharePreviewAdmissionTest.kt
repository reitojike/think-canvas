package com.thinkcanvas.share

import com.thinkcanvas.data.BoardRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SharePreviewAdmissionTest {
    @Test fun editorStartedDuringBoardLookupKeepsPreviewDeferredUntilTheEditFinishes() = runBlocking {
        var ready = true
        val entered = CompletableDeferred<Unit>()
        val boards = CompletableDeferred<List<BoardRow>>()
        var previews = 0
        val lookup = launch {
            loadSharePreviewIfReady({ ready }, { entered.complete(Unit); boards.await() }, { 1L }) { _, _ -> previews++ }
        }
        entered.await()
        ready = false // The original editor starts while the store read is suspended.
        boards.complete(listOf(BoardRow(1, "編集先")))
        lookup.join()
        assertEquals(0, previews)
        ready = true // Finish the original operation, then retry against current readiness.
        loadSharePreviewIfReady({ ready }, { boards.await() }, { 1L }) { rows, destination ->
            assertEquals(1L, destination)
            assertEquals("編集先", rows.single().name)
            previews++
        }
        assertEquals(1, previews)
    }

    @Test fun pageChangedDuringLastBoardLookupRejectsTheOldCandidateAndRetriesForCurrentPage() = runBlocking {
        var page = Any()
        val oldPage = page
        val entered = CompletableDeferred<Unit>()
        val lastBoard = CompletableDeferred<Long?>()
        val shown = mutableListOf<Long?>()
        val lookup = launch {
            loadSharePreviewIfReady({ page === oldPage }, { emptyList() }, {
                entered.complete(Unit); lastBoard.await()
            }) { _, destination -> shown.add(destination) }
        }
        entered.await()
        page = Any() // A different Page/owner appears before last-board I/O returns.
        lastBoard.complete(2L)
        lookup.join()
        assertTrue(shown.isEmpty())
        val currentPage = page
        loadSharePreviewIfReady({ page === currentPage }, { emptyList() }, { 3L }) { _, destination ->
            shown.add(destination)
        }
        assertEquals(listOf(3L), shown)
    }
}
