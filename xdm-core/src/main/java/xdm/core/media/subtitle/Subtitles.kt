// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.subtitle

/**
 * Stand-alone subtitle files (a captured `.vtt`, `.ttml`, `.srt`): what format one is, what it holds,
 * and its SRT form. Streams' subtitle tracks go through [WebVtt] directly.
 */
object Subtitles {
    enum class Format(val ext: String, val mimeType: String) {
        VTT("vtt", "text/vtt"), TTML("ttml", "application/ttml+xml"), SRT("srt", "application/x-subrip");

        /** Whether [toSrt] has anything to do for this format. */
        val convertible: Boolean get() = this != SRT
    }

    /** What a subtitle file holds, for the captured list and its filters. */
    data class Summary(
        val format: Format,
        val cues: Int,
        /** End of the last cue, milliseconds: about the length of the video it belongs to. */
        val lastEndMs: Long,
        val language: String?,
        /** A WebVTT segment of an HLS subtitle playlist (`X-TIMESTAMP-MAP`), not a whole file. */
        val hlsSegment: Boolean,
        /** Every cue is an image reference: a seek-bar thumbnail track, not text. */
        val imageTrack: Boolean,
    )

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val SRT_TIMING = Regex("""\d{1,2}:\d{2}:\d{2},\d{1,3}\s*-->""")
    private val IMAGE_REF = Regex("""(?i)(\.(jpe?g|png|webp|gif|bmp)(\?|#|$))|#xywh=""")
    private val VTT_LANGUAGE = Regex("""(?im)^\s*Language\s*:\s*([A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})*)\s*$""")

    /** [bytes] as text: UTF-8 unless a UTF-16 BOM says otherwise; a leading BOM is dropped. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return String(bytes, Charsets.UTF_8).removePrefix("﻿")
    }

    /** The format of [text], from its content alone; null when it is none of them. */
    fun detect(text: String): Format? {
        val head = text.removePrefix("﻿").trimStart()
        return when {
            head.startsWith("WEBVTT") -> Format.VTT
            head.startsWith("<") && Regex("""<(\w+:)?tt[\s>]""").containsMatchIn(head.take(4096)) -> Format.TTML
            SRT_TIMING.containsMatchIn(head.take(4096)) -> Format.SRT
            else -> null
        }
    }

    /** The cues of [text] in [format], on the file's own timeline. */
    fun cues(text: String, format: Format): List<WebVtt.Cue> = when (format) {
        // SRT is close enough to WebVTT for the cue reader: index lines are skipped, commas accepted.
        Format.VTT, Format.SRT -> WebVtt.parseSegments(listOf(text), null, hls = false)
        Format.TTML -> Ttml.parse(text)
    }

    /** What [text] holds, or null when it is not a subtitle file. */
    fun summarize(text: String): Summary? {
        val format = detect(text) ?: return null
        val cues = cues(text, format)
        val language = when (format) {
            Format.VTT -> VTT_LANGUAGE.find(text.take(2048).substringBefore("-->"))?.groupValues?.get(1)
            Format.TTML -> Ttml.language(text)
            Format.SRT -> null
        }
        val header = text.take(1024).substringBefore("-->")
        return Summary(
            format = format,
            cues = cues.size,
            lastEndMs = cues.maxOfOrNull { it.endMs } ?: 0L,
            language = language,
            hlsSegment = format == Format.VTT && header.contains("X-TIMESTAMP-MAP"),
            imageTrack = cues.isNotEmpty() && cues.all { c -> c.text.lines().all { IMAGE_REF.containsMatchIn(it.trim()) } },
        )
    }

    /**
     * [text] as SRT, or null when it is already SRT or not a subtitle file. "" when it holds no
     * cue. Cues repeated back to back are merged as for stream subtitles.
     */
    fun toSrt(text: String): String? {
        val format = detect(text)?.takeIf { it.convertible } ?: return null
        return WebVtt.formatSrt(WebVtt.merge(cues(text, format)))
    }

    /** [srt] as UTF-8 with a BOM, so Windows players don't read a non-Latin subtitle as the ANSI code page. */
    fun srtBytes(srt: String): ByteArray = UTF8_BOM + srt.toByteArray(Charsets.UTF_8)

    /** [fileName] with its extension replaced by `.srt` (`movie.en.vtt` -> `movie.en.srt`). */
    fun srtFileName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        return "$base.srt"
    }
}
