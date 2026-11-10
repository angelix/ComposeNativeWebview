// Bounded per-view queues for the raw `window.ipc` messages of the desktop backends. Each native
// bridge owns one; a view's messages are kept only once something drains them.
package dev.nucleusframework.webview.web

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * Raw `window.ipc` messages waiting to be drained, per native view handle.
 *
 * `window.ipc` exists in every frame of every page, so a page decides how many messages arrive. A
 * view's messages are therefore queued only after [open] (the view has a JS bridge to drain them
 * into) and only up to [capacity]; anything else is dropped. Safe to call from any thread.
 */
internal class IpcInbox(private val capacity: Int = DEFAULT_CAPACITY) {
    private val queues = ConcurrentHashMap<Long, ArrayBlockingQueue<String>>()

    /** Starts queueing messages for [handle]. Opening an open view changes nothing. */
    fun open(handle: Long) {
        queues.computeIfAbsent(handle) { ArrayBlockingQueue(capacity) }
    }

    /** Queues [message] and returns true, or drops it when [handle] is not open or its queue is full. */
    fun offer(handle: Long, message: String): Boolean = queues[handle]?.offer(message) ?: false

    /** Removes and returns everything queued for [handle], oldest first. */
    fun drain(handle: Long): List<String> {
        val queue = queues[handle] ?: return emptyList()
        val drained = ArrayList<String>()
        queue.drainTo(drained)
        return drained
    }

    /** Forgets [handle] and whatever is queued for it. */
    fun close(handle: Long) {
        queues.remove(handle)
    }

    private companion object {
        const val DEFAULT_CAPACITY = 1024
    }
}
