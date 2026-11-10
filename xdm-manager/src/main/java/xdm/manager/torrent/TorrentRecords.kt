// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager.torrent

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Kotlin side of the native library's binary records (layouts in include/xdm_torrent.h of the separate libtorrent-builds repo).

/** Torrent flags for adding ([TorrentEngine.addTorrent]) and [TorrentEngine.setFlags]. */
object TorrentFlags {
    const val PAUSED = 0x01
    const val SEQUENTIAL = 0x02
    /** No file writes: used while only the metadata of a magnet is wanted. */
    const val UPLOAD_MODE = 0x04
    const val AUTO_MANAGED = 0x08
    /** No peer exchange for this torrent; set when adding it (C API 4). */
    const val DISABLE_PEX = 0x10

    /** [TorrentEngine.requestResume]: skip (RESUME_SKIPPED) when nothing changed since the last save. */
    const val RESUME_IF_MODIFIED = 0x01
}

/** File priorities ([TorrentFile.priority]). */
object FilePriority {
    const val SKIP: Byte = 0
    const val LOW: Byte = 1
    const val NORMAL: Byte = 4
    const val HIGH: Byte = 7
}

enum class TorrentEventType(val id: Int) {
    METADATA(1), METADATA_FAILED(2), STATE(3), FINISHED(4), PAUSED(5), RESUMED(6), ERROR(7), RESUME_DATA(8),
    RESUME_SKIPPED(9), RESUME_FAILED(10), MOVED(11), MOVE_FAILED(12), REMOVED(13), DELETED(14), DELETE_FAILED(15),
    CHECKED(16), LISTEN_OK(17), LISTEN_FAILED(18), STATUS(19), LOG(20), SESSION_STATS(21), FLUSHED(22);

    companion object {
        private val byId = values().associateBy { it.id }
        fun of(id: Int): TorrentEventType? = byId[id]
    }
}

/** libtorrent's torrent states. */
object TorrentState {
    const val CHECKING_FILES = 1
    const val DOWNLOADING_METADATA = 2
    const val DOWNLOADING = 3
    const val FINISHED = 4
    const val SEEDING = 5
    const val CHECKING_RESUME_DATA = 7
}

/**
 * One event from the engine. [key] is the torrent's key, empty for session events. Only the fields of its
 * [type] are set: [code]/[message] for errors and logs, [state], [data] (resume data), [path] (MOVED), [port]
 * (LISTEN_OK), [status] (STATUS), [dhtNodes] (SESSION_STATS).
 */
data class TorrentEvent(
    val type: TorrentEventType,
    val key: String,
    val code: Int = 0,
    val message: String = "",
    val state: Int = 0,
    val data: ByteArray? = null,
    val path: String? = null,
    val port: Int = 0,
    val status: TorrentStatus? = null,
    val dhtNodes: Long = 0,
)

data class TorrentStatus(
    val key: String,
    val state: Int,
    val flags: Int,
    /** Bytes of the selected files. */
    val totalWanted: Long,
    val totalWantedDone: Long,
    val totalDone: Long,
    val allTimeDownload: Long,
    val allTimeUpload: Long,
    val downloadRate: Int,
    val uploadRate: Int,
    val peers: Int,
    val seeds: Int,
    /** Scrape results, -1 unknown. */
    val complete: Int,
    val incomplete: Int,
    val connections: Int,
    /** Of the current state: download progress, or checking progress while checking. */
    val progressPpm: Int,
    val addedTime: Long,
    val completedTime: Long,
    val activeSeconds: Long,
    val seedingSeconds: Long,
    /** Distributed copies x 1000, -1 unknown. */
    val availabilityMilli: Int,
    val numPieces: Int,
    val piecesDone: Int,
    val name: String,
    val savePath: String,
    val error: String,
) {
    val paused get() = flags and SF_PAUSED != 0
    val hasMetadata get() = flags and SF_HAS_METADATA != 0
    val finished get() = flags and SF_FINISHED != 0
    val seeding get() = flags and SF_SEEDING != 0
    val errored get() = flags and SF_ERRORED != 0
    val checking get() = state == TorrentState.CHECKING_FILES || state == TorrentState.CHECKING_RESUME_DATA

    /** Uploaded over downloaded, all time; what was already on disk counts as downloaded. */
    val ratio: Float
        get() {
            val base = maxOf(allTimeDownload, totalDone)
            return if (base > 0) allTimeUpload.toFloat() / base else 0f
        }

    companion object {
        const val SF_PAUSED = 0x001
        const val SF_AUTO_MANAGED = 0x002
        const val SF_SEQUENTIAL = 0x004
        const val SF_UPLOAD_MODE = 0x008
        const val SF_HAS_METADATA = 0x010
        const val SF_FINISHED = 0x020
        const val SF_SEEDING = 0x040
        const val SF_ERRORED = 0x080
        const val SF_MOVING = 0x100
        const val SF_NEED_SAVE = 0x200
    }
}

