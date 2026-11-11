// Unit tests for releaseAfterHostFrames, which holds a desktop native view back until the window that
// showed it has begun the frames in which it applies what it had queued for that view. JVM only, like
// the desktop backends that use it.

package dev.nucleusframework.webview.web

import androidx.compose.runtime.BroadcastFrameClock
import kotlin.test.Test
import kotlin.test.assertEquals

class HostFramesTest {
    private var released = 0

    @Test
    fun releasesOnceTheHostsFramesHaveBegun() {
        val frames = BroadcastFrameClock()
        releaseAfterHostFrames(frames) { released++ }
        assertEquals(0, released, "the host may still apply a queued frame update to the view")

        frames.sendFrame(0)
        assertEquals(0, released, "this frame's present is the one that applies what was queued")

        frames.sendFrame(16_000_000)
        assertEquals(1, released)
    }

    @Test
    fun releasesOnlyOnce() {
        val frames = BroadcastFrameClock()
        releaseAfterHostFrames(frames) { released++ }

        repeat(5) { frames.sendFrame(it * 16_000_000L) }

        assertEquals(1, released)
    }

    @Test
    fun releasesAtOnceWhereNoWindowDrivesFrames() {
        releaseAfterHostFrames(frameClock = null) { released++ }

        assertEquals(1, released)
    }

    @Test
    fun releasesWhenTheWindowIsGone() {
        val frames = BroadcastFrameClock()
        releaseAfterHostFrames(frames) { released++ }

        frames.cancel()

        assertEquals(1, released, "a window that closed presents no more frames, and applies nothing more")
    }
}
