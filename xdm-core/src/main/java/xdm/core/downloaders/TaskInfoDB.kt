// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.downloaders

import okhttp3.HttpUrl.Companion.toHttpUrl
import xdm.core.downloaders.web.streaming.manifest.dash.DashSegment
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.SensitiveHeaders
import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import xdm.core.util.TagReader
import xdm.core.util.TagWriter
import xdm.core.util.TaggedFile
import java.io.File

// Field numbers of `task-<id>.info` (see TaggedFile). Never reuse or retype a number. 1..19 are
// shared by the HTTP, HLS and DASH records, 20 and up are per type. Batch has its own numbering,
// except F_TYPE, which every record has: a getter returns null for a record of another type.
private const val F_ID = 1
private const val F_URL = 2
private const val F_FILE_NAME = 3
private const val F_RESPECT_FILE_NAME = 4
private const val F_CATEGORY_ID = 5
private const val F_TARGET_FOLDER = 6
private const val F_MAX_PIECE = 7
private const val F_COOKIE = 8
private const val F_HEADERS = 9
private const val F_ORIGIN = 10
private const val F_TYPE = 11
/** [ExtractorSource]: where an external extractor got the links. HTTP, HLS and DASH. */
private const val F_EXTRACTOR = 12

private const val HTTP_KNOWN_FILE_SIZE = 20
private const val HTTP_CONVERT_TO_SRT = 21
private const val HTTP_SOURCE_URL = 22
private const val HTTP_SOURCE_COOKIE = 23

private const val HLS_AUDIO_URL = 20
private const val HLS_AUDIO_ONLY = 21
private const val HLS_TEMP_DIR = 22
private const val HLS_INDEPENDENT = 23
private const val HLS_SUBTITLE = 24

private const val DASH_TEMP_DIR = 20
private const val DASH_AUDIO_SEGMENT = 21
private const val DASH_VIDEO_SEGMENT = 22
private const val DASH_AUDIO_MIME = 23
private const val DASH_VIDEO_MIME = 24
private const val DASH_SUBTITLE = 25

private const val BATCH_ID = 1
private const val BATCH_NAME = 2
private const val BATCH_FOLDER = 3
private const val BATCH_MAX_PIECE = 4
private const val BATCH_ORIGIN = 5
private const val BATCH_HEADERS = 6
private const val BATCH_COOKIE = 7
private const val BATCH_ITEM = 8
private const val BATCH_TEMP_DIR = 9

// Torrent: own numbering like Batch (F_TYPE shared).
private const val TORRENT_ID = 1
private const val TORRENT_KEY = 2
private const val TORRENT_NAME = 3
private const val TORRENT_FOLDER = 4
private const val TORRENT_MAGNET = 5
private const val TORRENT_CATEGORY_ID = 6
private const val TORRENT_FILE_PRIORITIES = 7
private const val TORRENT_SEQUENTIAL = 8
private const val TORRENT_ORIGIN = 9

// BatchItem
private const val ITEM_URL = 1
private const val ITEM_FILE_NAME = 2
private const val ITEM_COOKIE_GROUP = 3
private const val ITEM_KNOWN_SIZE = 4
private const val ITEM_NAME_FROM_PAGE = 5
private const val ITEM_AUDIO_URL = 6

// DashSegment; the range is present only when both of its fields are.
private const val SEG_URL = 1
private const val SEG_RANGE_START = 2
private const val SEG_RANGE_END = 3

// ExtractorSource.
private const val EX_EXTRACTOR = 1
private const val EX_PAGE_URL = 2
private const val EX_FORMAT_ID = 3
private const val EX_CONTAINER = 4
private const val EX_PIECES = 5

// SubtitleTrack.
private const val SUB_NAME = 1
private const val SUB_LANGUAGE = 2
private const val SUB_PLAYLIST_URL = 3
private const val SUB_SEGMENT = 4
private const val SUB_DEFAULT = 5
private const val SUB_FORCED = 6

// HeaderMap: one HEADER record per name, with its values in order.
private const val HEADER = 1
private const val HEADER_NAME = 1
private const val HEADER_VALUE = 2

