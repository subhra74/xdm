import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Test
import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.web.http.HttpDownloaderTask
import xdm.core.downloaders.web.streaming.downloader.hls.HlsDownloaderTask
import xdm.core.downloaders.web.streaming.manifest.hls.HlsParser
import xdm.core.downloaders.web.streaming.manifest.hls.singleFileHttpUrl
import java.io.File
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end HLS VOD tests: ffmpeg builds real segments, a Range-capable file server serves them,
 * the real [HlsDownloaderTask] + TransmuxingMuxer download and mux, and ffprobe verifies the
 * committed output. See the case matrix in [StreamingE2EBase].
 *
 * Cases: H1 plain TS · H2 ABR master · H3 alt-audio · H4 fMP4/CMAF · H5 single-file byte-range ·
 * H6 independent segments · H7 AES-128 explicit IV · H8 AES-128 sequence-derived IV ·
 * H9 AES-128 key rotation · H10 discontinuity · H11 HEVC-in-TS · H12 AC-3 audio · H13 audio-only ·
 * H14 SAMPLE-AES rejection (fixture-free).
 */
class TestHlsE2E : StreamingE2EBase() {

    private var nextId = 1000L

    /** Build + run an HLS task against a media (and optional audio) playlist, await success. */
    private fun runHls(
        caseName: String,
        mediaUrl: String,
        audioUrl: String? = null,
        independent: Boolean = false,
        audioOnly: Boolean = false,
    ): StreamingTestHost {
        val h = host(caseName)
        val info = HlsDownloadTaskInfo(
            id = nextId++, fileName = "$caseName.mp4",
            tempDir = File(root, "$caseName/tmp").absolutePath, respectFileName = false,
            cookie = null, headers = null, origin = null, autoCategorize = false,
            defaultDownloadFolder = File(root, "$caseName/out").absolutePath,
            userSelectedDownloadFolder = null, maxPiece = 0, authInfo = null,
            url = mediaUrl, audioUrl = audioUrl, audioOnly = audioOnly, independent = independent,
        )
        HlsDownloaderTask(info, newClient(), muxer(), h, root.absolutePath, config()).start()
        awaitSuccess(h)
        return h
    }

