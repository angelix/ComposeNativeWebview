// Unit tests for IpcInbox, the bounded per-view queues the desktop backends keep raw `window.ipc`
// messages in. JVM only: the desktop backends are, and the inbox uses java.util.concurrent.

package dev.nucleusframework.webview.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IpcInboxTest {
    @Test
    fun dropsMessagesForAViewNothingDrains() {
        val inbox = IpcInbox(capacity = 4)
        assertFalse(inbox.offer(handle = 1, message = "a"))
        assertEquals(emptyList(), inbox.drain(1))
    }

    @Test
    fun queuesMessagesInOrderOnceAViewIsOpened() {
        val inbox = IpcInbox(capacity = 4)
        inbox.open(1)
        assertTrue(inbox.offer(1, "a"))
        assertTrue(inbox.offer(1, "b"))
        assertEquals(listOf("a", "b"), inbox.drain(1))
        assertEquals(emptyList(), inbox.drain(1), "a drain empties the queue")
    }

    @Test
    fun dropsWhatArrivesWhileTheQueueIsFull() {
        val inbox = IpcInbox(capacity = 3)
        inbox.open(1)
        val accepted = (1..10).map { inbox.offer(1, "m$it") }
        assertEquals(listOf(true, true, true) + List(7) { false }, accepted)
        assertEquals(listOf("m1", "m2", "m3"), inbox.drain(1))
        assertTrue(inbox.offer(1, "after"), "draining makes room again")
        assertEquals(listOf("after"), inbox.drain(1))
    }

    @Test
    fun keepsViewsApart() {
        val inbox = IpcInbox(capacity = 4)
        inbox.open(1)
        inbox.open(2)
        inbox.offer(1, "one")
        inbox.offer(2, "two")
        assertEquals(listOf("two"), inbox.drain(2))
        assertEquals(listOf("one"), inbox.drain(1))
    }

    @Test
    fun openingAgainKeepsWhatIsQueued() {
        val inbox = IpcInbox(capacity = 4)
        inbox.open(1)
        inbox.offer(1, "a")
        inbox.open(1)
        assertEquals(listOf("a"), inbox.drain(1))
    }

    @Test
    fun closingAViewForgetsItsMessagesAndStopsQueueing() {
        val inbox = IpcInbox(capacity = 4)
        inbox.open(1)
        inbox.offer(1, "a")
        inbox.close(1)
        assertEquals(emptyList(), inbox.drain(1))
        assertFalse(inbox.offer(1, "b"), "a closed view is not drained any more")
        inbox.open(1)
        assertEquals(emptyList(), inbox.drain(1), "a view that reuses the handle starts empty")
    }
}
