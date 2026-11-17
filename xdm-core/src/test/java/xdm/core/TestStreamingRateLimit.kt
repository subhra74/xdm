// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.web.streaming.downloader.dash.DashDownloaderTask
import xdm.core.downloaders.web.streaming.downloader.hls.HlsDownloaderTask
import xdm.core.downloaders.web.streaming.manifest.dash.parseMpdManifest
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * HLS and DASH against servers that rate-limit without 429: a limit shared by every segment URL (as a server
 * limits a client) refuses connections beyond it with a 5xx or a 4xx, with or without Retry-After. Every segment
 * must arrive, the working connections must keep their speed, and a link that really expired (every request
 * refused after some segments, nothing downloading) must still be reported as expired, and a server that only
 * says "busy" as busy.
 *
 * Real media from ffmpeg, served by [MockHttpServer]. Runs with [FastRetries].
 */
class TestStreamingRateLimit : StreamingE2EBase() {

    private val servers = mutableListOf<MockHttpServer>()
    private var nextId = 7_000L

    /** When the last download was started (after its media was made), for timing. */
    private var startedAt = 0L

    @AfterEach
    fun stopServers() {
        servers.forEach { runCatching { it.stop() } }
    }

    private fun mock() = MockHttpServer().also { servers.add(it) }

    /** About 200 KB/s per connection: a segment takes a fraction of a second, a download a few seconds. */
    private fun throttled() = ConnPlan(throttleChunk = 4 * 1024, throttleSleepMs = 20)

    /** What each segment request gets: [refuse] decides from the running count of segment requests. */
    private class Behaviour(val limit: SharedLimit?, val refuse: (requestNo: Int) -> ConnPlan?) {
        val requests = AtomicInteger(0)
    }

    private fun plan(b: Behaviour, normal: ConnPlan): (ReqCtx) -> ConnPlan = {
        b.refuse(b.requests.incrementAndGet()) ?: normal
    }

    // ---------------------------------------------------------------- HLS

