package com.thinkcanvas.share

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ShareSaveOperationTest {
    @Test fun canceledQueuedRequestNeverRunsAndFollowingCommandStillRuns() = runBlocking {
        val canceled = CompletableDeferred<Unit>()
        canceled.cancel()
        var writes = 0
        executeShareOperation(canceled) { writes++ }
        val following = CompletableDeferred<Unit>()
        executeShareOperation(following) { writes++; Unit }
        following.await()
        assertEquals(1, writes)
    }

    @Test fun cancellationInterruptsOnlyTheActiveShareWorker() = runBlocking {
        val result = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var committed = false
        val actor = launch {
            executeShareOperation(result) { entered.complete(Unit); gate.await(); committed = true }
            val next = CompletableDeferred<Unit>()
            executeShareOperation(next) { Unit }
            next.await()
        }
        entered.await()
        result.cancel()
        actor.join()
        assertFalse(committed)
        assertFalse(gate.isCancelled)
        assertFalse(actor.isCancelled)
    }
}
