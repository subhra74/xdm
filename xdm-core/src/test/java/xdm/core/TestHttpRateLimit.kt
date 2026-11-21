// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import xdm.core.downloaders.DownloadError
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Servers that rate-limit without 429: they turn away connections beyond a limit with a 5xx or a 4xx (403,
 * 410, 401), with or without Retry-After, while the connections they admitted keep downloading. The download
 * must finish intact and about as fast as with only the admitted connections, and must still tell a link that
 * really expired (every request refused, nothing downloading) from a rate limit.
 *
 * Runs with [FastRetries]: "stalled" after 2 s without data, a busy server given up after 8 s.
 */
class TestHttpRateLimit : HttpDownloadTestBase() {

    /** About 1.6 MB/s per connection, so a download takes long enough for the limit to matter. */
    private fun throttled() = ConnPlan(throttleChunk = 32 * 1024, throttleSleepMs = 20)

    /**
     * Like [throttled], but the server closes every connection after 256 KB, so chunks keep reconnecting: an
     * open transfer isn't cut by a link expiring, the next request is refused.
     */
    private fun reconnecting() = throttled().copy(truncateAfterBytes = 256 * 1024)

    @ParameterizedTest(name = "limit answered {0}, Retry-After {1}")
    @CsvSource("503,0", "500,0", "502,0", "504,0", "403,0", "410,0", "401,0", "416,0", "503,1", "403,1")
    fun rateLimitWithout429_completesIntact(status: Int, retryAfter: Int) {
        val bytes = randomData(6 * 1024 * 1024 + 321, seed = 900L + status)
        val path = "/rl-$status-$retryAfter"
        val ep = server.register(path, Endpoint(
            bytes, maxConcurrent = 3, limitStatus = status, limitRetryAfterSecs = retryAfter, plan = { throttled() },
        ))
        val host = host()
        newTask(900L + status * 10 + retryAfter, path, host, maxSegments = 8).start()

        awaitSuccess(host, timeoutSec = 60)
        assertChecksumMatches(host, bytes)
        assertTrue(ep.refusedByLimit.get() > 0, "expected connections beyond the limit to be refused")
    }

    /**
     * The link stops working mid-download: every new request is refused with no Retry-After. The admitted
     * connections finish what they were sending, then nothing downloads, and the refusal is an expired link,
     * reported within about the stall window.
     */
    @ParameterizedTest(name = "expired link answered {0}")
    @CsvSource("403", "410", "401")
    fun linkExpiringMidDownload_isLinkExpired(status: Int) {
        val bytes = randomData(8 * 1024 * 1024, seed = 950L + status)
        val expiresAt = AtomicLong(Long.MAX_VALUE)
        val path = "/expire-$status"
        server.register(path, Endpoint(bytes, plan = {
            if (System.currentTimeMillis() >= expiresAt.get()) ConnPlan(failStatus = status) else reconnecting()
        }))
        val host = host()
        val task = newTask(950L + status, path, host, maxSegments = 4)
        task.start()
        assertTrue(waitFor(15_000) { task.downloadedBytes > 512 * 1024 }, "no progress before the link expired")
        expiresAt.set(System.currentTimeMillis())
        val expired = System.currentTimeMillis()

        assertTrue(host.latch.await(30, TimeUnit.SECONDS), "download did not settle")
        assertEquals(DownloadError.LinkExpired, host.failure, "an expired link must not read as a busy server or network error")
        val took = System.currentTimeMillis() - expired
        // The open connections send at most 256 KB more, then the 2 s stall window, then one backoff.
        assertTrue(took < 12_000, "expiry reported after $took ms")
    }

