// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.downloaders.web.streaming.downloader

import xdm.core.media.muxer.impl.AudioFrames
import xdm.core.media.muxer.impl.SegmentProbe
import xdm.core.media.muxer.impl.SegmentProbe.Container
import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.iso.Mp4Demuxer
import xdm.core.util.Logger
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.ceil

/**
 * The last resort when downloaded segments can't be made into one file: the segments themselves
 * (decrypted) in a folder with HLS playlists over them, which VLC, mpv and ffmpeg play. Built in
 * [dir] inside the download's temp folder — the segments are moved, not copied — and published by
 * the host ([xdm.core.downloaders.DownloadHost.commitOutputFolder]). The playlists are written last,
 * so a folder with its [ENTRY] playlist is complete: a restart publishes it again without rebuilding.
 *
 * Each rendition gets a media playlist (`EXTINF` from the playlist's durations, measured from the
 * segments when unknown; `EXT-X-DISCONTINUITY` where its section changes; `EXT-X-MAP` for init
 * segments; `EXT-X-ENDLIST`); two renditions get a master with the audio as an `EXT-X-MEDIA` group.
 */
internal class PlaylistFolder(tempFolder: String) {

    /** A downloaded segment as the downloader knows it. */
    class Segment(val file: File, val duration: Double, val section: Int, val init: Boolean)

    val dir = File(tempFolder, "playlist")

    val isBuilt: Boolean get() = File(dir, ENTRY).isFile

    /** Whether [segments] are all something an HLS playlist can carry (TS, fMP4, packed audio). */
    fun canCarry(segments: List<Segment>): Boolean = segments.isNotEmpty() && segments.all { s ->
        when (SegmentProbe.locate(s.file.path)?.container) {
            Container.TS, Container.PACKED_AUDIO -> true
            Container.MP4 -> true
            else -> false
        }
    }

    /** Moves [video] (and [audio]) into [dir] and writes the playlists; false when it couldn't. */
    fun build(video: List<Segment>, audio: List<Segment>): Boolean = try {
        dir.mkdirs()
        val v = rendition("video", video)
        val a = if (audio.isNotEmpty()) rendition("audio", audio) else null
        if (a == null) {
            File(dir, ENTRY).writeText(v.playlist)
        } else {
            File(dir, "video.m3u8").writeText(v.playlist)
            File(dir, "audio.m3u8").writeText(a.playlist)
            val seconds = (v.seconds).coerceAtLeast(1.0)
            val bandwidth = ((v.bytes + a.bytes) * 8 / seconds).toLong().coerceAtLeast(1)
            File(dir, ENTRY).writeText(
                "#EXTM3U\n#EXT-X-VERSION:7\n" +
                    "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio.m3u8\"\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth,AUDIO=\"audio\"\nvideo.m3u8\n"
            )
        }
        Logger.info("XDM", "Playlist folder built: ${video.size} video${if (audio.isEmpty()) "" else " + ${audio.size} audio"} segments")
        true
    } catch (e: Exception) {
        Logger.error("XDM", "Playlist folder failed", e)
        false
    }

    private class Rendition(val playlist: String, val bytes: Long, val seconds: Double)

