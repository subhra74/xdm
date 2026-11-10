package xdm.app.ytdlp

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.ExtractorSource
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.web.streaming.manifest.dash.DashSegment
import xdm.core.network.http.HeaderMap
import xdm.core.util.CoreUtils
import xdm.core.util.FileUtils
import java.util.Locale

/** A row of the Video download quality list. */
sealed class VideoChoice {
    abstract val formats: List<YtFormat>

    /** The container the file is saved as: yt-dlp's own pick for the formats (`mp4`, `webm`, `mkv`, `m4a`). */
    abstract val container: String
    val formatIds: List<String> get() = formats.map { it.id }
    val audioOnly: Boolean get() = formats.none { it.hasVideo }

    /** Total size; null when a format has none. [sizeExact] is false when one is yt-dlp's estimate. */
    val size: Long? get() = formats.map { it.size }.takeIf { s -> s.all { it != null } }?.sumOf { it!! }
    val sizeExact: Boolean get() = formats.all { it.sizeExact }

    /** A video-only and an audio-only track, merged after the download. */
    data class Pair(val video: YtFormat, val audio: YtFormat, override val container: String) : VideoChoice() {
        override val formats get() = listOf(video, audio)
    }

    /** One format: video with audio, or audio alone. */
    data class Single(val format: YtFormat, override val container: String) : VideoChoice() {
        override val formats get() = listOf(format)
    }

    val label: String
        get() {
            val v = formats.firstOrNull { it.hasVideo }
            val a = when (this) {
                is Pair -> audio
                is Single -> format.takeIf { it.hasAudio }
            }
            val parts = ArrayList<String>()
            if (audioOnly) {
                val f = formats[0]
                parts.add((f.abr ?: f.tbr)?.let { "${it.toInt()} kbps" } ?: f.formatNote ?: f.id)
                codecName(f.acodec)?.let { parts.add(it) }
            } else {
                val f = v ?: formats[0]
                parts.add(resolution(f))
                listOfNotNull(codecName(f.vcodec), codecName(a?.acodec)).takeIf { it.isNotEmpty() }
                    ?.let { parts.add(it.joinToString(" + ")) }
            }
            parts.add(container)
            return parts.joinToString(" · ")
        }
}

object YtDlpChoices {
    /** 10 MiB, the request size yt-dlp uses where the site throttles longer ones. */
    const val DEFAULT_PIECE = 10L * 1024 * 1024

    /**
     * The quality list: merged video+audio pairs, highest first, one per resolution, frame rate and
     * video codec; then files that hold both; then the best audio of each kind.
     */
    fun of(info: YtInfo): List<VideoChoice> {
        // Live streams are not supported.
        if (info.isLive) return emptyList()
        val formats = info.formats.filter { it.supported }
        val videoOnly = formats.filter { it.hasVideo && !it.hasAudio }
        val audioOnly = formats.filter { !it.hasVideo && it.hasAudio }
        val muxed = formats.filter { it.hasVideo && it.hasAudio }

        val pairs = videoOnly
            .groupBy { Triple(it.height ?: 0, highFps(it), codecName(it.vcodec)) }
            // A plain file before an HLS copy of the same video: it has a size and downloads in pieces.
            .values.mapNotNull { group ->
                group.maxWithOrNull(compareBy<YtFormat> { it.kind == YtFormat.Kind.FILE }.thenBy { it.tbr ?: 0.0 })
            }
            .mapNotNull { v ->
                val sameKind = audioOnly.filter { it.kind == v.kind }
                val audio = sameKind.filter { family(it.ext) == family(v.ext) }.maxByOrNull { bitrate(it) }
                    ?: sameKind.maxByOrNull { bitrate(it) }
                    ?: return@mapNotNull null
                VideoChoice.Pair(v, audio, pairContainer(v, audio))
            }
            .sortedWith(compareByDescending<VideoChoice.Pair> { it.video.height ?: 0 }
                .thenByDescending { highFps(it.video) }
                .thenBy { codecRank(it.video.vcodec) })

        val singles = muxed
            .sortedWith(compareByDescending<YtFormat> { it.height ?: 0 }.thenByDescending { it.tbr ?: 0.0 })
            .map { VideoChoice.Single(it, singleContainer(it)) }

        val audio = audioOnly.groupBy { family(it.ext) to it.kind }.values
            .mapNotNull { g -> g.maxByOrNull { bitrate(it) } }
            .sortedByDescending { bitrate(it) }
            .map { VideoChoice.Single(it, singleContainer(it)) }

        return pairs + singles + audio
    }

