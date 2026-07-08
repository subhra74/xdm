import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.web.streaming.downloader.dash.DashDownloaderTask
import xdm.core.downloaders.web.streaming.manifest.dash.MpdEntry
import xdm.core.downloaders.web.streaming.manifest.dash.parseMpdManifest
import java.io.File

/**
 * End-to-end DASH VOD tests. ffmpeg builds a real MPD + segments; the test runs the *real*
 * [parseMpdManifest] (the app-layer step that expands a Representation into concrete segment URLs),
 * feeds the result to the real [DashDownloaderTask] + TransmuxingMuxer, and ffprobe-verifies the
 * committed output.
 *
 * Cases: D1 SegmentTemplate/$Number$ · D2 SegmentTimeline/$Time$ · D3 SegmentList · D4 single-file
 * indexRange (documented limitation) · D6 multi-Representation ABR · D7 BaseURL resolution ·
 * D8 multi-Period · D9 HEVC · D10 WebM VP9/Opus -> MKV · D12 ContentProtection rejection.
 */
class TestDashE2E : StreamingE2EBase() {

    private var nextId = 2000L

    /** Parse [mpdRel]'s MPD (served + on disk) and build+run a DASH task from the first A/V entry. */
    private fun runDash(caseName: String, mpdRel: String, expectMkv: Boolean = false): StreamingTestHost {
        val mpdFile = File(root, mpdRel)
        val entries: List<MpdEntry> = mpdFile.inputStream().use { parseMpdManifest(it, url(mpdRel)) }
        val entry = entries.firstOrNull { it.video != null && it.audio != null }
            ?: error("no video+audio entry parsed from $mpdRel")
        val v = entry.video!!; val a = entry.audio!!
        val h = host(caseName)
        val info = DashDownloadTaskInfo(
            id = nextId++, fileName = "$caseName.${if (expectMkv) "mkv" else "mp4"}",
            tempDir = File(root, "$caseName/tmp").absolutePath, respectFileName = false,
            cookie = null, headers = null, origin = null, autoCategorize = false,
            defaultDownloadFolder = File(root, "$caseName/out").absolutePath,
            userSelectedDownloadFolder = null, maxPiece = 0, authInfo = null,
            videoSegments = v.segments,
            audioSegments = a.segments,
            url = url(mpdRel), audioMime = a.mimeType, videoMime = v.mimeType,
        )
        DashDownloaderTask(info, newClient(), muxer(), h, root.absolutePath, config()).start()
        awaitSuccess(h)
        return h
    }

