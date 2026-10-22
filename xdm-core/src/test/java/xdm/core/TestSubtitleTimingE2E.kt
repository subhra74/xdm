package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.SubtitleTrack
import xdm.core.downloaders.subtitleFileName
import xdm.core.downloaders.web.streaming.downloader.dash.DashDownloaderTask
import xdm.core.downloaders.web.streaming.downloader.hls.HlsDownloaderTask
import xdm.core.downloaders.web.streaming.manifest.hls.HlsParser
import xdm.core.downloaders.web.streaming.manifest.dash.parseMpdManifest
import java.io.File

/**
 * Sidecar subtitle timing, end to end: ffmpeg encodes a test video together with a known SRT into
 * HLS (video + WebVTT subtitle playlist), the real parser, [HlsDownloaderTask] and TransmuxingMuxer
 * download and mux it, and the `.srt` written beside the output must put every cue at the same
 * moment of the video as the source SRT did.
 *
 * Ground truth: the source cue times are relative to video frame 0, and ffprobe reports where frame 0
 * sits in the output (`start_time` of its video stream), so each output cue must start at
 * `source + start_time`. The tolerance is one frame at 25 fps.
 *
 * How ffmpeg times WebVTT (checked with ffmpeg 9): it writes no X-TIMESTAMP-MAP; with TS segments
 * cue time `t` is PTS `t + 1.4 s` (the mpegts muxer's start delay, `-output_ts_offset` added to both),
 * with fMP4 it is media time `t`. The cases rewrite the segments into the shapes real packagers use:
 * a stream-wide map, per-segment maps, no map (cue time = PTS, as the spec reads it), a 33-bit PTS wrap.
 */
class TestSubtitleTimingE2E : StreamingE2EBase() {

    private var nextId = 3000L

    private data class Cue(val startMs: Long, val endMs: Long, val text: String)

    /** Source cues, relative to video frame 0. The second spans segment boundaries. */
    private val source = listOf(
        Cue(500, 1200, "First"),
        Cue(1500, 3500, "Across segments"),
        Cue(4000, 4800, "Fourth"),
        Cue(5200, 5900, "Last"),
    )

    /** ffmpeg's TS start delay: its WebVTT cue time 0 is this PTS. */
    private val tsDelayTicks = 126_000L

    private val frameMs = 40L

    // ------------------------------------------------------------------------------- cases

    @Test fun t1_tsStreamWideMap() {
        requireTools()
        val d = genHls("t1", "mpegts")
        rewriteVtt(d) { _, text -> withMap(text, tsDelayTicks, 0) }
        assertTimed(runHlsMaster("t1"))
    }

    @Test fun t2_tsOffsetTimeline() {
        requireTools()
        // Video PTS starts at 11.4 s, cue times at 10.5 s: the map ties them.
        val d = genHls("t2", "mpegts", "-output_ts_offset", "10")
        rewriteVtt(d) { _, text -> withMap(text, tsDelayTicks, 0) }
        assertTimed(runHlsMaster("t2"))
    }

    @Test fun t3_tsNoMapCueTimeIsPts() {
        requireTools()
        // Without a map the spec (and hls.js) read cue time 0 as PTS 0.
        val d = genHls("t3", "mpegts")
        rewriteVtt(d) { _, text -> shiftCues(text, tsDelayTicks / 90) }
        assertTimed(runHlsMaster("t3"))
    }

    @Test fun t4_tsPerSegmentMap() {
        requireTools()
        // Each segment maps its own start: cue time k s <-> PTS 20 + 1.4 + k s (cue times made 0-based).
        val d = genHls("t4", "mpegts", "-output_ts_offset", "20")
        rewriteVtt(d) { k, text ->
            withMap(shiftCues(text, -20_000), tsDelayTicks + (20 + k) * 90_000L, k * 1000L)
        }
        assertTimed(runHlsMaster("t4"))
    }

    @Test fun t5_tsPtsWrapsMidStream() {
        requireTools()
        // PTS starts 2 s before the 33-bit wrap: later segments' MPEGTS values are small again.
        val wrap = 1L shl 33
        val offsetMs = (wrap - tsDelayTicks) / 90 - 2000
        val d = genHls("t5", "mpegts", "-output_ts_offset", "%.3f".format(offsetMs / 1000.0))
        rewriteVtt(d) { k, text ->
            val local = k * 1000L
            val mpegTs = (tsDelayTicks + (offsetMs + local) * 90) % wrap
            withMap(shiftCues(text, -offsetMs), mpegTs, local)
        }
        assertTimed(runHlsMaster("t5"))
    }