    /** yt-dlp's merge rule: mp4 video with m4a audio stays mp4, webm with webm stays webm, else mkv. */
    internal fun pairContainer(v: YtFormat, a: YtFormat): String = when {
        v.ext == "mp4" && (a.ext == "m4a" || a.ext == "mp4") -> "mp4"
        v.ext == "webm" && a.ext == "webm" -> "webm"
        else -> "mkv"
    }

    /** HLS (TS) is saved as mp4, like yt-dlp does; other files keep their extension. */
    private fun singleContainer(f: YtFormat): String = when {
        f.kind == YtFormat.Kind.HLS -> if (f.hasVideo) "mp4" else "m4a"
        else -> f.ext?.takeIf { it.isNotBlank() && it != "unknown_video" } ?: if (f.hasVideo) "mp4" else "m4a"
    }

    private fun family(ext: String?) = when (ext) {
        "mp4", "m4a", "m4v", "mov" -> "mp4"
        "webm", "weba" -> "webm"
        else -> ext ?: ""
    }

    private fun bitrate(f: YtFormat) = f.abr ?: f.tbr ?: 0.0
    private fun highFps(f: YtFormat) = (f.fps ?: 0.0) > 30.5

    /** H.264 first: it plays everywhere. */
    private fun codecRank(codec: String?) = when (codecName(codec)) {
        "H.264" -> 0; "VP9" -> 1; "H.265" -> 2; "AV1" -> 3; else -> 4
    }

    /** The name of the file a choice is saved as: the page title with the container's extension. */
    fun fileName(info: YtInfo, choice: VideoChoice): String =
        FileUtils.sanitizeFileName("${info.title.take(150).trim()}.${choice.container}") ?: "video.${choice.container}"
}

internal fun resolution(f: YtFormat): String {
    val h = f.height ?: return f.formatNote ?: f.id
    val fps = f.fps?.takeIf { it > 30.5 }?.let { String.format(Locale.ROOT, "%.0f", it) } ?: ""
    return "${h}p$fps"
}

internal fun codecName(codec: String?): String? {
    val c = codec?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() && it != "none" } ?: return null
    return when {
        c.startsWith("avc") || c.startsWith("h264") -> "H.264"
        c.startsWith("hev") || c.startsWith("hvc") || c.startsWith("h265") -> "H.265"
        c.startsWith("vp9") || c.startsWith("vp09") -> "VP9"
        c.startsWith("vp8") -> "VP8"
        c.startsWith("av01") || c.startsWith("av1") -> "AV1"
        c.startsWith("mp4a") || c == "aac" -> "AAC"
        c.startsWith("opus") -> "Opus"
        c.startsWith("vorbis") -> "Vorbis"
        c.startsWith("mp3") || c == "mp4a.40.34" -> "MP3"
        c.startsWith("ac-3") || c == "ac3" -> "AC-3"
        c.startsWith("ec-3") || c == "eac3" -> "E-AC-3"
        c.startsWith("flac") -> "FLAC"
        else -> codec.substringBefore('.')
    }
}

/** The download a choice becomes; started through the download manager. */
sealed class YtDlpTask {
    data class Http(val task: HttpDownloadTaskInfo) : YtDlpTask()
    data class Hls(val task: HlsDownloadTaskInfo) : YtDlpTask()
    data class Dash(val task: DashDownloadTaskInfo) : YtDlpTask()
}

/** Where and how a choice is saved, as picked in the dialog. */
data class SaveAs(val fileName: String, val targetFolder: String, val categoryId: String?, val maxPiece: Int, val tempDir: String)

