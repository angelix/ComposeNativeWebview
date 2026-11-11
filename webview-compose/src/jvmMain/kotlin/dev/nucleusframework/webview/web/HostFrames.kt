// Holds a desktop native view back until the window that showed it is done with it. The window's
// host queues a view's frame updates and applies them when it presents its next frame, also for a
// view it has detached meanwhile; a view released before that present is touched after it is freed.

package dev.nucleusframework.webview.web

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The frames the host must begin before a view it showed may be released. What it had queued for
 * the view is applied when the first of them is presented, and that present has run by the time the
 * second begins.
 */
private const val HOST_FRAMES_BEFORE_RELEASE = 2

/** Outlives the composition a view leaves; continues on the thread that begins the frame. */
private val releases = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

/**
 * Runs [release] once the host that drives [frameClock] has begun the frames after which it no longer
 * touches a view it showed. Runs it at once when no window drives frames ([frameClock] null), and when
 * the window is gone, which ends its clock.
 */
internal fun releaseAfterHostFrames(frameClock: MonotonicFrameClock?, release: () -> Unit) {
    if (frameClock == null) {
        release()
        return
    }
    releases.launch(frameClock) {
        try {
            repeat(HOST_FRAMES_BEFORE_RELEASE) { withFrameNanos { } }
        } finally {
            release()
        }
    }
}
