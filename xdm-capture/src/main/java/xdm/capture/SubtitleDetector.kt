// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.capture

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.media.subtitle.Subtitles
import xdm.core.util.CoreUtils
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import xdm.core.util.getHeader
import java.util.Collections
import java.util.Locale

/** A subtitle file the browser loaded, offered in the captured list next to the videos. */
data class CapturedSubtitle(
    val task: HttpDownloadTaskInfo,
    val display: StreamingVideoDisplayInfo,
    val format: Subtitles.Format,
    /** The cue language from the file or its URL (`en`, `pt-BR`); null when neither says. */
    val language: String?,
)

/**
 * Turns subtitle responses ([MediaFilter.isSubtitle]) into [CapturedSubtitle]s. Each one is read
 * once (subtitles are small), and only whole text files are offered:
 *
 * - a WebVTT segment of an HLS subtitle playlist (`X-TIMESTAMP-MAP`) is not: the stream offers its
 *   subtitles itself ("CC n"). Its folder is remembered per tab, so the playlist's other segments
 *   are dropped without being fetched;
 * - a seek-bar thumbnail track (cues that are only image references) is not;
 * - nor a file with no cue, or one that is no subtitle format at all.
 *
 * The same URL in the same tab is looked at once until the tab navigates.
 */
internal class SubtitleDetector(private val env: Env) {
    interface Env {
        fun execute(task: () -> Unit)

        /** The body of [url] fetched with [msg]'s headers and cookie, at most [maxBytes] + 1 bytes; null on failure. */
        fun fetch(url: String, msg: ExtensionMessage, maxBytes: Int): ByteArray?

        fun offer(subtitle: CapturedSubtitle)
    }

    /** (tab id, folder URL) of HLS subtitle segments. */
    private val segmentFolders = lruSet<Pair<String?, String>>(256)

    /** (tab id, URL) already looked at. */
    private val seen = lruSet<Pair<String?, String>>(1024)

    /** Takes a subtitle response; [body] is its text when the sender already has it. */
    fun onMediaMessage(msg: ExtensionMessage, body: String? = null) {
        val url = msg.url ?: return
        folderOf(url)?.let { if (segmentFolders.contains(msg.tabId to it)) return }
        if (!seen.add(msg.tabId to url)) return
        env.execute {
            val text = body ?: env.fetch(url, msg, MAX_BYTES)?.let { bytes ->
                if (bytes.size > MAX_BYTES) {
                    Logger.info("XDM", "Subtitle larger than $MAX_BYTES bytes, ignoring: $url")
                    return@execute
                }
                Subtitles.decode(bytes)
            } ?: return@execute
            process(msg, text)?.let { env.offer(it) }
        }
    }

    /** What [text], the body of [msg]'s URL, is offered as; null when it is not offered. */
    internal fun process(msg: ExtensionMessage, text: String): CapturedSubtitle? {
        val url = msg.url ?: return null
        val summary = Subtitles.summarize(text) ?: run {
            Logger.info("XDM", "Not a subtitle file: $url")
            return null
        }
        when {
            summary.hlsSegment -> {
                folderOf(url)?.let { segmentFolders.add(msg.tabId to it) }
                return null
            }
            summary.imageTrack -> {
                Logger.info("XDM", "Thumbnail track, not a subtitle: $url")
                return null
            }
            summary.cues == 0 -> return null
        }
        val language = summary.language ?: languageFromUrl(url)
        val name = subtitleName(withoutLanguage(baseName(msg), language), language, summary.format)
        val dest = CaptureContext.host.destination(name, url, getHeader("Referer", msg.requestHeaders) ?: msg.tabUrl)
        val size = text.toByteArray(Charsets.UTF_8).size.toLong()
        val task = HttpDownloadTaskInfo(
            id = CoreUtils.uniqueId(),
            url = url,
            fileName = name,
            respectFileName = true,
            cookie = msg.cookie,
            headers = msg.requestHeaders,
            origin = getHeader("Referer", msg.requestHeaders) ?: msg.tabUrl,
            categoryId = dest.first,
            targetFolder = dest.second,
            // A few KB: more connections only cost requests.
            maxPiece = 1,
            authInfo = null,
            knownFileSize = null,
        )
        val display = StreamingVideoDisplayInfo(
            quality = description(summary.format, language),
            size = size,
            duration = summary.lastEndMs / 1000,
            tabUrl = msg.tabUrl,
            tabId = msg.tabId,
        )
        return CapturedSubtitle(task, display, summary.format, language)
    }

    fun onTabNavigated(tabId: String) {
        synchronized(seen) { seen.removeIf { it.first == tabId } }
        synchronized(segmentFolders) { segmentFolders.removeIf { it.first == tabId } }
    }

