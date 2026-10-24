// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.media.subtitle

/**
 * Turns WebVTT (one file, or the segments of an HLS subtitle playlist) into SubRip (`.srt`), the
 * sidecar format every desktop player loads next to a video.
 *
 * Timing (HLS, as hls.js and the spec do it): `X-TIMESTAMP-MAP=MPEGTS:<90 kHz>,LOCAL:<cue time>` ties a
 * segment's cue times to the media PTS clock; a segment without it maps cue time 0 to MPEGTS 0. The cues
 * are moved onto the muxed file's timeline, which starts at [timelineStartSec] (the first presented PTS
 * the muxer used), and a 33-bit PTS wrap between the two is undone. When that start is unknown, the
 * first segment map's MPEGTS stands in for it (the stream start on practically every server), and
 * segments without a map keep their times. Other sources (DASH, plain files) keep their times as they
 * are: they are relative to the presentation start, which is where the output starts.
 *
 * Cues repeated across segment boundaries are merged; WebVTT styling other than `<b>`, `<i>` and `<u>`
 * (which SRT players understand) is dropped.
 */
object WebVtt {
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    /** The SRT text for [segments] in playback order, or "" when they hold no cue. */
    fun toSrt(segments: List<String>, timelineStartSec: Double? = null, hls: Boolean = true): String =
        formatSrt(merge(parseSegments(segments, timelineStartSec, hls)))

    fun parseSegments(segments: List<String>, timelineStartSec: Double?, hls: Boolean = true): List<Cue> {
        val parsed = segments.map { parse(it) }
        val originTicks = timelineStartSec?.let { Math.round(it * 90_000) }
            ?: if (hls) parsed.firstNotNullOfOrNull { it.map?.mpegTs } else null
        val cues = ArrayList<Cue>()
        for (seg in parsed) {
            val map = when {
                !hls || originTicks == null -> null
                seg.map != null -> seg.map
                timelineStartSec != null -> ZERO_MAP
                else -> null
            }
            for (c in seg.cues) {
                val start = map?.let { toOutput(c.startMs, it, originTicks!!) } ?: c.startMs
                val end = map?.let { toOutput(c.endMs, it, originTicks!!) } ?: c.endMs
                if (end <= 0) continue
                cues.add(Cue(start.coerceAtLeast(0), end, c.text))
            }
        }
        return cues
    }

    /** A cue time on the output timeline: its PTS ([map]) less the output's start, unwrapped. */
    private fun toOutput(cueMs: Long, map: TimestampMap, originTicks: Long): Long =
        Math.round(wrap33((cueMs - map.localMs) * 90 + map.mpegTs - originTicks) / 90.0)

    /** Sorts by start, drops exact repeats, and joins a cue split across a segment boundary. */
    fun merge(cues: List<Cue>): List<Cue> {
        val out = ArrayList<Cue>()
        for (c in cues.distinct().sortedWith(compareBy({ it.startMs }, { it.endMs }))) {
            val i = (out.size - 1 downTo maxOf(0, out.size - JOIN_WINDOW))
                .firstOrNull { out[it].text == c.text && c.startMs <= out[it].endMs + JOIN_GAP_MS }
            if (i != null) {
                val prev = out[i]
                out[i] = prev.copy(endMs = maxOf(prev.endMs, c.endMs))
            } else {
                out.add(c)
            }
        }
        return out
    }

    fun formatSrt(cues: List<Cue>): String {
        val sb = StringBuilder()
        for ((i, c) in cues.withIndex()) {
            sb.append(i + 1).append("\r\n")
            sb.append(srtTime(c.startMs)).append(" --> ").append(srtTime(c.endMs)).append("\r\n")
            sb.append(c.text.replace("\n", "\r\n")).append("\r\n\r\n")
        }
        return sb.toString()
    }

    internal class TimestampMap(val mpegTs: Long, val localMs: Long)

    /** What a segment without `X-TIMESTAMP-MAP` means in HLS: cue time 0 is PTS 0. */
    private val ZERO_MAP = TimestampMap(0, 0)

    internal class Segment(val cues: List<Cue>, val map: TimestampMap?)

