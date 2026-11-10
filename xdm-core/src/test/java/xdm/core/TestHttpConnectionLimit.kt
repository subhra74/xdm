// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.downloaders.DownloadError
import java.util.concurrent.TimeUnit

/**
 * A server that refuses connections beyond its limit with 403 (as some CDNs do, "deny by rate limit") while
 * the ones it admitted keep downloading. That is not an expired link: the download must keep to the
 * connections the server allows and finish, after a resume too, and only a link that refuses everything is
 * reported as expired.
 */
class TestHttpConnectionLimit : HttpDownloadTestBase() {

    private fun limited(path: String, bytes: ByteArray) =
        server.register(path, Endpoint(bytes, maxConcurrent = 3, limitStatus = 403, plan = {
            ConnPlan(throttleChunk = 32 * 1024, throttleSleepMs = 10)
        }))

    @Test
    fun forbiddenBeyondServerLimit_completesIntact() {
        val bytes = randomData(6 * 1024 * 1024 + 777, seed = 501)
        val ep = limited("/limit403", bytes)
        val host = host()
        newTask(501, "/limit403", host, maxSegments = 8).start()

        awaitSuccess(host, timeoutSec = 90)
        assertChecksumMatches(host, bytes)
        assertTrue(ep.refusedByLimit.get() > 0, "expected connections beyond the limit to be refused")
        assertTrue(ep.refusedByLimit.get() < 40, "kept opening refused connections: ${ep.refusedByLimit.get()}")
    }

    @Test
    fun forbiddenBeyondServerLimit_resumeCompletesIntact() {
        val bytes = randomData(6 * 1024 * 1024 + 99, seed = 502)
        val ep = limited("/limit403-resume", bytes)
        val first = host()
        val task = newTask(502, "/limit403-resume", first, maxSegments = 8)
        task.start()
        assertTrue(waitFor(20_000) { task.downloadedBytes > 1024 * 1024 }, "no progress before the pause")
        task.stop()
        assertTrue(first.pauseLatch.await(5, TimeUnit.SECONDS), "pause callback should fire")
        assertEquals(null, first.failure, "failed before the pause: ${first.failure}")

        // The paused download's responses still hold the server's slots until their handlers notice.
        assertTrue(waitFor(10_000) { ep.active.get() == 0 }, "server connections still open after the pause")
        // Resumes every saved segment at once, more than the server admits.
        val second = host()
        newTask(502, "/limit403-resume", second, maxSegments = 8).start()
        awaitSuccess(second, timeoutSec = 90)
        assertChecksumMatches(second, bytes)
    }
}