    /** Generate a muxed H.264+AAC TS rendition (index.m3u8 + seg*.ts) into [caseName]/. */
    private fun genMuxedTs(caseName: String, dur: Int = 3, vararg extra: String) {
        val d = caseDir(caseName)
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
            "-t", "$dur", "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-g", "25",
            "-c:a", "aac", "-ar", "44100", "-ac", "2", *extra,
            "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
            "-hls_segment_filename", File(d, "seg%d.ts").path, File(d, "index.m3u8").path
        )
    }

    private fun segFiles(dir: File, regex: Regex): List<File> =
        dir.listFiles { f -> f.name.matches(regex) }?.sortedBy { it.name } ?: emptyList()

    // ---- H1: plain muxed TS -----------------------------------------------------------------
    @Test fun h1_plainTsMuxed() {
        requireFfmpeg()
        genMuxedTs("h1")
        val h = runHls("h1", url("h1/index.m3u8"))
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 2.0)
    }

    // ---- H2: master / ABR playlist -> pick highest-bandwidth variant ------------------------
    @Test fun h2_masterAbrPicksVariant() {
        requireFfmpeg()
        val d = caseDir("h2")
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "2",
            "-filter_complex", "[0:v]split=2[v1][v2];[v1]scale=320:240,format=yuv420p[a];[v2]scale=160:120,format=yuv420p[b]",
            "-map", "[a]", "-map", "1:a", "-map", "[b]", "-map", "1:a",
            "-c:v", "libx264", "-profile:v", "baseline", "-g", "25", "-c:a", "aac", "-ar", "44100",
            "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
            "-var_stream_map", "v:0,a:0,name:hi v:1,a:1,name:lo", "-master_pl_name", "master.m3u8",
            "-hls_segment_filename", File(d, "stream_%v/seg%d.ts").path, File(d, "stream_%v/index.m3u8").path
        )
        val masterUrl = url("h2/master.m3u8")
        val variants = HlsParser.parseMasterPlaylist(File(d, "master.m3u8").readLines().iterator(), masterUrl).getOrThrow()
        val best = variants.maxByOrNull { it.attributes["BANDWIDTH"]?.toLongOrNull() ?: 0 }!!
        val h = runHls("h2", best.videoPlaylist.toString())
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 1.0)
    }

    // ---- H3: separate alternate-audio + video -----------------------------------------------
    @Test fun h3_alternateAudio() {
        requireFfmpeg()
        val d = caseDir("h3")
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "2",
            "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p",
            "-g", "25", "-c:a", "aac", "-ar", "44100",
            "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
            "-var_stream_map", "v:0,agroup:aud a:0,agroup:aud,default:yes", "-master_pl_name", "master.m3u8",
            "-hls_segment_filename", File(d, "stream_%v/seg%d.ts").path, File(d, "stream_%v/index.m3u8").path
        )
        val masterUrl = url("h3/master.m3u8")
        val entry = HlsParser.parseMasterPlaylist(File(d, "master.m3u8").readLines().iterator(), masterUrl)
            .getOrThrow().first { it.videoPlaylist != null && it.audioPlaylist != null }
        val h = runHls("h3", entry.videoPlaylist.toString(), audioUrl = entry.audioPlaylist.toString())
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 1.0)
    }

    // ---- H4: fragmented MP4 / CMAF (EXT-X-MAP init + .m4s) -----------------------------------
    @Test fun h4_fragmentedMp4() {
        requireFfmpeg()
        val d = caseDir("h4")
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "3",
            "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p", "-g", "25",
            "-c:a", "aac", "-ar", "44100", "-ac", "2",
            "-f", "hls", "-hls_time", "1", "-hls_segment_type", "fmp4", "-hls_list_size", "0",
            "-hls_fmp4_init_filename", "init.mp4",
            "-hls_segment_filename", File(d, "seg%d.m4s").path, File(d, "index.m3u8").path
        )
        val h = runHls("h4", url("h4/index.m3u8"))
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 2.0)
    }

    // ---- H5: single-file segments addressed by EXT-X-BYTERANGE -------------------------------
    @Test fun h5_singleFileByteRange() {
        requireFfmpeg()
        val d = caseDir("h5")
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "3",
            "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-g", "25",
            "-c:a", "aac", "-ar", "44100", "-ac", "2",
            "-f", "hls", "-hls_time", "1", "-hls_flags", "single_file",
            "-hls_segment_type", "mpegts", "-hls_list_size", "0",
            "-hls_segment_filename", File(d, "media.ts").path, File(d, "index.m3u8").path
        )
        Assume.assumeTrue("expected single-file byte-range playlist",
            File(d, "index.m3u8").readText().contains("EXT-X-BYTERANGE"))
        val h = runHls("h5", url("h5/index.m3u8"))
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 2.0)
    }

    // ---- H5b: single-file byte-range detected + fetched as a plain HTTP download --------------
    @Test fun h5b_singleFileDownloadsAsPlainHttp() {
        requireFfmpeg()
        val d = caseDir("h5b")
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "3",
            "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-g", "25",
            "-c:a", "aac", "-ar", "44100", "-ac", "2",
            "-f", "hls", "-hls_time", "1", "-hls_flags", "single_file",
            "-hls_segment_type", "mpegts", "-hls_list_size", "0",
            "-hls_segment_filename", File(d, "media.ts").path, File(d, "index.m3u8").path
        )
        // The playlist must be recognised as a single-file byte-range playlist, yielding the media URL.
        val playlist = HlsParser.parseMediaSegments(File(d, "index.m3u8").readLines().iterator(), url("h5b/index.m3u8"))
            .getOrThrow()
        val fileUrl = playlist.singleFileHttpUrl()
        assertEquals(url("h5b/media.ts"), fileUrl)
        // No mux -> the native MPEG-TS container must be preserved (.ts, not .mp4).
        assertEquals("ts", xdm.core.util.plainDownloadExt(null, fileUrl))

        // Route it exactly as VideoHelper now does: a plain whole-file HTTP download (no mux).
        val h = host("h5b")
        val info = HttpDownloadTaskInfo(
            id = nextId++, url = fileUrl!!, fileName = "h5b.ts", respectFileName = false,
            cookie = null, headers = null, origin = null, autoCategorize = false,
            defaultDownloadFolder = File(root, "h5b/out").absolutePath, userSelectedDownloadFolder = null,
            maxPiece = 4, authInfo = null, knownFileSize = null,
        )
        HttpDownloaderTask(info, h, newClient(), root.absolutePath, config()).start()
        awaitSuccess(h)
        // The whole single file is a complete, playable A+V program.
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 2.0)
    }

    // ---- H6: EXT-X-INDEPENDENT-SEGMENTS ------------------------------------------------------
    @Test fun h6_independentSegments() {
        requireFfmpeg()
        genMuxedTs("h6")
        val pl = File(caseDir("h6"), "index.m3u8")
        pl.writeText(pl.readText().replaceFirst("#EXTM3U", "#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS"))
        val h = runHls("h6", url("h6/index.m3u8"))
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 2.0)
    }

    // ---- AES-128 helpers --------------------------------------------------------------------
    private fun randomKey(): ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }

    private fun ivFromSeq(seq: Long): ByteArray {
        val b = ByteArray(16); var v = seq
        for (i in 15 downTo 0) { b[i] = (v and 0xff).toByte(); v = v ushr 8 }
        return b
    }

    private fun ivHex(seq: Long): String = "0x" + ivFromSeq(seq).joinToString("") { "%02x".format(it) }

    private fun aesEncrypt(src: File, dst: File, key: ByteArray, iv: ByteArray) {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        dst.outputStream().use { out -> CipherOutputStream(out, c).use { cos -> src.inputStream().use { it.copyTo(cos) } } }
    }

    /**
     * Encrypt [caseName]'s plain segments (video-only H.264 TS) with AES-128 and write a playlist.
     * [keyForSeg] picks the (uriName, keyBytes) per segment; [writeIv] toggles whether an explicit
     * IV attribute is emitted (false => the parser derives the IV from the media sequence).
     */
    private fun buildAesCase(
        caseName: String,
        writeIv: Boolean,
        keyForSeg: (Int) -> Pair<String, ByteArray>,
    ): String {
        val d = caseDir(caseName)
        // Plain source segments (video-only keeps decryption round-trip assertions simple).
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25", "-t", "3", "-an",
            "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-g", "25",
            "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
            "-hls_segment_filename", File(d, "plain%d.ts").path, File(d, "plain.m3u8").path
        )
        val plain = segFiles(d, Regex("plain\\d+\\.ts"))
        val writtenKeys = HashMap<String, ByteArray>()
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n")
        var prevUri: String? = null
        plain.forEachIndexed { i, seg ->
            val (uri, key) = keyForSeg(i)
            writtenKeys[uri] = key
            val enc = File(d, "enc$i.ts")
            aesEncrypt(seg, enc, key, ivFromSeq(i.toLong()))
            if (uri != prevUri || (writeIv)) {
                sb.append("#EXT-X-KEY:METHOD=AES-128,URI=\"$uri\"")
                if (writeIv) sb.append(",IV=${ivHex(i.toLong())}")
                sb.append('\n')
                prevUri = uri
            }
            sb.append("#EXTINF:1.000000,\nenc$i.ts\n")
        }
        sb.append("#EXT-X-ENDLIST\n")
        File(d, "index.m3u8").writeText(sb.toString())
        // Serve each key file at its (relative) URI under the case dir.
        writtenKeys.forEach { (uri, key) -> File(d, uri).writeBytes(key) }
        return url("$caseName/index.m3u8")
    }

    // ---- H7: AES-128 with explicit IV -------------------------------------------------------
    @Test fun h7_aes128ExplicitIv() {
        requireFfmpeg()
        val key = randomKey()
        val u = buildAesCase("h7", writeIv = true) { "enc.key" to key }
        val h = runHls("h7", u)
        verifyOutput(h, expectVideo = true, expectAudio = false, minDuration = 2.0)
    }

    // ---- H8: AES-128 with sequence-derived IV (no IV attribute) ------------------------------
    @Test fun h8_aes128SequenceIv() {
        requireFfmpeg()
        val key = randomKey()
        val u = buildAesCase("h8", writeIv = false) { "enc.key" to key }
        val h = runHls("h8", u)
        verifyOutput(h, expectVideo = true, expectAudio = false, minDuration = 2.0)
    }

    // ---- H9: AES-128 key rotation (alternating per-segment keys) -----------------------------
    @Test fun h9_aes128KeyRotation() {
        requireFfmpeg()
        val k0 = randomKey(); val k1 = randomKey()
        val u = buildAesCase("h9", writeIv = true) { i -> if (i % 2 == 0) "k0.key" to k0 else "k1.key" to k1 }
        val h = runHls("h9", u)
        verifyOutput(h, expectVideo = true, expectAudio = false, minDuration = 2.0)
    }

    // ---- H10: EXT-X-DISCONTINUITY (timeline reset across two spliced clips) ------------------
    @Test fun h10_discontinuity() {
        requireFfmpeg()
        val d = caseDir("h10")
        // Two independently-encoded clips; each restarts its TS timeline at ~0.
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "2",
            "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-g", "25",
            "-c:a", "aac", "-ar", "44100", "-ac", "2",
            "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
            "-hls_segment_filename", File(d, "a%d.ts").path, File(d, "a.m3u8").path
        )
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=44100", "-t", "2",
            "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-g", "25",
            "-c:a", "aac", "-ar", "44100", "-ac", "2",
            "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
            "-hls_segment_filename", File(d, "b%d.ts").path, File(d, "b.m3u8").path
        )
        val a = segFiles(d, Regex("a\\d+\\.ts")); val b = segFiles(d, Regex("b\\d+\\.ts"))
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n")
        a.forEach { sb.append("#EXTINF:1.000000,\n${it.name}\n") }
        sb.append("#EXT-X-DISCONTINUITY\n")
        b.forEach { sb.append("#EXTINF:1.000000,\n${it.name}\n") }
        sb.append("#EXT-X-ENDLIST\n")
        File(d, "index.m3u8").writeText(sb.toString())
        val h = runHls("h10", url("h10/index.m3u8"))
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 3.0)
    }

    // ---- H11: HEVC / H.265 in TS ------------------------------------------------------------
    @Test fun h11_hevcInTs() {
        requireFfmpeg()
        Assume.assumeTrue("libx265 not available", hasEncoder("libx265"))
        val d = caseDir("h11")
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "2",
            "-c:v", "libx265", "-tag:v", "hvc1", "-pix_fmt", "yuv420p", "-g", "25",
            "-c:a", "aac", "-ar", "44100", "-ac", "2",
            "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
            "-hls_segment_filename", File(d, "seg%d.ts").path, File(d, "index.m3u8").path
        )
        val h = runHls("h11", url("h11/index.m3u8"))
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 1.0)
        probeCodecs(h.finalFile!!.absolutePath)?.let {
            Assume.assumeTrue("expected hevc video, got $it", it.contains("hevc"))
        }
    }

    // ---- H12: AC-3 audio --------------------------------------------------------------------
    @Test fun h12_ac3Audio() {
        requireFfmpeg()
        Assume.assumeTrue("ac3 encoder not available", hasEncoder("ac3"))
        val d = caseDir("h12")
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "2",
            "-c:v", "libx264", "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-g", "25",
            "-c:a", "ac3", "-ar", "48000", "-ac", "2",
            "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
            "-hls_segment_filename", File(d, "seg%d.ts").path, File(d, "index.m3u8").path
        )
        val h = runHls("h12", url("h12/index.m3u8"))
        verifyOutput(h, expectVideo = true, expectAudio = true, minDuration = 1.0)
    }

    // ---- H13: audio-only HLS ----------------------------------------------------------------
    @Test fun h13_audioOnly() {
        requireFfmpeg()
        val d = caseDir("h13")
        ffmpeg(
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "3", "-vn",
            "-c:a", "aac", "-ar", "44100", "-ac", "2",
            "-f", "hls", "-hls_time", "1", "-hls_segment_type", "mpegts", "-hls_list_size", "0",
            "-hls_segment_filename", File(d, "seg%d.ts").path, File(d, "index.m3u8").path
        )
        val h = runHls("h13", url("h13/index.m3u8"), audioOnly = true)
        verifyOutput(h, expectVideo = false, expectAudio = true, minDuration = 2.0)
    }

    // ---- H14: SAMPLE-AES must be rejected (skipped, no fixture) ------------------------------
    @Test fun h14_sampleAesRejected() {
        val d = caseDir("h14")
        File(d, "index.m3u8").writeText(
            """
            #EXTM3U
            #EXT-X-VERSION:5
            #EXT-X-TARGETDURATION:1
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="enc.key"
            #EXTINF:1.0,
            seg0.ts
            #EXT-X-ENDLIST
            """.trimIndent()
        )
        File(d, "seg0.ts").writeBytes(ByteArray(1024))
        File(d, "enc.key").writeBytes(ByteArray(16))
        val h = host("h14")
        val info = HlsDownloadTaskInfo(
            id = nextId++, fileName = "h14.mp4", tempDir = File(root, "h14/tmp").absolutePath,
            respectFileName = false, cookie = null, headers = null, origin = null, autoCategorize = false,
            defaultDownloadFolder = File(root, "h14/out").absolutePath, userSelectedDownloadFolder = null,
            maxPiece = 0, authInfo = null, url = url("h14/index.m3u8"), audioUrl = null,
            audioOnly = false, independent = false,
        )
        HlsDownloaderTask(info, newClient(), muxer(), h, root.absolutePath, config()).start()
        val err = awaitFailure(h)
        // The manifest never parses, so init fails -> InvalidResponse (not a mux error).
        org.junit.Assert.assertEquals(DownloadError.InvalidResponse, err)
    }
}