    @Test fun t6_fmp4NoMap() {
        requireTools()
        // fMP4: ffmpeg's cue times are media times, which is what "no map" means.
        genHls("t6", "fmp4")
        assertTimed(runHlsMaster("t6"))
    }

    @Test fun t7_alternateAudioWithMap() {
        requireTools()
        val d = genHls("t7", "mpegts", varStreamMap = "a:0,agroup:aud v:0,s:0,agroup:aud,sgroup:subs")
        rewriteVtt(d) { _, text -> withMap(text, tsDelayTicks, 0) }
        assertTimed(runHlsMaster("t7"))
    }

    @Test fun t8_dashSidecarVtt() {
        requireTools()
        val d = caseDir("t8")
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "6",
            "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25",
            "-c:a", "aac", "-ar", "44100",
            "-f", "dash", "-seg_duration", "1", "-use_template", "1", "-use_timeline", "1",
            File(d, "manifest.mpd").path
        )
        // DASH WebVTT is relative to the period start, where frame 0 is.
        File(d, "subs.vtt").writeText("WEBVTT\n\n" + source.joinToString("\n") {
            "${vttTime(it.startMs)} --> ${vttTime(it.endMs)}\n${it.text}\n"
        })
        val mpd = File(d, "manifest.mpd")
        mpd.writeText(
            mpd.readText().replace(
                "</Period>",
                """<AdaptationSet contentType="text" mimeType="text/vtt" lang="en">
                     <Representation id="sub" bandwidth="100"><BaseURL>subs.vtt</BaseURL></Representation>
                   </AdaptationSet></Period>"""
            )
        )
        val entry = mpd.inputStream().use { parseMpdManifest(it, url("t8/manifest.mpd")) }
            .first { it.video != null && it.audio != null }
        val h = host("t8")
        val id = nextId++
        val info = DashDownloadTaskInfo(
            id = id, fileName = "t8.mp4", tempDir = File(root, "t8/tmp").absolutePath, respectFileName = false,
            cookie = null, headers = null, origin = null, categoryId = null,
            targetFolder = File(root, "t8/out").absolutePath, maxPiece = 0, authInfo = null,
            videoSegments = entry.video!!.segments, audioSegments = entry.audio!!.segments,
            url = url("t8/manifest.mpd"), audioMime = entry.audio!!.mimeType, videoMime = entry.video!!.mimeType,
            subtitles = listOf(entry.subtitles.single()),
        )
        DashDownloaderTask(info, newClient(), muxer(), h, root.absolutePath, config()).start()
        awaitSuccess(h)
        assertTimed(h to entry.subtitles.single())
    }

    // ----------------------------------------------------------------------------- helpers

    private fun requireTools() {
        requireFfmpeg()
        Assumptions.assumeTrue(ffprobe != null, "ffprobe not on PATH; skipping")
    }

    /** ffmpeg HLS with a WebVTT subtitle rendition from [source]; returns the case folder. */
    private fun genHls(
        caseName: String, segmentType: String, vararg extra: String,
        varStreamMap: String = "v:0,a:0,s:0,sgroup:subs",
    ): File {
        val d = caseDir(caseName)
        val srt = File(d, "source.srt").apply {
            writeText(source.withIndex().joinToString("\n") { (i, c) ->
                "${i + 1}\n${srtTime(c.startMs)} --> ${srtTime(c.endMs)}\n${c.text}\n"
            })
        }
        val ext = if (segmentType == "fmp4") "m4s" else "ts"
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=160x120:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-i", srt.path, "-t", "6",
            "-map", "0:v", "-map", "1:a", "-map", "2:s", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25",
            "-c:a", "aac", "-ar", "44100", "-c:s", "webvtt", "-metadata:s:s:0", "language=en", *extra,
            "-f", "hls", "-hls_time", "1", "-hls_list_size", "0", "-hls_segment_type", segmentType,
            "-var_stream_map", varStreamMap, "-master_pl_name", "master.m3u8",
            "-hls_segment_filename", File(d, "s%v_%d.$ext").path, File(d, "index_%v.m3u8").path
        )
        return d
    }

    /** Parses the master like the app does and downloads its A/V variant with the subtitle picked. */
    private fun runHlsMaster(caseName: String): Pair<StreamingTestHost, SubtitleTrack> {
        val masterUrl = url("$caseName/master.m3u8")
        val entry = HlsParser.parseMasterPlaylist(File(root, "$caseName/master.m3u8").readLines().iterator(), masterUrl)
            .getOrThrow().first { it.videoPlaylist != null && it.subtitles.isNotEmpty() }
        val track = entry.subtitles.single()
        val h = host(caseName)
        val info = HlsDownloadTaskInfo(
            id = nextId++, fileName = "$caseName.mp4",
            tempDir = File(root, "$caseName/tmp").absolutePath, respectFileName = false,
            cookie = null, headers = null, origin = null, categoryId = null,
            targetFolder = File(root, "$caseName/out").absolutePath, maxPiece = 0, authInfo = null,
            url = entry.videoPlaylist.toString(), audioUrl = entry.audioPlaylist?.toString(),
            independent = false, subtitles = listOf(track),
        )
        HlsDownloaderTask(info, newClient(), muxer(), h, root.absolutePath, config()).start()
        awaitSuccess(h)
        return h to track
    }

    /** Asserts the sidecar beside the output holds [source], each cue at its frame in the output. */
    private fun assertTimed(result: Pair<StreamingTestHost, SubtitleTrack>) {
        val (h, track) = result
        val video = h.finalFile!!
        val srt = File(video.parentFile, subtitleFileName(video.name, track.language))
        assertTrue(srt.isFile, "no sidecar ${srt.name} beside ${video.name}")
        val frame0 = Math.round(videoStartSec(video) * 1000)
        val got = parseSrt(srt.readText(Charsets.UTF_8).removePrefix("﻿"))
        assertEquals(source.map { it.text }, got.map { it.text }, "cues in ${srt.name} (frame 0 at $frame0 ms): $got")
        for ((want, cue) in source.zip(got)) {
            val msg = "'${cue.text}': ${cue.startMs}-${cue.endMs} ms, frame 0 at $frame0 ms, " +
                "expected ${want.startMs + frame0}-${want.endMs + frame0} ms"
            assertTrue(Math.abs(cue.startMs - (want.startMs + frame0)) <= frameMs, msg)
            assertTrue(Math.abs(cue.endMs - (want.endMs + frame0)) <= frameMs, msg)
        }
    }

    /** Where the output's first video frame is presented, from ffprobe (edit lists included). */
    private fun videoStartSec(file: File): Double {
        val (_, out) = exec(
            ffprobe!!, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=start_time",
            "-of", "csv=p=0", file.absolutePath
        )
        return out.trim().toDoubleOrNull() ?: error("no video start_time for $file: $out")
    }

    /** Rewrites each subtitle segment (in playlist order) of the case folder [d]. */
    private fun rewriteVtt(d: File, transform: (index: Int, text: String) -> String) {
        val playlist = d.listFiles { f -> f.name.endsWith("_vtt.m3u8") }!!.single()
        val segments = playlist.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(segments.size > 3, "expected several subtitle segments, got $segments")
        segments.forEachIndexed { k, name ->
            val f = File(d, name)
            f.writeText(transform(k, f.readText()))
        }
    }

    private fun withMap(vtt: String, mpegTs: Long, localMs: Long): String =
        vtt.replaceFirst("WEBVTT", "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:$mpegTs,LOCAL:${vttTime(localMs)}")

    private fun shiftCues(vtt: String, deltaMs: Long): String =
        vtt.lines().joinToString("\n") { line ->
            if (!line.contains("-->")) line
            else line.split("-->").joinToString(" --> ") { vttTime(parseTime(it.trim()) + deltaMs) }
        }

    private fun parseSrt(text: String): List<Cue> =
        text.split(Regex("\\r?\\n\\r?\\n")).mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val timing = lines.indexOfFirst { it.contains("-->") }
            if (timing < 0) return@mapNotNull null
            val (a, b) = lines[timing].split("-->").map { parseTime(it.trim()) }
            Cue(a, b, lines.drop(timing + 1).joinToString("\n"))
        }

    private fun parseTime(s: String): Long {
        val f = s.replace(',', '.').split(':')
        val h = if (f.size == 3) f[0].toLong() else 0L
        val m = f[f.size - 2].toLong()
        val (sec, ms) = f.last().split('.')
        return ((h * 60 + m) * 60 + sec.toLong()) * 1000 + ms.padEnd(3, '0').take(3).toLong()
    }

    private fun vttTime(ms: Long) =
        "%02d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    private fun srtTime(ms: Long) = vttTime(ms).replace('.', ',')
}
