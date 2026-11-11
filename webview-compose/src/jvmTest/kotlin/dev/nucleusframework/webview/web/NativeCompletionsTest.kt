// Unit tests for NativeCompletions, which suspends a caller until a native backend reports that
// the operation it started has finished. JVM only, like the desktop backends that use it.

package dev.nucleusframework.webview.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class NativeCompletionsTest {
    @Test
    fun suspendsUntilTheOperationReportsBack() = runBlocking {
        val completions = NativeCompletions()
        var started = -1L
        val waiter = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            completions.await(handle = 7) { started = it }
        }
        assertTrue(started > 0, "the operation was started with a request id")
        assertFalse(waiter.isCompleted, "still waiting for the backend")
        completions.complete(started)
        withTimeout(5_000) { waiter.await() }
    }

    @Test
    fun returnsAtOnceWhenTheOperationReportsBackWhileStarting() = runBlocking {
        val completions = NativeCompletions()
        withTimeout(5_000) { completions.await(handle = 7) { completions.complete(it) } }
    }

    @Test
    fun givesEveryOperationItsOwnRequestId() = runBlocking {
        val completions = NativeCompletions()
        val ids = mutableListOf<Long>()
        val first = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { completions.await(1) { ids += it } }
        val second = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { completions.await(1) { ids += it } }
        assertEquals(2, ids.distinct().size)
        completions.complete(ids[1])
        withTimeout(5_000) { second.await() }
        assertFalse(first.isCompleted, "finishing one operation does not finish another")
        completions.complete(ids[0])
        withTimeout(5_000) { first.await() }
    }

    @Test
    fun letsGoOfEveryCallerOfAReleasedView() = runBlocking {
        val completions = NativeCompletions()
        val released = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { completions.await(handle = 1) { } }
        val other = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { completions.await(handle = 2) { } }
        completions.release(handle = 1)
        withTimeout(5_000) { released.await() }
        assertFalse(other.isCompleted, "another view's operation keeps waiting")
        completions.release(handle = 2)
        withTimeout(5_000) { other.await() }
    }

    @Test
    fun ignoresAReportForAnUnknownRequest() {
        NativeCompletions().complete(12345)
    }
}
