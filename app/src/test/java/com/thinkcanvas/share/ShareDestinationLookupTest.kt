package com.thinkcanvas.share

import com.thinkcanvas.data.BoardRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ShareDestinationLookupTest {
    @Test fun pickerLookupCannotDiscardDestinationCreatedBeforeRecomposition() = runBlocking {
        var state = ShareImportState(ShareImportPhase.PICKER, ShareImportRequest(text = "shared"))
        val captured = state
        val entered = CompletableDeferred<Unit>()
        val rows = CompletableDeferred<List<BoardRow>>()
        var published = 0
        var missing = 0
        val lookup = launch {
            loadShareBoardsIfCurrent({ state === captured }, { entered.complete(Unit); rows.await() }) { boards ->
                published++
                if (state.phase == ShareImportPhase.PREVIEW && boards.none { it.id == captured.request?.destinationId }) {
                    missing++
                    state = state.copy(phase = ShareImportPhase.PICKER,
                        request = checkNotNull(state.request).copy(destinationId = null))
                }
            }
        }
        entered.await()
        state = state.copy(phase = ShareImportPhase.PREVIEW,
            request = checkNotNull(state.request).copy(destinationId = 3L))
        rows.complete(emptyList()) // The old query completes before effect cancellation/recomposition.
        lookup.join()
        assertEquals(0, published)
        assertEquals(0, missing)
        assertEquals(ShareImportPhase.PREVIEW, state.phase)
        assertEquals(3L, state.request?.destinationId)
        val current = state
        loadShareBoardsIfCurrent({ state === current }, { listOf(BoardRow(3, "new board")) }) {
            published++
            assertEquals(3L, it.single().id)
        }
        assertEquals(1, published)
    }

    @Test fun cancelledOrReplacedRequestRejectsTheSuspendedPreviewLookup() = runBlocking {
        for (replacement in listOf(ShareImportState(ShareImportPhase.EMPTY),
            ShareImportState(ShareImportPhase.PREVIEW, ShareImportRequest(text = "other", destinationId = 2L)))) {
            var state = ShareImportState(ShareImportPhase.PREVIEW,
                ShareImportRequest(text = "old", destinationId = 1L))
            val captured = state
            val entered = CompletableDeferred<Unit>()
            val rows = CompletableDeferred<List<BoardRow>>()
            var publications = 0
            val lookup = launch {
                loadShareBoardsIfCurrent({ state === captured }, { entered.complete(Unit); rows.await() }) {
                    publications++
                }
            }
            entered.await()
            state = replacement
            rows.complete(emptyList())
            lookup.join()
            assertEquals(0, publications)
            assertSame(replacement, state)
        }
    }

    @Test fun currentPreviewPublishesExistingAndMissingDestinationResultsOnce() = runBlocking {
        for (boards in listOf(emptyList(), listOf(BoardRow(1, "destination")))) {
            val state = ShareImportState(ShareImportPhase.PREVIEW,
                ShareImportRequest(text = "shared", destinationId = 1L))
            var publications = 0
            var missing = 0
            loadShareBoardsIfCurrent({ true }, { boards }) { result ->
                publications++
                if (result.none { it.id == state.request?.destinationId }) missing++
            }
            assertEquals(1, publications)
            assertEquals(if (boards.isEmpty()) 1 else 0, missing)
        }
    }
}