private fun TagWriter.headers(no: Int, headers: HeaderMap?) {
    headers ?: return
    message(no) {
        for ((name, values) in headers) {
            message(HEADER) {
                string(HEADER_NAME, name)
                values.forEach { string(HEADER_VALUE, it) }
            }
        }
    }
}

private fun TagReader.headers(): HeaderMap {
    val r = message()
    val headers = LinkedHashMap<String, List<String>>()
    while (true) {
        when (r.next()) {
            -1 -> return headers
            HEADER -> {
                val h = r.message()
                var name: String? = null
                val values = ArrayList<String>()
                while (true) {
                    when (h.next()) {
                        -1 -> break
                        HEADER_NAME -> name = h.string()
                        HEADER_VALUE -> values.add(h.string())
                        else -> h.skip()
                    }
                }
                if (name != null) headers[name] = values
            }
            else -> r.skip()
        }
    }
}

private fun TagWriter.dashSegment(no: Int, seg: DashSegment) = message(no) {
    string(SEG_URL, seg.url.toString())
    seg.range?.let { long(SEG_RANGE_START, it.first); long(SEG_RANGE_END, it.second) }
}

private fun TagReader.dashSegment(): DashSegment {
    val r = message()
    var url = ""
    var start: Long? = null
    var end: Long? = null
    while (true) {
        when (r.next()) {
            -1 -> break
            SEG_URL -> url = r.string()
            SEG_RANGE_START -> start = r.long()
            SEG_RANGE_END -> end = r.long()
            else -> r.skip()
        }
    }
    return DashSegment(url.toHttpUrl(), if (start != null && end != null) Pair(start, end) else null)
}

private fun TagWriter.subtitle(no: Int, track: SubtitleTrack) = message(no) {
    string(SUB_NAME, track.name)
    string(SUB_LANGUAGE, track.language)
    stringOrNull(SUB_PLAYLIST_URL, track.playlistUrl)
    track.segments.forEach { dashSegment(SUB_SEGMENT, it) }
    bool(SUB_DEFAULT, track.isDefault)
    bool(SUB_FORCED, track.forced)
}

private fun TagReader.subtitle(): SubtitleTrack {
    val r = message()
    var name = ""
    var language = ""
    var playlistUrl: String? = null
    val segments = ArrayList<DashSegment>()
    var isDefault = false
    var forced = false
    while (true) {
        when (r.next()) {
            -1 -> break
            SUB_NAME -> name = r.string()
            SUB_LANGUAGE -> language = r.string()
            SUB_PLAYLIST_URL -> playlistUrl = r.string()
            SUB_SEGMENT -> segments.add(r.dashSegment())
            SUB_DEFAULT -> isDefault = r.bool()
            SUB_FORCED -> forced = r.bool()
            else -> r.skip()
        }
    }
    return SubtitleTrack(name, language, playlistUrl, segments, isDefault, forced)
}

private fun TagWriter.extractor(no: Int, source: ExtractorSource?) {
    source ?: return
    message(no) {
        string(EX_EXTRACTOR, source.extractor)
        string(EX_PAGE_URL, source.pageUrl)
        source.formatIds.forEach { string(EX_FORMAT_ID, it) }
        stringOrNull(EX_CONTAINER, source.container)
        if (source.pieces) bool(EX_PIECES, true)
    }
}

private fun TagReader.extractor(): ExtractorSource {
    val r = message()
    var extractor = ""
    var pageUrl = ""
    val formatIds = ArrayList<String>()
    var container: String? = null
    var pieces = false
    while (true) {
        when (r.next()) {
            -1 -> break
            EX_PIECES -> pieces = r.bool()
            EX_EXTRACTOR -> extractor = r.string()
            EX_PAGE_URL -> pageUrl = r.string()
            EX_FORMAT_ID -> formatIds.add(r.string())
            EX_CONTAINER -> container = r.string()
            else -> r.skip()
        }
    }
    return ExtractorSource(extractor, pageUrl, formatIds, container, pieces)
}

/** Fields 1..19, shared by the HTTP, HLS and DASH records. */
private class CommonFields {
    var id = 0L
    var url = ""
    var fileName = ""
    var respectFileName = false
    var categoryId: String? = null
    var targetFolder = ""
    var maxPiece = 0
    var cookie: String? = null
    var headers: HeaderMap? = null
    var origin: String? = null
    var type = 0
    var extractor: ExtractorSource? = null

