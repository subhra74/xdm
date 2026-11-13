// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager.torrent

import xdm.core.util.Logger
import java.nio.ByteBuffer

/** Thrown by [TorrentEngine] calls that fail; [code] is the native `XT_E_*` value. */
class TorrentEngineException(val code: Int, message: String) : Exception(message)

/**
 * One BitTorrent session (the native library's, or a fake in tests). Torrents are addressed by their key
 * (40 hex). Control calls may come from any thread; [poll] from one thread only.
 */
interface TorrentEngine {
    /** Creates the session. [state] is what [state] returned last time, or null for a cold start. */
    fun open(settings: String, state: ByteArray?)
    fun applySettings(settings: String)

    /** The state to pass to the next [open]: DHT routing table and the listen interfaces. */
    fun state(): ByteArray?
    fun listenPort(): Int

    /** Returns the key. Throws [TorrentEngineException] (code [NativeTorrent.E_EXISTS] when already added). */
    fun addTorrent(torrent: ByteArray?, resume: ByteArray?, savePath: String?, priorities: ByteArray?, flags: Int): String
    fun addMagnet(uri: String, resume: ByteArray?, savePath: String?, flags: Int): String

    fun pause(key: String)
    fun resume(key: String)
    fun remove(key: String, deleteFiles: Boolean)
    fun recheck(key: String)
    fun reannounce(key: String)
    fun setFilePriorities(key: String, priorities: ByteArray)
    fun setFlags(key: String, set: Int, clear: Int)
    fun setLimits(key: String, downloadBps: Int, uploadBps: Int, maxConnections: Int)
    fun moveStorage(key: String, path: String)

    /** Adds a tracker at [tier]; a URL the torrent already has is left as it is. */
    fun addTracker(key: String, url: String, tier: Int)
    fun requestResume(key: String, flags: Int)

    /** Writes everything queued for the torrent's files; FLUSHED follows once it is on disk. */
    fun flush(key: String)

    /** Requests resume data for every torrent; returns how many requests were made. */
    fun requestResumeAll(flags: Int): Int
    fun addPeer(key: String, ip: String, port: Int)

    /** Asks for STATUS events of changed torrents in a later [poll]. */
    fun postUpdates()
    fun postSessionStats()

    /** Waits up to [timeoutMs] for events. */
    fun poll(timeoutMs: Int): List<TorrentEvent>

    /** Null when the torrent has no metadata yet or is not in the session. */
    fun info(key: String): TorrentInfo?
    fun metadata(key: String): ByteArray?
    fun pieces(key: String): PieceMap?
    fun peers(key: String): List<TorrentPeer>
    fun trackers(key: String): List<TorrentTracker>

    /** Destroys the session; blocks up to the tracker stop timeout. */
    fun close()

    /** Parses a .torrent file without a session; null when it is not one. */
    fun parseTorrent(torrent: ByteArray): TorrentInfo?

    /** The key of a magnet link, or null when it is not a valid one. */
    fun magnetKey(uri: String): String?
}

/** [TorrentEngine] over [NativeTorrent]. The library must be loaded first. */
class JniTorrentEngine : TorrentEngine {
    @Volatile
    private var session = 0L
    private var buffer: ByteBuffer = ByteBuffer.allocateDirect(256 * 1024)
    private var bytes = ByteArray(buffer.capacity())

    private fun s(): Long = session.also { if (it == 0L) throw TorrentEngineException(NativeTorrent.E_INVALID, "session closed") }

    private fun check(rc: Int): Int {
        if (rc < 0) throw TorrentEngineException(rc, NativeTorrent.lastError())
        return rc
    }

    private fun key(k: String?): String = k ?: throw TorrentEngineException(errorCode(), NativeTorrent.lastError())

    /** The add calls return null on any failure; the message tells an existing torrent apart. */
    private fun errorCode(): Int =
        if (NativeTorrent.lastError().startsWith("already added")) NativeTorrent.E_EXISTS else NativeTorrent.E_FAILED

    override fun open(settings: String, state: ByteArray?) {
        check(session == 0L) { "already open" }
        val s = NativeTorrent.sessionNew(settings, state)
        if (s == 0L) throw TorrentEngineException(NativeTorrent.E_FAILED, NativeTorrent.lastError())
        session = s
        Logger.info("Torrent", "Session started: ${NativeTorrent.version()}")
    }