    private fun hlsMedia(caseName: String): List<File> {
        val d = caseDir(caseName)
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25", "-t", "8", "-an",
            "-c:v", "libx264", "-b:v", "1500k", "-pix_fmt", "yuv420p", "-g", "12",
            "-f", "hls", "-hls_time", "0.5", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
            "-hls_segment_filename", File(d, "seg%03d.ts").path, File(d, "index.m3u8").path
        )
        return d.listFiles { f -> f.name.matches(Regex("seg\\d+\\.ts")) }!!.sortedBy { it.name }
    }

    private fun serveHls(caseName: String, server: MockHttpServer, segs: List<File>, b: Behaviour): String {
        val playlist = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n")
        segs.forEachIndexed { i, seg ->
            val path = "/$caseName/s$i.ts"
            server.register(path, Endpoint(seg.readBytes(), sharedLimit = b.limit, plan = plan(b, throttled())))
            playlist.append("#EXTINF:0.5,\n").append(server.url(path)).append('\n')
        }
        playlist.append("#EXT-X-ENDLIST\n")
        server.register("/$caseName/index.m3u8", Endpoint(playlist.toString().toByteArray()))
        return server.url("/$caseName/index.m3u8")
    }

    private fun startHls(caseName: String, url: String, maxSegments: Int): StreamingTestHost {
        val h = host(caseName)
        val info = HlsDownloadTaskInfo(
            id = nextId++, fileName = "$caseName.mp4", tempDir = File(root, "$caseName/tmp").absolutePath,
            respectFileName = false, cookie = null, headers = null, origin = null, categoryId = null,
            targetFolder = File(root, "$caseName/out").absolutePath, maxPiece = 0, authInfo = null,
            url = url, audioUrl = null, audioOnly = false, independent = false,
        )
        val task = HlsDownloaderTask(info, newClient(), muxer(), h, root.absolutePath, E2EConfig(maxSegments = maxSegments))
        startedAt = System.nanoTime()
        task.start()
        return h
    }

    private fun runHls(caseName: String, b: Behaviour, maxSegments: Int = 8): StreamingTestHost {
        val segs = hlsMedia(caseName)
        return startHls(caseName, serveHls(caseName, mock(), segs, b), maxSegments)
    }

    // ---------------------------------------------------------------- DASH

    private fun dashMedia(caseName: String): File {
        val d = caseDir(caseName)
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "8",
            "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-b:v", "1500k", "-pix_fmt", "yuv420p",
            "-g", "12", "-c:a", "aac", "-ar", "44100", "-ac", "2",
            "-f", "dash", "-seg_duration", "0.5", "-use_template", "1", "-use_timeline", "0",
            File(d, "manifest.mpd").path
        )
        return d
    }

    private fun runDash(caseName: String, b: Behaviour, maxSegments: Int = 8): StreamingTestHost {
        val d = dashMedia(caseName)
        val server = mock()
        d.listFiles { f -> f.isFile }!!.forEach { f ->
            val isMedia = f.name.endsWith(".m4s")
            server.register(
                "/$caseName/${f.name}",
                if (isMedia) Endpoint(f.readBytes(), sharedLimit = b.limit, plan = plan(b, throttled()))
                else Endpoint(f.readBytes())
            )
        }
        val mpdUrl = server.url("/$caseName/manifest.mpd")
        val entry = File(d, "manifest.mpd").inputStream().use { parseMpdManifest(it, mpdUrl) }
            .first { it.video != null && it.audio != null }
        val v = entry.video!!
        val a = entry.audio!!
        val h = host(caseName)
        val info = DashDownloadTaskInfo(
            id = nextId++, fileName = "$caseName.mp4", tempDir = File(root, "$caseName/tmp").absolutePath,
            respectFileName = false, cookie = null, headers = null, origin = null, categoryId = null,
            targetFolder = File(root, "$caseName/out").absolutePath, maxPiece = 0, authInfo = null,
            videoSegments = v.segments, audioSegments = a.segments,
            url = mpdUrl, audioMime = a.mimeType, videoMime = v.mimeType,
        )
        val task = DashDownloaderTask(info, newClient(), muxer(), h, root.absolutePath, E2EConfig(maxSegments = maxSegments))
        startedAt = System.nanoTime()
        task.start()
        return h
    }

    private fun run(kind: String, caseName: String, b: Behaviour, maxSegments: Int = 8): StreamingTestHost =
        if (kind == "hls") runHls(caseName, b, maxSegments) else runDash(caseName, b, maxSegments)

    // ---------------------------------------------------------------- tests

    @ParameterizedTest(name = "{0}: limit answered {1}, Retry-After {2}")
    @CsvSource(
        "hls,503,0", "hls,500,0", "hls,403,0", "hls,404,0", "hls,403,1",
        "dash,503,0", "dash,502,0", "dash,403,0", "dash,410,0", "dash,403,1",
    )
    fun rateLimitWithout429_allSegmentsArrive(kind: String, status: Int, retryAfter: Int) {
        requireFfmpeg()
        val limit = SharedLimit(max = 3, status = status, retryAfterSecs = retryAfter)
        val h = run(kind, "rl-$kind-$status-$retryAfter", Behaviour(limit) { null })
        awaitSuccess(h, 90)
        assertTrue(limit.refused.get() > 0, "the limit was never hit")
        verifyOutput(h, expectVideo = true, expectAudio = kind == "dash", minDuration = 7.0)
    }

    /**
     * After a few segments every request is refused with no Retry-After: the segment URLs expired. Nothing
     * downloads any more, so once stalled the refusal is an expired link, not a rate limit or a busy server.
     */
    @ParameterizedTest(name = "{0}: expired, answered {1}")
    @CsvSource("hls,403", "hls,404", "hls,410", "dash,403", "dash,404")
    fun linkExpiringMidDownload_isLinkExpired(kind: String, status: Int) {
        requireFfmpeg()
        val h = run(kind, "exp-$kind-$status", Behaviour(null) { n -> if (n > 6) ConnPlan(failStatus = status) else null })
        assertEquals(DownloadError.LinkExpired, awaitFailure(h, 40), "an expired link must not read as a rate limit")
    }

    /** After a few segments every request gets a 5xx, or a 403 with Retry-After: a busy server, waited out, then reported busy. */
    @ParameterizedTest(name = "{0}: busy forever, {1}, Retry-After {2}")
    @CsvSource("hls,503,0", "hls,403,1", "dash,500,0", "dash,403,1")
    fun serverBusyForever_isServerBusy(kind: String, status: Int, retryAfter: Int) {
        requireFfmpeg()
        val h = run(kind, "busy-$kind-$status-$retryAfter", Behaviour(null) { n ->
            if (n > 6) ConnPlan(failStatus = status, retryAfterSecs = retryAfter) else null
        })
        assertEquals(DownloadError.ServerBusy, awaitFailure(h, 60), "a busy server must not read as an expired link")
    }

    /**
     * Throughput: 8 connections against a server admitting 3 (refusing the rest with a 503 or a 403, no
     * Retry-After) must finish about as fast as 3 connections against a server without a limit: the refused
     * connections wait aside without stalling the working ones.
     */
    @ParameterizedTest(name = "{0}: throughput with limit answered {1}")
    @CsvSource("hls,503", "hls,403", "dash,503", "dash,403")
    fun rateLimit_keepsSpeedOfWorkingConnections(kind: String, status: Int) {
        requireFfmpeg()
        // Timed from the task's start (after ffmpeg made the media) to the end of the download, assembling included.
        val free = run(kind, "free-$kind-$status", Behaviour(null) { null }, maxSegments = 3)
        awaitSuccess(free, 90)
        val baseline = (System.nanoTime() - startedAt) / 1_000_000

        val limit = SharedLimit(max = 3, status = status)
        val limitedHost = run(kind, "lim-$kind-$status", Behaviour(limit) { null }, maxSegments = 8)
        awaitSuccess(limitedHost, 90)
        val limited = (System.nanoTime() - startedAt) / 1_000_000

        println("THROUGHPUT $kind $status: limited $limited ms, free $baseline ms, refused ${limit.refused.get()}")
        assertTrue(limit.refused.get() > 0, "the limit was never hit")
        assertEquals(3, limit.peakActive.get(), "server limit not used fully")
        assertTrue(
            limited <= baseline * 3 / 2 + 2_000,
            "rate-limited $kind took $limited ms, 3 free connections $baseline ms: the working connections were slowed down"
        )
    }
}