data class TorrentFile(
    val size: Long,
    val done: Long,
    val priority: Byte,
    val attributes: Int,
    /** Relative, '/' separated; starts with the torrent name for multi-file torrents. */
    val path: String,
) {
    /** Padding files are never shown and never selectable. */
    val isPad get() = attributes and ATTR_PAD != 0

    companion object {
        const val ATTR_PAD = 0x1
        const val ATTR_HIDDEN = 0x2
        const val ATTR_EXECUTABLE = 0x4
        const val ATTR_SYMLINK = 0x8
    }
}

data class TorrentInfo(
    val v1: String,
    val v2: String,
    val name: String,
    val totalSize: Long,
    val pieceLength: Int,
    val numPieces: Int,
    val flags: Int,
    val comment: String,
    val creator: String,
    val creationDate: Long,
    val files: List<TorrentFile>,
) {
    /** Our key: the v1 hash, else the v2 hash truncated to 20 bytes. */
    val key: String get() = if (v1.isNotEmpty()) v1 else v2.take(40)
    val isPrivate get() = flags and 0x1 != 0

    /** Whether the torrent is one file saved directly in the folder (else a folder named [name]). */
    val singleFile: Boolean get() = files.count { !it.isPad } == 1 && !files.first { !it.isPad }.path.contains('/')
}

class PieceMap(val numPieces: Int, private val bits: ByteArray) {
    fun has(i: Int): Boolean = i in 0 until numPieces && (bits[i ushr 3].toInt() shr (7 - (i and 7))) and 1 != 0
    val count: Int get() = (0 until numPieces).count { has(it) }
}

data class TorrentPeer(
    val ip: String,
    val port: Int,
    val client: String,
    val flags: Int,
    val sources: Int,
    val downloadRate: Int,
    val uploadRate: Int,
    val totalDownload: Long,
    val totalUpload: Long,
    val progressPpm: Int,
) {
    companion object {
        const val SEED = 0x001
        const val INCOMING = 0x002
        const val ENCRYPTED = 0x004
        const val UTP = 0x008
        const val INTERESTED = 0x010
        const val CHOKED = 0x020
        const val REMOTE_INTERESTED = 0x040
        const val REMOTE_CHOKED = 0x080
        const val SNUBBED = 0x100
        const val I2P = 0x200

        const val SRC_TRACKER = 0x01
        const val SRC_DHT = 0x02
        const val SRC_PEX = 0x04
        const val SRC_LSD = 0x08
        const val SRC_RESUME = 0x10
        const val SRC_INCOMING = 0x20
    }
}

data class TorrentTracker(
    val url: String,
    val tier: Int,
    val source: Int,
    /** [NOT_CONTACTED], [WORKING], [UPDATING], [ERROR]. */
    val status: Int,
    /** Scrape results, -1 unknown. */
    val seeds: Int,
    val peers: Int,
    val downloaded: Int,
    val fails: Int,
    /** Unix seconds, 0 none. */
    val nextAnnounce: Long,
    val message: String,
) {
    companion object {
        const val NOT_CONTACTED = 0
        const val WORKING = 1
        const val UPDATING = 2
        const val ERROR = 3
    }
}

/** Decoders for the native records. Each throws [IllegalArgumentException] on a malformed buffer. */
object TorrentRecords {
    private const val HASH_LEN = 40