    private fun rendition(prefix: String, segments: List<Segment>): Rendition {
        val names = ArrayList<String>()
        val inits = BooleanArray(segments.size)
        for ((i, s) in segments.withIndex()) {
            val located = SegmentProbe.locate(s.file.path)
            val isInit = s.init || (located?.container == Container.MP4 && isInitSegment(s.file, located.offset))
            inits[i] = isInit
            val ext = when (located?.container) {
                Container.TS -> "ts"
                Container.MP4 -> if (isInit) "mp4" else "m4s"
                Container.PACKED_AUDIO -> s.file.name.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it in PACKED_EXTENSIONS } ?: "aac"
                else -> "bin"
            }
            val name = String.format(Locale.ROOT, "%s_%05d.%s", prefix, i, ext)
            val target = File(dir, name)
            if (!target.isFile && !s.file.renameTo(target)) {
                s.file.copyTo(target, overwrite = true)
                s.file.delete()
            }
            names.add(name)
        }
        val files = names.map { File(dir, it) }
        val durations = durations(segments, files, inits)
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n")
        sb.append("#EXT-X-TARGETDURATION:").append(ceil(durations.maxOrNull() ?: 1.0).toLong().coerceAtLeast(1)).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        var section = segments.firstOrNull()?.section
        for (i in segments.indices) {
            if (segments[i].section != section) { sb.append("#EXT-X-DISCONTINUITY\n"); section = segments[i].section }
            if (inits[i]) { sb.append("#EXT-X-MAP:URI=\"").append(names[i]).append("\"\n"); continue }
            sb.append(String.format(Locale.ROOT, "#EXTINF:%.3f,\n", durations[i])).append(names[i]).append('\n')
        }
        sb.append("#EXT-X-ENDLIST\n")
        return Rendition(sb.toString(), files.sumOf { it.length() }, durations.sum())
    }

    /** Seconds per segment: the playlist's, else measured from the segment (0 for init segments). */
    private fun durations(segments: List<Segment>, files: List<File>, inits: BooleanArray): DoubleArray {
        val out = DoubleArray(segments.size) { if (inits[it]) 0.0 else segments[it].duration }
        if (out.indices.all { inits[it] || out[it] > 0 }) return out
        val container = files.firstOrNull { !it.name.endsWith(".mp4") }?.let { SegmentProbe.locate(it.path)?.container }
        val measured = when (container) {
            Container.TS -> tsDurations(files, inits)
            Container.MP4 -> fmp4Durations(files, inits)
            Container.PACKED_AUDIO -> DoubleArray(files.size) { if (inits[it]) 0.0 else packedDuration(files[it]) }
            else -> DoubleArray(files.size)
        }
        val known = out.filter { it > 0 } + measured.filter { it > 0 }
        val fallback = if (known.isEmpty()) 6.0 else known.average()
        for (i in out.indices) if (!inits[i] && out[i] <= 0) out[i] = measured[i].takeIf { it > 0 } ?: fallback
        return out
    }

    /** From each TS segment's first timestamp to the next one's; the last gets the average. */
    private fun tsDurations(files: List<File>, inits: BooleanArray): DoubleArray {
        val first = files.map { firstPts(it) }
        val out = DoubleArray(files.size)
        for (i in files.indices) {
            if (inits[i]) continue
            val a = first[i]; val b = first.getOrNull(i + 1)
            if (a != null && b != null && b > a && b - a < 90000L * 600) out[i] = (b - a) / 90000.0
        }
        return out
    }

    private fun firstPts(f: File): Long? = RandomAccessFile(f, "r").use { raf ->
        val buf = ByteArray(188 * 2000)
        val n = raf.read(buf).coerceAtLeast(0)
        var p = buf.indexOfFirst { it.toInt() == 0x47 }.coerceAtLeast(0)
        while (p + 188 <= n) {
            if (buf[p].toInt() == 0x47 && buf[p + 1].toInt() and 0x40 != 0) {
                val afc = (buf[p + 3].toInt() shr 4) and 3
                var i = p + 4
                if (afc == 3) i += 1 + (buf[p + 4].toInt() and 0xFF)
                if (afc != 2 && i + 14 <= p + 188 && buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1 &&
                    (buf[i + 3].toInt() and 0xFF) in 0xC0..0xEF && (buf[i + 7].toInt() and 0x80) != 0) {
                    val o = i + 9
                    return@use ((buf[o].toLong() and 0x0E) shl 29) or ((buf[o + 1].toLong() and 0xFF) shl 22) or
                        ((buf[o + 2].toLong() and 0xFE) shl 14) or ((buf[o + 3].toLong() and 0xFF) shl 7) or ((buf[o + 4].toLong() and 0xFE) shr 1)
                }
            }
            p += 188
        }
        null
    }

    /** fMP4: the first track's decode time from one fragment to the next (the init and fragments read in order). */
    private fun fmp4Durations(files: List<File>, inits: BooleanArray): DoubleArray {
        val out = DoubleArray(files.size)
        val demux = Mp4Demuxer(object : SampleSink { override fun writeSampleData(data: ByteArray, offset: Int, length: Int) = 0L })
        val firstSample = IntArray(files.size)
        for ((i, f) in files.withIndex()) {
            firstSample[i] = demux.tracks.firstOrNull()?.samples?.size ?: 0
            runCatching { demux.parseSegment(f.path) }
        }
        val track = demux.tracks.firstOrNull() ?: return out
        val s = track.samples
        for (i in files.indices) {
            if (inits[i]) continue
            val from = firstSample[i]
            val to = if (i + 1 < files.size) firstSample[i + 1] else s.size
            if (to - from < 1 || track.timescale <= 0) continue
            val end = if (to < s.size) s.dts(to) else s.dts(to - 1) + (s.dts(to - 1) - s.dts(maxOf(from, to - 2))).coerceAtLeast(0)
            out[i] = (end - s.dts(from)).toDouble() / track.timescale
        }
        return out
    }

    /** Packed audio: the frames' samples over their rate. */
    private fun packedDuration(f: File): Double {
        val data = f.readBytes()
        var p = 0
        var seconds = 0.0
        while (p < data.size) {
            val frame = AudioFrames.at(data, p)
            if (frame == null) { p++; continue }
            seconds += frame.samples.toDouble() / frame.sampleRate
            p += frame.length
        }
        return seconds
    }

    private fun isInitSegment(f: File, from: Long): Boolean = RandomAccessFile(f, "r").use { raf ->
        var pos = from
        var moov = false
        val h = ByteArray(8)
        while (pos + 8 <= raf.length()) {
            raf.seek(pos); raf.readFully(h)
            val size = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
            when (String(h, 4, 4, Charsets.US_ASCII)) { "moov" -> moov = true; "moof", "mdat" -> return@use false }
            if (size < 8) break
            pos += size
        }
        moov
    }

    companion object {
        /** The playlist the folder is opened with; the host renames it after the download. */
        const val ENTRY = "index.m3u8"
        private val PACKED_EXTENSIONS = setOf("aac", "ac3", "ec3", "eac3", "mp3", "mp2")
    }
}
