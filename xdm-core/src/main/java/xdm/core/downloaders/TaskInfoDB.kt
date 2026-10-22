package xdm.core.downloaders

import xdm.core.downloaders.web.streaming.manifest.dash.DashSegment
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.SensitiveHeaders
import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import xdm.core.util.TagReader
import xdm.core.util.TagWriter
import xdm.core.util.TaggedFile
import java.io.File
import java.net.URI

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

private const val HTTP_KNOWN_FILE_SIZE = 20

private const val HLS_AUDIO_URL = 20
private const val HLS_AUDIO_ONLY = 21
private const val HLS_TEMP_DIR = 22
private const val HLS_INDEPENDENT = 23

private const val DASH_TEMP_DIR = 20
private const val DASH_AUDIO_SEGMENT = 21
private const val DASH_VIDEO_SEGMENT = 22
private const val DASH_AUDIO_MIME = 23
private const val DASH_VIDEO_MIME = 24

private const val BATCH_ID = 1
private const val BATCH_NAME = 2
private const val BATCH_FOLDER = 3
private const val BATCH_MAX_PIECE = 4
private const val BATCH_ORIGIN = 5
private const val BATCH_HEADERS = 6
private const val BATCH_COOKIE = 7
private const val BATCH_ITEM = 8

// BatchItem
private const val ITEM_URL = 1
private const val ITEM_FILE_NAME = 2
private const val ITEM_COOKIE_GROUP = 3
private const val ITEM_KNOWN_SIZE = 4
private const val ITEM_NAME_FROM_PAGE = 5

// DashSegment; the range is present only when both of its fields are.
private const val SEG_URL = 1
private const val SEG_RANGE_START = 2
private const val SEG_RANGE_END = 3

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
    return DashSegment(URI(url), if (start != null && end != null) Pair(start, end) else null)
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
            while (true) {
                val no = r.next()
                if (no == -1) break
                if (c.read(no, r)) continue
                when (no) {
                    HTTP_KNOWN_FILE_SIZE -> knownFileSize = r.long()
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
            while (true) {
                val no = r.next()
                if (no == -1) break
                if (c.read(no, r)) continue
                when (no) {
                    HLS_AUDIO_URL -> audioUrl = r.string()
                    HLS_AUDIO_ONLY -> audioOnly = r.bool()
                    HLS_TEMP_DIR -> tempDir = r.string()
                    HLS_INDEPENDENT -> independent = r.bool()
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
                    task.targetFolder, task.maxPiece, task.cookie, task.headers, task.origin,
                )
                task.knownFileSize?.let { long(HTTP_KNOWN_FILE_SIZE, it) }
            }
        }.onFailure { Logger.error("XDM", "Error saving task info ${task.id}", it) }.isSuccess

    fun saveHlsTask(task: HlsDownloadTaskInfo) {
        AtomicIO.writeTransacted("task-${task.id}.info", configDir, ownerOnly = true) { out ->
            TaggedFile.write(out) {
                common(
                    DownloadType.Hls,
                    task.id, task.url, task.fileName, task.respectFileName, task.categoryId,
                    task.targetFolder, task.maxPiece, task.cookie, task.headers, task.origin,
                )
                stringOrNull(HLS_AUDIO_URL, task.audioUrl)
                bool(HLS_AUDIO_ONLY, task.audioOnly)
                string(HLS_TEMP_DIR, task.tempDir)
                bool(HLS_INDEPENDENT, task.independent)
            }
        }.onFailure { Logger.error("XDM", "Error saving task info ${task.id}", it) }
    }

    fun saveDashTask(task: DashDownloadTaskInfo) {
        AtomicIO.writeTransacted("task-${task.id}.info", configDir, ownerOnly = true) { out ->
            TaggedFile.write(out) {
                common(
                    DownloadType.Dash,
                    task.id, task.url, task.fileName, task.respectFileName, task.categoryId,
                    task.targetFolder, task.maxPiece, task.cookie, task.headers, task.origin,
                )
                string(DASH_TEMP_DIR, task.tempDir)
                task.audioSegments.forEach { dashSegment(DASH_AUDIO_SEGMENT, it) }
                task.videoSegments.forEach { dashSegment(DASH_VIDEO_SEGMENT, it) }
                string(DASH_AUDIO_MIME, task.audioMime)
                string(DASH_VIDEO_MIME, task.videoMime)
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
        while (true) {
            when (r.next()) {
                -1 -> break
                ITEM_URL -> url = r.string()
                ITEM_FILE_NAME -> fileName = r.string()
                ITEM_COOKIE_GROUP -> cookieGroup = r.int()
                ITEM_KNOWN_SIZE -> knownSize = r.long()
                ITEM_NAME_FROM_PAGE -> nameFromPage = r.bool()
                else -> r.skip()
            }
        }
        return BatchItem(
            url = url,
            fileName = fileName,
            cookieGroup = cookieGroup,
            knownSize = knownSize,
            nameFromPage = nameFromPage,
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
                for (item in task.items) {
                    message(BATCH_ITEM) {
                        string(ITEM_URL, item.url)
                        string(ITEM_FILE_NAME, item.fileName)
                        int(ITEM_COOKIE_GROUP, item.cookieGroup)
                        item.knownSize?.let { long(ITEM_KNOWN_SIZE, it) }
                        bool(ITEM_NAME_FROM_PAGE, item.nameFromPage)
                    }
                }
            }
        }.onFailure { Logger.error("XDM", "Error saving task info ${task.id}", it) }
    }

    fun deleteRecord(id: Long) {
        SensitiveHeaders.forget(id)
        File(configDir, "task-$id.info").delete()
        File(configDir, "task-$id.info.bak1").delete()
        File(configDir, "task-$id.info.bak2").delete()
    }
}