    /** Generate a standard H.264+AAC DASH package into [caseName]/ with the given addressing opts. */
    private fun genDash(caseName: String, vararg dashOpts: String) {
        val d = caseDir(caseName)
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "3",
            "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p",
            "-g", "25", "-c:a", "aac", "-ar", "44100", "-ac", "2",
            "-f", "dash", "-seg_duration", "1", *dashOpts, File(d, "manifest.mpd").path
        )
    }

    // ---- D1: SegmentTemplate + $Number$ (also proves separate A/V sets merge into one file) ----
    @Test fun d1_segmentTemplateNumber() {
        requireFfmpeg()
        genDash("d1", "-use_template", "1", "-use_timeline", "0")
        val h = runDash("d1", "d1/manifest.mpd")
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 2.0)
    }

    // ---- D2: SegmentTemplate + SegmentTimeline ($Time$) -------------------------------------
    @Test fun d2_segmentTimeline() {
        requireFfmpeg()
        genDash("d2", "-use_template", "1", "-use_timeline", "1")
        val h = runDash("d2", "d2/manifest.mpd")
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 2.0)
    }

    // ---- D3: SegmentList (explicit SegmentURL entries) --------------------------------------
    @Test fun d3_segmentList() {
        requireFfmpeg()
        genDash("d3", "-use_template", "0", "-use_timeline", "0")
        Assume.assumeTrue("expected SegmentList", File(caseDir("d3"), "manifest.mpd").readText().contains("SegmentList"))
        val h = runDash("d3", "d3/manifest.mpd")
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 2.0)
    }

    // ---- D4: single-file addressing via SegmentList mediaRange (byte ranges into one file) ---
    @Test fun d4_singleFileByteRange() {
        requireFfmpeg()
        genDash("d4", "-single_file", "1")
        val mpd = File(caseDir("d4"), "manifest.mpd")
        Assume.assumeTrue("expected mediaRange single-file MPD", mpd.readText().contains("mediaRange"))
        // The whole representation is one file; every segment is a byte range into it. Parsing must
        // now yield ranged segments, and the downloader must fetch each range and mux them.
        val entries = mpd.inputStream().use { parseMpdManifest(it, url("d4/manifest.mpd")) }
        val v = entries.first { it.video != null }.video!!
        assertTrue("expected byte-ranged single-file segments", v.segments.all { it.range != null } && v.segments.size > 1)
        val h = runDash("d4", "d4/manifest.mpd")
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 2.0)
    }

    // ---- D6: multiple Representations in one AdaptationSet (ABR) -----------------------------
    @Test fun d6_multiRepresentationAbr() {
        requireFfmpeg()
        val d = caseDir("d6")
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "3",
            "-filter_complex", "[0:v]split=2[hi][lo];[hi]scale=320:240,format=yuv420p[a];[lo]scale=160:120,format=yuv420p[b]",
            "-map", "[a]", "-map", "[b]", "-map", "1:a",
            "-c:v", "libx264", "-profile:v", "main", "-g", "25", "-c:a", "aac", "-ar", "44100",
            "-adaptation_sets", "id=0,streams=0,1 id=1,streams=2",
            "-f", "dash", "-seg_duration", "1", "-use_template", "1", "-use_timeline", "0",
            File(d, "manifest.mpd").path
        )
        val h = runDash("d6", "d6/manifest.mpd")
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 2.0)
    }

    // ---- D7: BaseURL resolution (segments live under a sub-path via <BaseURL>) ----------------
    @Test fun d7_baseUrlResolution() {
        requireFfmpeg()
        val d = caseDir("d7")
        genDash("d7", "-use_template", "1", "-use_timeline", "0")
        // Move every segment/init into media/ and inject a document-level <BaseURL>media/</BaseURL>.
        val media = File(d, "media").apply { mkdirs() }
        d.listFiles { f -> f.name.endsWith(".m4s") }?.forEach { it.renameTo(File(media, it.name)) }
        val mpd = File(d, "manifest.mpd")
        mpd.writeText(mpd.readText().replaceFirst(Regex("(<MPD[^>]*>)"), "$1\n  <BaseURL>media/</BaseURL>"))
        val h = runDash("d7", "d7/manifest.mpd")
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 2.0)
    }

    // ---- D8: multiple Periods (parser concatenates period entries) --------------------------
    @Test fun d8_multiPeriod() {
        requireFfmpeg()
        val d = caseDir("d8")
        genDash("d8", "-use_template", "1", "-use_timeline", "0")
        val mpd = File(d, "manifest.mpd")
        val text = mpd.readText()
        val periodRegex = Regex("(?s)<Period\\b.*?</Period>")
        val period = periodRegex.find(text)?.value ?: error("no Period in MPD")
        // Give the period an explicit duration and duplicate it, so the manifest advertises two
        // full-length periods (rather than the parser splitting mediaPresentationDuration between
        // them). The app models each Period as its own download/output file, so a per-period entry
        // must expand to the same segment set as the single-period case.
        val withDuration = period.replaceFirst("<Period", "<Period duration=\"PT3S\"")
        mpd.writeText(text.replaceFirst(period, withDuration + "\n" + withDuration))
        val entries = mpd.inputStream().use { parseMpdManifest(it, url("d8/manifest.mpd")) }
        assertTrue("expected >= 2 period entries, got ${entries.size}", entries.size >= 2)
        // Each entry is independently downloadable+muxable; verify the first.
        val h = runDash("d8", "d8/manifest.mpd")
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 2.0)
    }

    // ---- D9: HEVC video passthrough ---------------------------------------------------------
    @Test fun d9_hevc() {
        requireFfmpeg()
        Assume.assumeTrue("libx265 not available", hasEncoder("libx265"))
        val d = caseDir("d9")
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "2",
            "-map", "0:v", "-map", "1:a", "-c:v", "libx265", "-tag:v", "hvc1", "-pix_fmt", "yuv420p",
            "-g", "25", "-c:a", "aac", "-ar", "44100", "-ac", "2",
            "-f", "dash", "-seg_duration", "1", "-use_template", "1", "-use_timeline", "0",
            File(d, "manifest.mpd").path
        )
        val h = runDash("d9", "d9/manifest.mpd")
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 1.0)
    }

    // ---- D10: DASH-WebM (VP9 + Opus) -> MKV output ------------------------------------------
    @Test fun d10_webmVp9OpusToMkv() {
        requireFfmpeg()
        Assume.assumeTrue("vp9/opus not available", hasEncoder("libvpx-vp9") && hasEncoder("libopus"))
        val d = caseDir("d10")
        val ok = ffmpegSoft(
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "2",
            "-map", "0:v", "-map", "1:a", "-c:v", "libvpx-vp9", "-b:v", "300k", "-g", "25",
            "-c:a", "libopus", "-ar", "48000", "-ac", "2",
            "-f", "dash", "-seg_duration", "1", "-dash_segment_type", "webm",
            "-use_template", "1", "-use_timeline", "0", File(d, "manifest.mpd").path
        )
        Assume.assumeTrue("webm DASH packaging failed on this ffmpeg", ok && File(d, "manifest.mpd").exists())
        val h = runDash("d10", "d10/manifest.mpd", expectMkv = true)
        assertTrue("expected .mkv output", h.finalFile!!.name.endsWith(".mkv"))
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 1.0)
    }

    // ---- D12: encrypted (ContentProtection) MPD must be rejected ----------------------------
    @Test fun d12_contentProtectionRejected() {
        val d = caseDir("d12")
        File(d, "manifest.mpd").writeText(
            """
            <?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT2S">
              <Period>
                <AdaptationSet mimeType="video/mp4">
                  <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>
                  <Representation id="0" bandwidth="100000" codecs="avc1.4d401f" width="320" height="240">
                    <BaseURL>v.mp4</BaseURL>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
            """.trimIndent()
        )
        val ex = runCatching {
            File(d, "manifest.mpd").inputStream().use { parseMpdManifest(it, url("d12/manifest.mpd")) }
        }.exceptionOrNull()
        assertTrue("expected an encrypted-manifest rejection, got $ex", ex != null)
    }
}