object YtDlpTasks {
    /**
     * Turns [choice] into a download. Plain files are cut into byte-range pieces, each its own request,
     * when the site limits request size or when tracks are merged; [sizes] gives a track's exact size
     * when yt-dlp had none (probed), as pieces need it. Merging is the app's muxer's ([YtDlpMuxer], or
     * ffmpeg when set in Settings).
     */
    fun build(info: YtInfo, choice: VideoChoice, save: SaveAs, sizes: Map<String, Long> = emptyMap()): YtDlpTask {
        val source = ExtractorSource(YtDlp.EXTRACTOR_ID, info.webpageUrl, choice.formatIds, choice.container)
        val first = choice.formats[0]
        val headers = headerMap(first.headers)
        val cookie = choice.formats.mapNotNull { it.cookieHeader }.distinct().joinToString("; ").ifEmpty { null }
        val origin = info.webpageUrl.ifEmpty { null }
        val video = (choice as? VideoChoice.Pair)?.video ?: choice.formats.firstOrNull { it.hasVideo }
        val audio = (choice as? VideoChoice.Pair)?.audio ?: choice.formats.firstOrNull { !it.hasVideo }

        when (first.kind) {
            YtFormat.Kind.HLS -> return YtDlpTask.Hls(
                HlsDownloadTaskInfo(
                    id = CoreUtils.uniqueId(), fileName = save.fileName, tempDir = save.tempDir, respectFileName = true,
                    cookie = cookie, headers = headers, origin = origin, categoryId = save.categoryId,
                    targetFolder = save.targetFolder, maxPiece = save.maxPiece, authInfo = null,
                    url = (video ?: first).url!!,
                    audioUrl = if (choice is VideoChoice.Pair) choice.audio.url else null,
                    audioOnly = choice.audioOnly, independent = false, extractor = source,
                )
            )

            YtFormat.Kind.FRAGMENTS -> return dash(save, source, headers, cookie, origin, video, audio, ::fragments)

            YtFormat.Kind.FILE -> {
                // One plain file that the site serves at full speed: the HTTP downloader's own
                // pieces, and no merge.
                if (choice is VideoChoice.Single && first.chunkSize == null) {
                    return YtDlpTask.Http(
                        HttpDownloadTaskInfo(
                            id = CoreUtils.uniqueId(), url = first.url!!, fileName = save.fileName, respectFileName = true,
                            cookie = cookie, headers = headers, origin = origin, categoryId = save.categoryId,
                            targetFolder = save.targetFolder, maxPiece = save.maxPiece, authInfo = null,
                            knownFileSize = first.fileSize ?: sizes[first.id], extractor = source,
                        )
                    )
                }
                return dash(save, source.copy(pieces = true), headers, cookie, origin, video, audio) {
                    pieces(it, sizes[it.id])
                }
            }
        }
    }

    private fun dash(
        save: SaveAs, source: ExtractorSource, headers: HeaderMap, cookie: String?, origin: String?,
        video: YtFormat?, audio: YtFormat?, segments: (YtFormat) -> List<DashSegment>,
    ): YtDlpTask.Dash = YtDlpTask.Dash(
        DashDownloadTaskInfo(
            id = CoreUtils.uniqueId(), fileName = save.fileName, tempDir = save.tempDir, respectFileName = true,
            cookie = cookie, headers = headers, origin = origin, categoryId = save.categoryId,
            targetFolder = save.targetFolder, maxPiece = save.maxPiece, authInfo = null,
            videoSegments = video?.let(segments).orEmpty(),
            audioSegments = audio?.let(segments).orEmpty(),
            url = (video ?: audio)?.url ?: source.pageUrl,
            audioMime = audio?.let { mime("audio", it.ext) } ?: "",
            videoMime = video?.let { mime("video", it.ext) } ?: "",
            extractor = source,
        )
    )

    /**
     * A plain file as consecutive byte ranges of at most the site's request size (10 MiB when it has
     * none). The pieces need not line up with anything in the file: the muxer joins them first.
     * With no known size the file is one piece.
     */
    internal fun pieces(f: YtFormat, probedSize: Long?): List<DashSegment> {
        val url = f.url!!.toHttpUrlOrNull() ?: throw IllegalArgumentException("Bad URL for format ${f.id}")
        val size = f.fileSize ?: probedSize ?: return listOf(DashSegment(url))
        val piece = (f.chunkSize ?: YtDlpChoices.DEFAULT_PIECE).coerceAtLeast(64 * 1024)
        val list = ArrayList<DashSegment>()
        var offset = 0L
        while (offset < size) {
            val len = minOf(piece, size - offset)
            // (offset, length), as the DASH parsers give byte ranges.
            list.add(DashSegment(url, offset to len))
            offset += len
        }
        return list
    }

    internal fun fragments(f: YtFormat): List<DashSegment> {
        val base = f.fragmentBaseUrl?.toHttpUrlOrNull()
        return f.fragments.map { fr ->
            val u = fr.url?.toHttpUrlOrNull() ?: base?.resolve(fr.path ?: "")
                ?: throw IllegalArgumentException("Bad fragment in format ${f.id}")
            DashSegment(u)
        }
    }

    private fun mime(kind: String, ext: String?) = when (ext) {
        "m4a" -> "$kind/mp4"
        null -> "$kind/mp4"
        else -> "$kind/$ext"
    }

    /** yt-dlp's headers for the format; the downloader adds Range and Cookie itself. */
    internal fun headerMap(h: Map<String, String>): HeaderMap =
        h.filterKeys { !it.equals("Cookie", true) && !it.equals("Range", true) && !it.equals("Accept-Encoding", true) }
            .mapValues { listOf(it.value) }
}