    /** Reads field [no] if it is a shared one; false for any other field, which the caller handles. */
    fun read(no: Int, r: TagReader): Boolean {
        when (no) {
            F_ID -> id = r.long()
            F_URL -> url = r.string()
            F_FILE_NAME -> fileName = r.string()
            F_RESPECT_FILE_NAME -> respectFileName = r.bool()
            F_CATEGORY_ID -> categoryId = r.string()
            F_TARGET_FOLDER -> targetFolder = r.string()
            F_MAX_PIECE -> maxPiece = r.int()
            F_COOKIE -> cookie = r.string()
            F_HEADERS -> headers = r.headers()
            F_ORIGIN -> origin = r.string()
            F_TYPE -> type = r.int()
            F_EXTRACTOR -> extractor = r.extractor()
            else -> return false
        }
        return true
    }

    /** Call once [id] is read: puts back the credentials that were kept out of the file. */
    fun restoredHeaders() = SensitiveHeaders.restore(id, headers)
}

private fun TagWriter.common(
    type: DownloadType, id: Long, url: String, fileName: String, respectFileName: Boolean, categoryId: String?,
    targetFolder: String, maxPiece: Int, cookie: String?, headers: HeaderMap?, origin: String?,
    extractor: ExtractorSource?,
) {
    int(F_TYPE, type.id)
    long(F_ID, id)
    string(F_URL, url)
    string(F_FILE_NAME, fileName)
    bool(F_RESPECT_FILE_NAME, respectFileName)
    stringOrNull(F_CATEGORY_ID, categoryId)
    string(F_TARGET_FOLDER, targetFolder)
    int(F_MAX_PIECE, maxPiece)
    // Queued and "download later" tasks are started from the `.info` file alone, so the request
    // context must be persisted or the download runs without its cookies / headers.
    stringOrNull(F_COOKIE, cookie)
    // Credentials such as Authorization stay in memory only.
    headers(F_HEADERS, SensitiveHeaders.strip(id, headers))
    stringOrNull(F_ORIGIN, origin)
    extractor(F_EXTRACTOR, extractor)
}

/**
 * Persists immutable task parameters to `task-<id>.info`, as numbered fields (see [TaggedFile]):
 * a field is added by giving it a new number above, and readers skip numbers they do not know.
 */
class TaskInfoDB(private val configDir: String) {
    fun getHttpTask(id: Long): HttpDownloadTaskInfo? {
        AtomicIO.readTransacted("task-$id.info", configDir) { input ->
            val r = TaggedFile.read(input)
            val c = CommonFields()
            var knownFileSize: Long? = null
            var convertToSrt = false
            var sourceUrl: String? = null
            var sourceCookie: String? = null
            while (true) {
                val no = r.next()
                if (no == -1) break
                if (c.read(no, r)) continue
                when (no) {
                    HTTP_KNOWN_FILE_SIZE -> knownFileSize = r.long()
                    HTTP_CONVERT_TO_SRT -> convertToSrt = r.bool()
                    HTTP_SOURCE_URL -> sourceUrl = r.string()
                    HTTP_SOURCE_COOKIE -> sourceCookie = r.string()
                    else -> r.skip()
                }
            }
            if (c.type != DownloadType.Http.id) return null
            return HttpDownloadTaskInfo(
                id = c.id,
                url = c.url,
                fileName = c.fileName,
                respectFileName = c.respectFileName,
                cookie = c.cookie,
                headers = c.restoredHeaders(),
                origin = c.origin,
                categoryId = c.categoryId,
                targetFolder = c.targetFolder,
                maxPiece = c.maxPiece,
                authInfo = null,
                knownFileSize = knownFileSize,
                convertToSrt = convertToSrt,
                extractor = c.extractor,
                sourceUrl = sourceUrl,
                sourceCookie = sourceCookie,
            )
        }
        return null
    }

