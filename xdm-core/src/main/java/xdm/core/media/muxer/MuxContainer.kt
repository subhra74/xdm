// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer

/**
 * Which container a muxed download (HLS, DASH, a video + audio pair) is written in: Matroska
 * (`mkv`, `mka` for audio) whenever WebM or Matroska is involved or a track can't go into MP4,
 * otherwise MP4 (`mp4`, or the `m4a`/`m4v` the caller asked for). A muxed output is never WebM: a
 * WebM merge is written as `mkv`. Plain downloads of a `.webm` file aren't muxed and keep their name.
 *
 * The choice is made twice: up front from what the manifest says ([forStreams]), so the name shown
 * in the dialog is right in most cases, and again when muxing from the segments' bytes ([plan]),
 * which wins; the published name then takes the extension written ([withExtension]).
 */
object MuxContainer {
    const val MP4 = "mp4"
    const val MKV = "mkv"

    /** MP4-family extensions a caller may ask for and keep. */
    private val MP4_FAMILY = setOf("mp4", "m4a", "m4v")

    /**
     * Codecs (manifest `CODECS` / `codecs` values, RFC 6381 style) MP4 has no sample entry for that
     * the MP4 writer can write: their streams go to Matroska.
     */
    private val MATROSKA_ONLY_CODECS = listOf("vorbis", "theora", "vp8", "vp08")

    /**
     * The container for separate streams of these MIME types and codecs, before anything is
     * downloaded: MP4 when every MIME type is an MP4 one and every codec fits MP4, else Matroska.
     */
    fun forStreams(vararg mimeTypes: String?, codecs: List<String?> = emptyList()): String {
        val allMp4 = mimeTypes.all { it != null && it.contains("mp4", ignoreCase = true) }
        val codecsFit = codecs.filterNotNull().all { mp4CanCarry(it) }
        return if (allMp4 && codecsFit) MP4 else MKV
    }

    /** False for a codec the MP4 writer has no sample entry for (see [MATROSKA_ONLY_CODECS]). */
    fun mp4CanCarry(codec: String): Boolean {
        val c = codec.trim().lowercase()
        return c.split(',').map { it.trim() }.none { one -> MATROSKA_ONLY_CODECS.any { one == it || one.startsWith("$it.") } }
    }

    /**
     * The extension a mux writes, from the [requested] one (the output name's, or the caller's pick)
     * and the inputs. [matroskaInput]: a segment list is WebM/Matroska. [mp4Incapable]: a track can't
     * go into MP4. Matroska is never turned into MP4.
     */
    fun plan(requested: String?, matroskaInput: Boolean, mp4Incapable: Boolean = false): String {
        val r = requested?.trim()?.trimStart('.')?.lowercase().orEmpty()
        return when {
            r == "mkv" || r == "mka" -> r
            r == "webm" -> MKV
            r == "weba" -> "mka"
            matroskaInput || mp4Incapable -> if (r == "m4a") "mka" else MKV
            r in MP4_FAMILY -> r
            else -> MP4
        }
    }

    /** True for an extension [plan] writes as Matroska. */
    fun isMatroska(ext: String): Boolean = ext.lowercase().trimStart('.') in setOf("mkv", "mka", "webm", "weba")

    /**
     * [name] with the extension [container]: a media extension is replaced, any other name (no
     * extension, or a dot that isn't one) gets it added. Unchanged when it already has it.
     */
    fun withExtension(name: String, container: String): String {
        val ext = container.trimStart('.')
        val dot = name.lastIndexOf('.')
        val current = if (dot > 0) name.substring(dot + 1) else ""
        return when {
            current.equals(ext, ignoreCase = true) -> name
            current.lowercase() in MEDIA_EXTENSIONS -> name.substring(0, dot + 1) + ext
            else -> "$name.$ext"
        }
    }

    private val MEDIA_EXTENSIONS = setOf(
        "mp4", "m4a", "m4v", "mov", "mkv", "mka", "webm", "weba", "ts", "m2ts", "mts", "aac", "ac3", "ec3", "mp3",
        "flv", "3gp", "ogg", "opus",
    )
}