    internal fun parse(text: String): Segment {
        val lines = text.removePrefix("﻿").split("\r\n", "\n", "\r")
        val cues = ArrayList<Cue>()
        var map: TimestampMap? = null
        var i = 0
        while (i < lines.size) {
            // A block runs to the next blank line.
            if (lines[i].isBlank()) { i++; continue }
            val start = i
            while (i < lines.size && lines[i].isNotBlank()) i++
            val block = lines.subList(start, i)
            val first = block[0].trim()
            if (start == 0 || first.startsWith("WEBVTT")) {
                // Header: may hold the timestamp map; never a cue.
                block.firstNotNullOfOrNull { parseTimestampMap(it) }?.let { map = it }
                if (block.none { it.contains("-->") }) continue
            }
            if (first.startsWith("NOTE") || first == "STYLE" || first == "REGION") continue
            val timing = block.indexOfFirst { it.contains("-->") }
            if (timing < 0) continue
            val times = parseTiming(block[timing]) ?: continue
            val body = block.subList(timing + 1, block.size).joinToString("\n") { cleanText(it) }.trim()
            if (body.isEmpty() || times.second <= times.first) continue
            cues.add(Cue(times.first, times.second, body))
        }
        return Segment(cues, map)
    }

    private fun parseTimestampMap(line: String): TimestampMap? {
        val t = line.trim()
        if (!t.startsWith("X-TIMESTAMP-MAP")) return null
        var mpegTs: Long? = null
        var local: Long? = null
        for (part in t.substringAfter('=').split(',')) {
            val key = part.substringBefore(':').trim()
            val value = part.substringAfter(':', "").trim()
            when (key) {
                "MPEGTS" -> mpegTs = value.toLongOrNull()
                "LOCAL" -> local = parseTime(value)
            }
        }
        return if (mpegTs != null) TimestampMap(mpegTs, local ?: 0) else null
    }

    private fun parseTiming(line: String): Pair<Long, Long>? {
        val parts = line.split("-->")
        if (parts.size != 2) return null
        val start = parseTime(parts[0].trim()) ?: return null
        // Cue settings (align:, line:, position:) follow the end time.
        val end = parseTime(parts[1].trim().substringBefore(' ').substringBefore('\t')) ?: return null
        return start to end
    }

    /** `hh:mm:ss.ttt` or `mm:ss.ttt` (a comma is accepted too) in milliseconds. */
    internal fun parseTime(s: String): Long? {
        val fields = s.replace(',', '.').split(':')
        if (fields.size !in 2..3) return null
        val h = if (fields.size == 3) fields[0].toLongOrNull() ?: return null else 0L
        val m = fields[fields.size - 2].toLongOrNull() ?: return null
        val sec = fields.last()
        val whole = sec.substringBefore('.').toLongOrNull() ?: return null
        val frac = sec.substringAfter('.', "").padEnd(3, '0').take(3).toLongOrNull() ?: return null
        return ((h * 60 + m) * 60 + whole) * 1000 + frac
    }

    private val TAG = Regex("</?([a-zA-Z]+)[^>]*>|<[0-9:.]+>")

    /** Keeps `<b>`, `<i>`, `<u>`; drops voice, class, ruby and karaoke timestamp tags; decodes entities. */
    private fun cleanText(line: String): String =
        TAG.replace(line) { m ->
            val name = m.groupValues[1].lowercase()
            if (name == "b" || name == "i" || name == "u") {
                if (m.value.startsWith("</")) "</$name>" else "<$name>"
            } else ""
        }
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .replace("&lrm;", "‎").replace("&rlm;", "‏").replace("&amp;", "&")
            .trimEnd()

    private fun srtTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms % 1000)
    }

    /** A 33-bit PTS difference brought into the signed range, so a wrap between the two values is harmless. */
    private fun wrap33(d: Long): Long {
        val m = 1L shl 33
        var v = d % m
        if (v >= m / 2) v -= m
        if (v < -m / 2) v += m
        return v
    }

    /** Same text starting within this of the previous cue's end is the same cue cut by a segment boundary. */
    private const val JOIN_GAP_MS = 40L

    /** How far back (in cues) a split cue's first half is looked for. */
    private const val JOIN_WINDOW = 4
}