    fun getHlsTask(id: Long): HlsDownloadTaskInfo? {
        AtomicIO.readTransacted("task-$id.info", configDir) { input ->
            val r = TaggedFile.read(input)
            val c = CommonFields()
            var audioUrl: String? = null
            var audioOnly = false
            var tempDir = ""
            var independent = false
            val subtitles = ArrayList<SubtitleTrack>()
            while (true) {
                val no = r.next()
                if (no == -1) break
                if (c.read(no, r)) continue
                when (no) {
                    HLS_AUDIO_URL -> audioUrl = r.string()
                    HLS_AUDIO_ONLY -> audioOnly = r.bool()
                    HLS_TEMP_DIR -> tempDir = r.string()
                    HLS_INDEPENDENT -> independent = r.bool()
                    HLS_SUBTITLE -> subtitles.add(r.subtitle())
                    else -> r.skip()
                }
            }
            if (c.type != DownloadType.Hls.id) return null
            return HlsDownloadTaskInfo(
                id = c.id,
                url = c.url,
                audioUrl = audioUrl,
                fileName = c.fileName,
                respectFileName = c.respectFileName,
                cookie = c.cookie,
                headers = c.restoredHeaders(),
                origin = c.origin,
                categoryId = c.categoryId,
                targetFolder = c.targetFolder,
                maxPiece = c.maxPiece,
                authInfo = null,
                audioOnly = audioOnly,
                tempDir = tempDir,
                independent = independent,
                subtitles = subtitles,
                extractor = c.extractor,
            )
        }
        return null
    }

    fun getDashTask(id: Long): DashDownloadTaskInfo? {
        AtomicIO.readTransacted("task-$id.info", configDir) { input ->
            val r = TaggedFile.read(input)
            val c = CommonFields()
            var tempDir = ""
            val audioSegments = ArrayList<DashSegment>()
            val videoSegments = ArrayList<DashSegment>()
            var audioMime = ""
            var videoMime = ""
            val subtitles = ArrayList<SubtitleTrack>()
            while (true) {
                val no = r.next()
                if (no == -1) break
                if (c.read(no, r)) continue
                when (no) {
                    DASH_TEMP_DIR -> tempDir = r.string()
                    DASH_AUDIO_SEGMENT -> audioSegments.add(r.dashSegment())
                    DASH_VIDEO_SEGMENT -> videoSegments.add(r.dashSegment())
                    DASH_AUDIO_MIME -> audioMime = r.string()
                    DASH_VIDEO_MIME -> videoMime = r.string()
                    DASH_SUBTITLE -> subtitles.add(r.subtitle())
                    else -> r.skip()
                }
            }
            if (c.type != DownloadType.Dash.id) return null
            return DashDownloadTaskInfo(
                id = c.id,
                url = c.url,
                fileName = c.fileName,
                respectFileName = c.respectFileName,
                cookie = c.cookie,
                headers = c.restoredHeaders(),
                origin = c.origin,
                categoryId = c.categoryId,
                targetFolder = c.targetFolder,
                maxPiece = c.maxPiece,
                authInfo = null,
                tempDir = tempDir,
                audioSegments = audioSegments,
                videoSegments = videoSegments,
                audioMime = audioMime,
                videoMime = videoMime,
                subtitles = subtitles,
                extractor = c.extractor,
            )
        }
        return null
    }

    /** Returns whether the task info reached the disk; callers that delete files after it must check. */
    fun saveHttpTask(task: HttpDownloadTaskInfo): Boolean =
        AtomicIO.writeTransacted("task-${task.id}.info", configDir, ownerOnly = true) { out ->
            TaggedFile.write(out) {
                common(
                    DownloadType.Http,
                    task.id, task.url, task.fileName, task.respectFileName, task.categoryId,
                    task.targetFolder, task.maxPiece, task.cookie, task.headers, task.origin, task.extractor,
                )
                task.knownFileSize?.let { long(HTTP_KNOWN_FILE_SIZE, it) }
                if (task.convertToSrt) bool(HTTP_CONVERT_TO_SRT, true)
                task.sourceUrl?.let { string(HTTP_SOURCE_URL, it) }
                task.sourceCookie?.let { string(HTTP_SOURCE_COOKIE, it) }
            }
        }.onFailure { Logger.error("XDM", "Error saving task info ${task.id}", it) }.isSuccess