    /** The page's title, else the URL's file name without its extension. */
    private fun baseName(msg: ExtensionMessage): String {
        val title = msg.tabTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (title != null) return title
        val file = FileUtils.getFileName(msg.url) ?: ""
        return file.substringBeforeLast('.').takeIf { it.isNotBlank() } ?: "subtitle"
    }

    /** [base] without a trailing language token ("episode.de" -> "episode"), which the name adds back. */
    private fun withoutLanguage(base: String, language: String?): String {
        val lang = language ?: return base
        for (sep in listOf(".", "_", "-")) {
            val suffix = sep + lang
            val alt = sep + lang.replace('-', '_')
            for (x in listOf(suffix, alt)) {
                if (base.length > x.length && base.endsWith(x, ignoreCase = true)) return base.dropLast(x.length)
            }
        }
        return base
    }

    companion object {
        /** Larger than any text subtitle file; a bigger body is something else. */
        const val MAX_BYTES = 4 shl 20

        private val ISO_LANGUAGES: Set<String> = Locale.getISOLanguages().toSet()
        private val QUERY_KEYS = setOf("lang", "language", "srclang", "locale", "tlang")
        private val LANG_TAG = Regex("""^([a-z]{2,3})(?:[-_]([a-z]{2}|[a-z]{4}|\d{3}))?$""", RegexOption.IGNORE_CASE)

        /** `<base>.<language>.<ext>`, or `<base>.<ext>` without a language. */
        fun subtitleName(base: String, language: String?, format: Subtitles.Format): String {
            val clean = FileUtils.sanitizeFileName(base)?.trim()?.takeIf { it.isNotEmpty() } ?: "subtitle"
            val lang = language?.let { FileUtils.sanitizeFileName(it) }?.takeIf { it.isNotBlank() }
            return if (lang != null) "$clean.$lang.${format.ext}" else "$clean.${format.ext}"
        }

        /**
         * The list's description: `[VTT] en`, the `[ext]` form plain videos use, so lists show the
         * format as a badge. Language-neutral, the list has no translations.
         */
        fun description(format: Subtitles.Format, language: String?): String =
            "[${format.name}]" + (language?.let { " $it" } ?: "")

        /**
         * A language tag the URL carries: a `lang`-like query parameter, the file name's last
         * `.`/`_`/`-` token (`movie_en.vtt`, `ep1.pt-BR.vtt`), or a path segment that is one
         * (`/subs/de/1.vtt`). Only ISO 639 codes count, so `_hd` or `_v2` are not taken.
         */
        fun languageFromUrl(url: String): String? {
            val parsed = url.toHttpUrlOrNull() ?: return null
            for (key in parsed.queryParameterNames) {
                if (key.lowercase(Locale.ROOT) in QUERY_KEYS) {
                    parsed.queryParameter(key)?.let { tag(it, threeLetters = true) }?.let { return it }
                }
            }
            val segments = parsed.pathSegments.filter { it.isNotEmpty() }
            val file = segments.lastOrNull()?.substringBeforeLast('.') ?: return null
            // "ep1.pt-BR" and "movie_pt-BR" keep the region, "ep1_pt_br" joins its last two tokens
            // (else "br" is Breton); "movie-en" is split on the hyphen.
            val tokens = file.split('_', '.')
            if (tokens.size >= 3 && tokens.last().length != 3) tag(tokens[tokens.size - 2] + "-" + tokens.last())?.let { return it }
            tag(tokens.last())?.let { return it }
            tag(file.split('-').last())?.let { return it }
            return segments.dropLast(1).asReversed().firstNotNullOfOrNull { tag(it) }
        }

        /**
         * [s] as a language tag (`en`, `pt-BR`, `zh-Hans`) when its language is an ISO 639-1 code.
         * Three-letter codes only where the spot says it is a language ([threeLetters]): in a
         * path, too many words are three letters long.
         */
        private fun tag(s: String?, threeLetters: Boolean = false): String? {
            val m = LANG_TAG.find(s?.trim() ?: return null) ?: return null
            val lang = m.groupValues[1].lowercase(Locale.ROOT)
            if (lang.length == 2 && lang !in ISO_LANGUAGES) return null
            if (lang.length == 3 && !threeLetters) return null
            val region = m.groupValues[2]
            return when {
                region.isEmpty() -> lang
                region.length == 4 -> "$lang-${region.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }}"
                else -> "$lang-${region.uppercase(Locale.ROOT)}"
            }
        }

        private fun folderOf(url: String): String? = url.toHttpUrlOrNull()?.resolve(".")?.toString()

        private fun <T> lruSet(max: Int): MutableSet<T> = Collections.synchronizedSet(
            Collections.newSetFromMap(object : LinkedHashMap<T, Boolean>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<T, Boolean>): Boolean = size > max
            })
        )
    }
}