    private class Reader(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        val b: ByteBuffer = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN)
        fun u8() = b.get().toInt() and 0xff
        fun u16() = b.short.toInt() and 0xffff
        fun i32() = b.int
        fun u32(): Long = b.int.toLong() and 0xffffffffL
        fun i64() = b.long
        fun bytes(n: Int): ByteArray {
            require(n >= 0 && n <= b.remaining()) { "record runs past the end" }
            return ByteArray(n).also { b.get(it) }
        }
        fun str(): String = String(bytes(u32().toInt()), Charsets.UTF_8)
        fun hash(): String = String(bytes(HASH_LEN), Charsets.US_ASCII).let { if (it.all { c -> c == '0' }) "" else it }
        fun header() {
            require(u8() == 1) { "unsupported record version" }
            u8(); u16()
        }
    }

    private inline fun <T> decode(block: () -> T): T = try {
        block()
    } catch (e: java.nio.BufferUnderflowException) {
        throw IllegalArgumentException("record runs past the end", e)
    }

    /** The events in a buffer from `xt_poll_events` ([length] bytes). Unknown event types are skipped. */
    fun events(buf: ByteArray, length: Int = buf.size): List<TorrentEvent> = decode {
        if (length == 0) return@decode emptyList()
        val r = Reader(buf, 0, length)
        require(r.u8() == 1) { "unsupported record version" }
        r.u8()
        val count = r.u16()
        val out = ArrayList<TorrentEvent>(count)
        repeat(count) {
            val typeId = r.u16()
            r.u16()
            val len = r.u32().toInt()
            val payload = r.bytes(len)
            TorrentEventType.of(typeId)?.let { out.add(event(it, payload)) }
        }
        out
    }

    private fun event(type: TorrentEventType, payload: ByteArray): TorrentEvent {
        val r = Reader(payload)
        val key = r.hash()
        return when (type) {
            TorrentEventType.STATE -> TorrentEvent(type, key, state = r.i32())
            TorrentEventType.ERROR, TorrentEventType.METADATA_FAILED, TorrentEventType.RESUME_FAILED,
            TorrentEventType.MOVE_FAILED, TorrentEventType.DELETE_FAILED, TorrentEventType.LISTEN_FAILED,
            TorrentEventType.LOG -> TorrentEvent(type, key, code = r.i32(), message = r.str())

            TorrentEventType.RESUME_DATA -> TorrentEvent(type, key, data = r.bytes(r.u32().toInt()))
            TorrentEventType.MOVED -> TorrentEvent(type, key, path = r.str())
            TorrentEventType.LISTEN_OK -> TorrentEvent(type, key, port = r.i32())
            TorrentEventType.STATUS -> TorrentEvent(type, key, status = status(key, r))
            TorrentEventType.SESSION_STATS -> TorrentEvent(type, key, dhtNodes = r.i64())
            else -> TorrentEvent(type, key)
        }
    }

    private fun status(key: String, r: Reader) = TorrentStatus(
        key = key,
        state = r.i32(),
        flags = r.i32(),
        totalWanted = r.i64(),
        totalWantedDone = r.i64(),
        totalDone = r.i64(),
        allTimeDownload = r.i64(),
        allTimeUpload = r.i64(),
        downloadRate = r.i32(),
        uploadRate = r.i32(),
        peers = r.i32(),
        seeds = r.i32(),
        complete = r.i32(),
        incomplete = r.i32(),
        connections = r.i32(),
        progressPpm = r.i32(),
        addedTime = r.i64(),
        completedTime = r.i64(),
        activeSeconds = r.i64(),
        seedingSeconds = r.i64(),
        availabilityMilli = r.i32(),
        numPieces = r.i32(),
        piecesDone = r.i32(),
        name = r.str(),
        savePath = r.str(),
        error = r.str(),
    )

    fun info(buf: ByteArray): TorrentInfo = decode {
        val r = Reader(buf)
        r.header()
        val v1 = r.str()
        val v2 = r.str()
        val name = r.str()
        val total = r.i64()
        val pieceLength = r.i32()
        val numPieces = r.i32()
        val flags = r.i32()
        val comment = r.str()
        val creator = r.str()
        val date = r.i64()
        val n = r.u32().toInt()
        val files = ArrayList<TorrentFile>(minOf(n, 65536))
        repeat(n) {
            val size = r.i64()
            val done = r.i64()
            val prio = r.u8().toByte()
            val attr = r.u8()
            r.u16()
            files.add(TorrentFile(size, done, prio, attr, r.str()))
        }
        TorrentInfo(v1, v2, name, total, pieceLength, numPieces, flags, comment, creator, date, files)
    }

    fun pieces(buf: ByteArray): PieceMap = decode {
        val r = Reader(buf)
        r.header()
        val n = r.i32()
        require(n >= 0) { "negative piece count" }
        PieceMap(n, r.bytes((n + 7) / 8))
    }

    fun peers(buf: ByteArray): List<TorrentPeer> = decode {
        val r = Reader(buf)
        r.header()
        val n = r.u32().toInt()
        List(n) {
            TorrentPeer(
                ip = r.str(), port = r.i32(), client = r.str(), flags = r.i32(), sources = r.i32(),
                downloadRate = r.i32(), uploadRate = r.i32(), totalDownload = r.i64(), totalUpload = r.i64(),
                progressPpm = r.i32(),
            )
        }
    }

    fun trackers(buf: ByteArray): List<TorrentTracker> = decode {
        val r = Reader(buf)
        r.header()
        val n = r.u32().toInt()
        List(n) {
            val url = r.str()
            val tier = r.u8()
            val source = r.u8()
            val status = r.u8()
            r.u8()
            TorrentTracker(
                url = url, tier = tier, source = source, status = status, seeds = r.i32(), peers = r.i32(),
                downloaded = r.i32(), fails = r.i32(), nextAnnounce = r.i64(), message = r.str(),
            )
        }
    }
}