    fun saveHlsTask(task: HlsDownloadTaskInfo) {
        AtomicIO.writeTransacted("task-${task.id}.info", configDir, ownerOnly = true) { out ->
            TaggedFile.write(out) {
                common(
                    DownloadType.Hls,
                    task.id, task.url, task.fileName, task.respectFileName, task.categoryId,
                    task.targetFolder, task.maxPiece, task.cookie, task.headers, task.origin, task.extractor,
                )
                stringOrNull(HLS_AUDIO_URL, task.audioUrl)
                bool(HLS_AUDIO_ONLY, task.audioOnly)
                string(HLS_TEMP_DIR, task.tempDir)
                bool(HLS_INDEPENDENT, task.independent)
                task.subtitles.forEach { subtitle(HLS_SUBTITLE, it) }
            }
        }.onFailure { Logger.error("XDM", "Error saving task info ${task.id}", it) }
    }

    fun saveDashTask(task: DashDownloadTaskInfo) {
        AtomicIO.writeTransacted("task-${task.id}.info", configDir, ownerOnly = true) { out ->
            TaggedFile.write(out) {
                common(
                    DownloadType.Dash,
                    task.id, task.url, task.fileName, task.respectFileName, task.categoryId,
                    task.targetFolder, task.maxPiece, task.cookie, task.headers, task.origin, task.extractor,
                )
                string(DASH_TEMP_DIR, task.tempDir)
                task.audioSegments.forEach { dashSegment(DASH_AUDIO_SEGMENT, it) }
                task.videoSegments.forEach { dashSegment(DASH_VIDEO_SEGMENT, it) }
                string(DASH_AUDIO_MIME, task.audioMime)
                string(DASH_VIDEO_MIME, task.videoMime)
                task.subtitles.forEach { subtitle(DASH_SUBTITLE, it) }
            }
        }.onFailure { Logger.error("XDM", "Error saving task info ${task.id}", it) }
    }

    fun getBatchTask(id: Long): BatchDownloadTaskInfo? {
        AtomicIO.readTransacted("task-$id.info", configDir) { input ->
            val r = TaggedFile.read(input)
            var taskId = 0L
            var name = ""
            var folder = ""
            var maxPiece = 0
            var origin: String? = null
            var headers: HeaderMap? = null
            val cookies = ArrayList<String>()
            val items = ArrayList<BatchItem>()
            var tempDir: String? = null
            var type = 0
            while (true) {
                when (r.next()) {
                    -1 -> break
                    F_TYPE -> type = r.int()
                    BATCH_ID -> taskId = r.long()
                    BATCH_NAME -> name = r.string()
                    BATCH_FOLDER -> folder = r.string()
                    BATCH_MAX_PIECE -> maxPiece = r.int()
                    BATCH_ORIGIN -> origin = r.string()
                    BATCH_HEADERS -> headers = r.headers()
                    BATCH_COOKIE -> cookies.add(r.string())
                    BATCH_ITEM -> items.add(readBatchItem(r.message()))
                    BATCH_TEMP_DIR -> tempDir = r.string()
                    else -> r.skip()
                }
            }
            if (type != DownloadType.Batch.id) return null
            return BatchDownloadTaskInfo(
                id = taskId,
                name = name,
                folder = folder,
                headers = SensitiveHeaders.restore(taskId, headers),
                origin = origin,
                maxPiece = maxPiece,
                cookies = cookies,
                items = items,
                tempDir = tempDir,
            )
        }
        return null
    }

    private fun readBatchItem(r: TagReader): BatchItem {
        var url = ""
        var fileName = ""
        var cookieGroup = -1
        var knownSize: Long? = null
        var nameFromPage = false
        var audioUrl: String? = null
        while (true) {
            when (r.next()) {
                -1 -> break
                ITEM_URL -> url = r.string()
                ITEM_FILE_NAME -> fileName = r.string()
                ITEM_COOKIE_GROUP -> cookieGroup = r.int()
                ITEM_KNOWN_SIZE -> knownSize = r.long()
                ITEM_NAME_FROM_PAGE -> nameFromPage = r.bool()
                ITEM_AUDIO_URL -> audioUrl = r.string()
                else -> r.skip()
            }
        }
        return BatchItem(
            url = url,
            fileName = fileName,
            cookieGroup = cookieGroup,
            knownSize = knownSize,
            nameFromPage = nameFromPage,
            audioUrl = audioUrl,
        )
    }