    /**
     * A link expiring as it does in practice: the transfers already open stop sending mid-body (the client's
     * read times out, 1 s here), and every new connection is refused with no Retry-After. The timeouts are
     * network errors and the refusals come while nothing downloads, so it ends as an expired link, not a
     * network error or a busy server.
     */
    @ParameterizedTest(name = "expired, open transfers time out, new connections get {0}")
    @CsvSource("403", "410")
    fun linkExpiring_openTransfersTimeOut_newConnectionsRefused(status: Int) {
        val bytes = randomData(8 * 1024 * 1024, seed = 960L + status)
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        val path = "/expire-timeout-$status"
        server.register(path, Endpoint(bytes, plan = {
            if (expired.get()) ConnPlan(failStatus = status) else throttled().copy(stallWhen = { expired.get() })
        }))
        val host = host()
        val task = newTaskForUrl(
            960L + status, server.url(path), host, TestConfig(maxSegments = 4), client = httpClientWithReadTimeout(1),
        )
        task.start()
        assertTrue(waitFor(15_000) { task.downloadedBytes > 512 * 1024 }, "no progress before the link expired")
        assertTrue(task.downloadedBytes < bytes.size, "finished before the link expired")
        expired.set(true)
        val at = System.currentTimeMillis()

        assertTrue(host.latch.await(30, TimeUnit.SECONDS), "download did not settle")
        assertEquals(DownloadError.LinkExpired, host.failure, "an expired link must not read as a network error or a busy server")
        val took = System.currentTimeMillis() - at
        // 1 s read timeout, the 2 s stall window, a backoff or two.
        assertTrue(took < 12_000, "expiry reported after $took ms")
    }

    /**
     * Every request is refused from some point on, but the refusal says when to come back (Retry-After), or is
     * a 5xx: a busy server, not an expired link. It is waited out for the budget, then reported as busy.
     */
    @ParameterizedTest(name = "busy forever: {0}, Retry-After {1}")
    @CsvSource("503,0", "500,0", "403,1")
    fun serverBusyForever_isServerBusy(status: Int, retryAfter: Int) {
        val bytes = randomData(8 * 1024 * 1024, seed = 970L + status)
        val busyFrom = AtomicLong(Long.MAX_VALUE)
        val path = "/busy-$status-$retryAfter"
        server.register(path, Endpoint(bytes, plan = {
            if (System.currentTimeMillis() >= busyFrom.get()) ConnPlan(failStatus = status, retryAfterSecs = retryAfter)
            else reconnecting()
        }))
        val host = host()
        val task = newTask(970L + status * 10 + retryAfter, path, host, maxSegments = 4)
        task.start()
        assertTrue(waitFor(15_000) { task.downloadedBytes > 512 * 1024 }, "no progress before the server got busy")
        busyFrom.set(System.currentTimeMillis())

        assertTrue(host.latch.await(40, TimeUnit.SECONDS), "download did not settle")
        assertEquals(DownloadError.ServerBusy, host.failure, "a busy server must not read as an expired link")
    }

    /**
     * Throughput: a server admitting 3 connections and refusing the rest (with a 503 or a 403, no Retry-After)
     * must not slow the download below what those 3 connections deliver: the refused ones wait aside without
     * stalling the working ones. Compared with the same download asking for only 3 connections of a server
     * without a limit.
     */
    @ParameterizedTest(name = "throughput with limit answered {0}")
    @CsvSource("503", "403", "416")
    fun rateLimit_keepsSpeedOfWorkingConnections(status: Int) {
        val bytes = randomData(10 * 1024 * 1024, seed = 990L + status)

        server.register("/free-$status", Endpoint(bytes, plan = { throttled() }))
        val baseHost = host()
        var t0 = System.nanoTime()
        newTask(990L + status, "/free-$status", baseHost, maxSegments = 3).start()
        awaitSuccess(baseHost, timeoutSec = 60)
        val baseline = (System.nanoTime() - t0) / 1_000_000

        val ep = server.register("/limited-$status", Endpoint(bytes, maxConcurrent = 3, limitStatus = status, plan = { throttled() }))
        val host = host()
        t0 = System.nanoTime()
        newTask(991L + status, "/limited-$status", host, maxSegments = 8).start()
        awaitSuccess(host, timeoutSec = 60)
        val limited = (System.nanoTime() - t0) / 1_000_000
        assertChecksumMatches(host, bytes)

        println("THROUGHPUT http $status: limited $limited ms, free $baseline ms, refused ${ep.refusedByLimit.get()}")
        assertTrue(ep.refusedByLimit.get() > 0, "the limit was never hit")
        assertTrue(ep.peakActive.get() == 3, "server limit not used fully: peak ${ep.peakActive.get()}")
        assertTrue(
            limited <= baseline * 3 / 2 + 1_500,
            "rate-limited download took $limited ms, 3 free connections $baseline ms: the working connections were slowed down"
        )
    }
}
