// Lets a desktop backend suspend its caller until the native side reports that an operation it
// started has finished. Each native bridge owns one per kind of operation.
package dev.nucleusframework.webview.web

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred

/**
 * Pairs native operations with the callers waiting for them by request id.
 *
 * [await] starts an operation and suspends until the native side calls [complete] with the id it
 * was given. Releasing a view lets go of everyone still waiting on it, so a caller never outlives
 * the native state that would have answered. Safe to call from any thread.
 */
internal class NativeCompletions {
    private class Waiter(val handle: Long, val done: CompletableDeferred<Unit>)

    private val nextRequestId = AtomicLong()
    private val waiting = ConcurrentHashMap<Long, Waiter>()

    /** Runs [start] with a fresh request id and suspends until that request is reported finished. */
    suspend fun await(handle: Long, start: (requestId: Long) -> Unit) {
        val requestId = nextRequestId.incrementAndGet()
        val done = CompletableDeferred<Unit>()
        waiting[requestId] = Waiter(handle, done)
        try {
            start(requestId)
            done.await()
        } finally {
            waiting.remove(requestId)
        }
    }

    /** Reports [requestId] finished. Unknown ids (already finished, or of a released view) are ignored. */
    fun complete(requestId: Long) {
        waiting.remove(requestId)?.done?.complete(Unit)
    }

    /** Lets go of every caller waiting on an operation of [handle]. */
    fun release(handle: Long) {
        for ((requestId, waiter) in waiting) {
            if (waiter.handle == handle && waiting.remove(requestId, waiter)) waiter.done.complete(Unit)
        }
    }
}