    fun saveBatchTask(task: BatchDownloadTaskInfo) {
        AtomicIO.writeTransacted("task-${task.id}.info", configDir, ownerOnly = true) { out ->
            TaggedFile.write(out) {
                int(F_TYPE, DownloadType.Batch.id)
                long(BATCH_ID, task.id)
                string(BATCH_NAME, task.name)
                string(BATCH_FOLDER, task.folder)
                int(BATCH_MAX_PIECE, task.maxPiece)
                stringOrNull(BATCH_ORIGIN, task.origin)
                // Credentials such as Authorization stay in memory only.
                headers(BATCH_HEADERS, SensitiveHeaders.strip(task.id, task.headers))
                // In order: BatchItem.cookieGroup is an index into this list.
                task.cookies.forEach { string(BATCH_COOKIE, it) }
                task.tempDir?.let { string(BATCH_TEMP_DIR, it) }
                for (item in task.items) {
                    message(BATCH_ITEM) {
                        string(ITEM_URL, item.url)
                        string(ITEM_FILE_NAME, item.fileName)
                        int(ITEM_COOKIE_GROUP, item.cookieGroup)
                        item.knownSize?.let { long(ITEM_KNOWN_SIZE, it) }
                        bool(ITEM_NAME_FROM_PAGE, item.nameFromPage)
                        item.audioUrl?.let { string(ITEM_AUDIO_URL, it) }
                    }
                }
            }
        }.onFailure { Logger.error("XDM", "Error saving task info ${task.id}", it) }
    }

    fun getTorrentTask(id: Long): TorrentDownloadTaskInfo? {
        AtomicIO.readTransacted("task-$id.info", configDir) { input ->
            val r = TaggedFile.read(input)
            var type = 0
            var taskId = 0L
            var key = ""
            var name = ""
            var folder = ""
            var magnet: String? = null
            var categoryId: String? = null
            var priorities = ByteArray(0)
            var sequential = false
            var origin: String? = null
            while (true) {
                when (r.next()) {
                    -1 -> break
                    F_TYPE -> type = r.int()
                    TORRENT_ID -> taskId = r.long()
                    TORRENT_KEY -> key = r.string()
                    TORRENT_NAME -> name = r.string()
                    TORRENT_FOLDER -> folder = r.string()
                    TORRENT_MAGNET -> magnet = r.string()
                    TORRENT_CATEGORY_ID -> categoryId = r.string()
                    TORRENT_FILE_PRIORITIES -> priorities = r.bytes()
                    TORRENT_SEQUENTIAL -> sequential = r.bool()
                    TORRENT_ORIGIN -> origin = r.string()
                    else -> r.skip()
                }
            }
            if (type != DownloadType.Torrent.id) return null
            return TorrentDownloadTaskInfo(
                id = taskId,
                key = key,
                name = name,
                folder = folder,
                magnet = magnet,
                categoryId = categoryId,
                filePriorities = priorities,
                sequential = sequential,
                origin = origin,
            )
        }
        return null
    }

    /** Returns whether the task info reached the disk. */
    fun saveTorrentTask(task: TorrentDownloadTaskInfo): Boolean =
        AtomicIO.writeTransacted("task-${task.id}.info", configDir, ownerOnly = true) { out ->
            TaggedFile.write(out) {
                int(F_TYPE, DownloadType.Torrent.id)
                long(TORRENT_ID, task.id)
                string(TORRENT_KEY, task.key)
                string(TORRENT_NAME, task.name)
                string(TORRENT_FOLDER, task.folder)
                stringOrNull(TORRENT_MAGNET, task.magnet)
                stringOrNull(TORRENT_CATEGORY_ID, task.categoryId)
                if (task.filePriorities.isNotEmpty()) bytes(TORRENT_FILE_PRIORITIES, task.filePriorities)
                if (task.sequential) bool(TORRENT_SEQUENTIAL, true)
                stringOrNull(TORRENT_ORIGIN, task.origin)
            }
        }.onFailure { Logger.error("XDM", "Error saving task info ${task.id}", it) }.isSuccess

    fun deleteRecord(id: Long) {
        SensitiveHeaders.forget(id)
        File(configDir, "task-$id.info").delete()
        File(configDir, "task-$id.info.bak1").delete()
        File(configDir, "task-$id.info.bak2").delete()
    }
}