    override fun applySettings(settings: String) { check(NativeTorrent.sessionApply(s(), settings)) }
    override fun state(): ByteArray? = NativeTorrent.sessionState(s())
    override fun listenPort(): Int = NativeTorrent.listenPort(s()).coerceAtLeast(0)

    override fun addTorrent(torrent: ByteArray?, resume: ByteArray?, savePath: String?, priorities: ByteArray?, flags: Int) =
        key(NativeTorrent.addTorrent(s(), torrent, resume, savePath, priorities, flags))

    override fun addMagnet(uri: String, resume: ByteArray?, savePath: String?, flags: Int) =
        key(NativeTorrent.addMagnet(s(), uri, resume, savePath, flags))

    override fun pause(key: String) { check(NativeTorrent.pause(s(), key)) }
    override fun resume(key: String) { check(NativeTorrent.resume(s(), key)) }
    override fun remove(key: String, deleteFiles: Boolean) { check(NativeTorrent.remove(s(), key, deleteFiles)) }
    override fun recheck(key: String) { check(NativeTorrent.recheck(s(), key)) }
    override fun reannounce(key: String) { check(NativeTorrent.reannounce(s(), key)) }
    override fun setFilePriorities(key: String, priorities: ByteArray) { check(NativeTorrent.setFilePriorities(s(), key, priorities)) }
    override fun setFlags(key: String, set: Int, clear: Int) { check(NativeTorrent.setFlags(s(), key, set, clear)) }
    override fun setLimits(key: String, downloadBps: Int, uploadBps: Int, maxConnections: Int) {
        check(NativeTorrent.setLimits(s(), key, downloadBps, uploadBps, maxConnections))
    }

    override fun moveStorage(key: String, path: String) { check(NativeTorrent.moveStorage(s(), key, path)) }
    override fun addTracker(key: String, url: String, tier: Int) { check(NativeTorrent.addTracker(s(), key, url, tier)) }
    override fun requestResume(key: String, flags: Int) { check(NativeTorrent.requestResume(s(), key, flags)) }
    override fun flush(key: String) { check(NativeTorrent.flush(s(), key)) }
    override fun requestResumeAll(flags: Int): Int = check(NativeTorrent.requestResumeAll(s(), flags))
    override fun addPeer(key: String, ip: String, port: Int) { check(NativeTorrent.addPeer(s(), key, ip, port)) }
    override fun postUpdates() { check(NativeTorrent.postUpdates(s())) }
    override fun postSessionStats() { check(NativeTorrent.postSessionStats(s())) }

    override fun poll(timeoutMs: Int): List<TorrentEvent> {
        val n = check(NativeTorrent.pollEvents(s(), timeoutMs, buffer))
        if (n > buffer.capacity()) {
            // One record (resume data with a large info dict) did not fit: grow and take it next time.
            buffer = ByteBuffer.allocateDirect(n * 2)
            bytes = ByteArray(buffer.capacity())
            return emptyList()
        }
        if (n == 0) return emptyList()
        buffer.clear()
        buffer.get(bytes, 0, n)
        return TorrentRecords.events(bytes, n)
    }

    private inline fun <T> read(call: () -> ByteArray?, decode: (ByteArray) -> T): T? = call()?.let(decode)

    override fun info(key: String) = read({ NativeTorrent.torrentInfo(s(), key) }, TorrentRecords::info)
    override fun metadata(key: String): ByteArray? = NativeTorrent.metadata(s(), key)
    override fun pieces(key: String) = read({ NativeTorrent.pieces(s(), key) }, TorrentRecords::pieces)
    override fun peers(key: String) = read({ NativeTorrent.peers(s(), key) }, TorrentRecords::peers).orEmpty()
    override fun trackers(key: String) = read({ NativeTorrent.trackers(s(), key) }, TorrentRecords::trackers).orEmpty()

    override fun close() {
        val s = session
        session = 0L
        if (s != 0L) NativeTorrent.sessionFree(s)
    }

    override fun parseTorrent(torrent: ByteArray): TorrentInfo? =
        NativeTorrent.parseTorrent(torrent)?.let { runCatching { TorrentRecords.info(it) }.getOrNull() }

    override fun magnetKey(uri: String): String? = NativeTorrent.magnetHash(uri)
}
