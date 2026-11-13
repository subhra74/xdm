// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager.torrent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [TorrentEngine] without the native library. A .torrent here is just its key in ASCII; a magnet's key is
 * what follows `btih:`. It answers like libtorrent does: resume requests with RESUME_DATA, pause with PAUSED,
 * remove with REMOVED (and DELETED when deleting), all through [poll]. Tests add other events with [emit].
 */
class FakeTorrentEngine : TorrentEngine {
    data class Added(val key: String, val torrent: ByteArray?, val resume: ByteArray?, val savePath: String?,
                     val priorities: ByteArray?, val flags: Int, val magnet: String? = null)

    val opened = AtomicInteger()
    var openedWith: Pair<String, ByteArray?>? = null
    val added = CopyOnWriteArrayList<Added>()
    val calls = CopyOnWriteArrayList<String>()
    val inSession: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Removed, but the removal has not been reported yet: libtorrent would hand the old torrent back. */
    private val removing = ConcurrentHashMap<String, Boolean>()
    private val events = LinkedBlockingQueue<TorrentEvent>()
    var closed = false
    var savedState: ByteArray? = "state".toByteArray()

    fun emit(event: TorrentEvent) = events.add(event)

    fun status(key: String, wanted: Long, done: Long, finished: Boolean = false, upload: Long = 0, name: String = "pack") =
        emit(TorrentEvent(TorrentEventType.STATUS, key, status = TorrentStatus(
            key = key, state = if (finished) TorrentState.FINISHED else TorrentState.DOWNLOADING,
            flags = TorrentStatus.SF_HAS_METADATA or (if (finished) TorrentStatus.SF_FINISHED else 0),
            totalWanted = wanted, totalWantedDone = done, totalDone = done, allTimeDownload = done,
            allTimeUpload = upload, downloadRate = 1000, uploadRate = 50, peers = 3, seeds = 2, complete = -1,
            incomplete = -1, connections = 3, progressPpm = 0, addedTime = 0, completedTime = 0, activeSeconds = 0,
            seedingSeconds = 0, availabilityMilli = -1, numPieces = 10, piecesDone = 5, name = name, savePath = "",
            error = "",
        )))

    override fun open(settings: String, state: ByteArray?) {
        opened.incrementAndGet()
        openedWith = settings to state
    }

    override fun applySettings(settings: String) { calls.add("apply $settings") }
    override fun state(): ByteArray? = savedState
    override fun listenPort() = 6881

    override fun addTorrent(torrent: ByteArray?, resume: ByteArray?, savePath: String?, priorities: ByteArray?, flags: Int): String {
        val key = when {
            torrent != null -> String(torrent, Charsets.US_ASCII)
            resume != null -> String(resume, Charsets.US_ASCII).removePrefix("resume-")
            else -> throw TorrentEngineException(NativeTorrent.E_INVALID, "nothing to add")
        }
        check(!removing.containsKey(key)) { "$key added while it is still being removed" }
        if (!inSession.add(key)) throw TorrentEngineException(NativeTorrent.E_EXISTS, "already added: $key")
        added.add(Added(key, torrent, resume, savePath, priorities, flags))
        return key
    }

    override fun addMagnet(uri: String, resume: ByteArray?, savePath: String?, flags: Int): String {
        val key = magnetKey(uri) ?: throw TorrentEngineException(NativeTorrent.E_INVALID, "bad magnet")
        check(!removing.containsKey(key)) { "$key added while it is still being removed" }
        if (!inSession.add(key)) throw TorrentEngineException(NativeTorrent.E_EXISTS, "already added: $key")
        added.add(Added(key, null, resume, savePath, null, flags, magnet = uri))
        return key
    }

    private fun known(key: String) {
        if (key !in inSession) throw TorrentEngineException(NativeTorrent.E_NOT_FOUND, "no torrent $key")
    }

    override fun pause(key: String) {
        known(key); calls.add("pause $key"); emit(TorrentEvent(TorrentEventType.PAUSED, key))
    }

    override fun resume(key: String) { known(key); calls.add("resume $key") }

    override fun remove(key: String, deleteFiles: Boolean) {
        known(key)
        inSession.remove(key)
        removing[key] = deleteFiles
        calls.add("remove $key $deleteFiles")
        emit(TorrentEvent(TorrentEventType.REMOVED, key))
        if (deleteFiles) emit(TorrentEvent(TorrentEventType.DELETED, key))
    }

    override fun recheck(key: String) { known(key); calls.add("recheck $key") }
    override fun reannounce(key: String) { known(key) }
    override fun setFilePriorities(key: String, priorities: ByteArray) {
        known(key)
        calls.add("priorities $key ${priorities.joinToString(",")}")
    }
    override fun setFlags(key: String, set: Int, clear: Int) { known(key) }
    override fun setLimits(key: String, downloadBps: Int, uploadBps: Int, maxConnections: Int) { known(key) }
    override fun moveStorage(key: String, path: String) { known(key) }
    override fun addTracker(key: String, url: String, tier: Int) {
        known(key)
        calls.add("tracker $key $url $tier")
    }

    override fun requestResume(key: String, flags: Int) {
        known(key)
        calls.add("resume-data $key")
        emit(TorrentEvent(TorrentEventType.RESUME_DATA, key, data = "resume-$key".toByteArray()))
    }

    override fun flush(key: String) {
        known(key)
        calls.add("flush $key")
        emit(TorrentEvent(TorrentEventType.FLUSHED, key))
    }

    override fun requestResumeAll(flags: Int): Int {
        inSession.forEach { emit(TorrentEvent(TorrentEventType.RESUME_DATA, it, data = "resume-$it".toByteArray())) }
        return inSession.size
    }

    override fun addPeer(key: String, ip: String, port: Int) { known(key) }
    override fun postUpdates() {}
    override fun postSessionStats() {}

    override fun poll(timeoutMs: Int): List<TorrentEvent> {
        val first = events.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return emptyList()
        val out = arrayListOf(first)
        events.drainTo(out)
        for (e in out) {
            if ((e.type == TorrentEventType.REMOVED && removing[e.key] == false) || e.type == TorrentEventType.DELETED)
                removing.remove(e.key)
        }
        return out
    }

    override fun info(key: String): TorrentInfo? = if (key in inSession)
        TorrentInfo(key, "", "pack", 100, 16384, 10, 0, "", "", 0, listOf(TorrentFile(100, 0, 4, 0, "pack/a.bin")))
    else null

    override fun metadata(key: String): ByteArray? = if (key in inSession) key.toByteArray() else null
    override fun pieces(key: String): PieceMap? = null
    override fun peers(key: String) = emptyList<TorrentPeer>()
    override fun trackers(key: String) = emptyList<TorrentTracker>()

    override fun close() { closed = true }

    /** What [parseTorrent] returns for any input. */
    @Volatile var parsed: TorrentInfo? = null

    override fun parseTorrent(torrent: ByteArray): TorrentInfo? = parsed
    override fun magnetKey(uri: String): String? = uri.substringAfter("btih:", "").take(40).takeIf { it.length == 40 }
}
