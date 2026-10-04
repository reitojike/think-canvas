package com.thinkcanvas.share

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

/** Cancel only this share command; cancellation must not terminate the store actor. */
internal suspend fun <T> executeShareOperation(result: CompletableDeferred<T>, block: suspend () -> T) {
    if (!result.isActive) return
    supervisorScope {
        val worker = async(start = CoroutineStart.LAZY) { block() }
        val cancellation = result.invokeOnCompletion { if (result.isCancelled) worker.cancel() }
        try {
            worker.start()
            result.complete(worker.await())
        } catch (error: Throwable) {
            result.completeExceptionally(error)
        } finally {
            cancellation.dispose()
        }
    }
}
