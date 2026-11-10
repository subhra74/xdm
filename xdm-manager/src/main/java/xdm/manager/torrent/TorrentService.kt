// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager.torrent

import xdm.core.util.AtomicIO
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Receives the events of one torrent, on the service's event thread. Must not block. */
interface TorrentListener {
    fun onEvent(event: TorrentEvent)

    /** A deferred add ([TorrentService.whenFree]) failed. */
    fun onAddFailed(error: Exception) {}
}

/** A torrent's live view ([TorrentService.details]); parts libtorrent could not give are null or empty. */
class TorrentDetails(
    val status: TorrentStatus?,
    val info: TorrentInfo?,
    val pieces: PieceMap?,
    val peers: List<TorrentPeer>,
    val trackers: List<TorrentTracker>,
)

/** A pending metadata fetch ([TorrentService.fetchMetadata]). */
interface MetadataFetch {
    fun cancel()
}

/**
 * The one BitTorrent session of the process and its event thread.
 *
 * - Started lazily ([start]): the first torrent that runs, or [warmUp] when a magnet or .torrent reaches the
 *   app. Once started it runs until [shutdown] (app exit), so the DHT routing table stays warm. Its state
 *   (DHT table, listen port) is saved to `torrent-session.dat` on exit and loaded on the next start.
 * - Events are polled on one thread and handed to the torrent's [TorrentListener] by key. Resume data is
 *   written to `<id>.resume` here, before the listener sees the event.
 * - A torrent that is being removed cannot be added again until libtorrent confirms the removal (it would
 *   get the old handle back): [whenFree] runs an add right away or once that confirmation arrives.
 * - Resume data of modified torrents is requested every [resumeIntervalMs] (and by the tasks on pause,
 *   finish and exit).
 */
class TorrentService(
    private val configDir: String,
    engineFactory: () -> TorrentEngine?,
    /** The session settings; [listenPort] is non-null only for a first start without saved state. */
    private val settings: (listenPort: Int?) -> String,
    private val resumeIntervalMs: Long = 60 * 60 * 1000L,
    /** True keeps the session open until exit; false closes it once no torrent used it for [idleMs]. */
    private val keepOpen: () -> Boolean = { false },
    private val idleMs: Long = 5 * 60 * 1000L,
    /** Flags every torrent is added with on top of its own ([TorrentFlags.DISABLE_PEX] when PeX is off). */
    val addFlags: () -> Int = { 0 },
) {
    /** When a torrent last started the session or joined it; the idle close counts from here. */
    @Volatile
    private var lastActivity = System.currentTimeMillis()

    private val created: TorrentEngine? by lazy {
        runCatching(engineFactory).onFailure { Logger.error("Torrent", "Torrent engine unavailable", it) }.getOrNull()
    }

    /** Whether torrents can be offered at all (the native library loaded). */
    val available: Boolean get() = created != null

    @Volatile
    private var engine: TorrentEngine? = null
    @Volatile
    private var running = false
    private var thread: Thread? = null
    private val lock = Any()

    private val listeners = ConcurrentHashMap<String, TorrentListener>()
    /** key -> download id, for writing resume data. */
    private val ids = ConcurrentHashMap<String, Long>()

    /** Keys removed from the session whose removal libtorrent has not confirmed yet; value: deleting files. */
    private val removing = ConcurrentHashMap<String, Boolean>()
    private val deferred = ConcurrentHashMap<String, MutableList<() -> Unit>>()
    private val resumeAnswers = AtomicInteger()
    private val loggedPort = AtomicInteger()
    /** The latest STATUS of each torrent in the session, for [details]. */
    private val statuses = ConcurrentHashMap<String, TorrentStatus>()

    @Volatile
    var dhtNodes: Long = 0
        private set

    val started: Boolean get() = engine != null

    /** The running engine, starting the session if needed. Throws when it cannot be started. */
    fun start(): TorrentEngine {
        lastActivity = System.currentTimeMillis()
        engine?.let { return it }
        synchronized(lock) {
            engine?.let { return it }
            val e = created ?: throw IllegalStateException("The torrent library is not available")
            val state = if (AtomicIO.exists(STATE_FILE, configDir))
                AtomicIO.readTransacted(STATE_FILE, configDir) { it.readBytes() }.getOrNull() else null
            val port = if (state == null) TorrentSettings.randomPort() else null
            e.open(settings(port), state)
            engine = e
            running = true
            thread = Thread(::eventLoop, "torrent-events").apply {
                isDaemon = true
                start()
            }
            Logger.info("Torrent", "Session started (saved state: ${state != null}, new port: $port)")
            return e
        }
    }

    /** Starts the session in the background, so the DHT bootstrap overlaps with the user picking files. */
    fun warmUp() {
        if (!available || started) return
        Thread({ runCatching { start() }.onFailure { Logger.error("Torrent", "Warm-up failed", it) } }, "torrent-warmup")
            .apply { isDaemon = true }.start()
    }

    fun applySettings(lines: String) {
        engine?.let { e -> runCatching { e.applySettings(lines) }.onFailure { Logger.error("Torrent", "Settings", it) } }
    }

    /** Routes the events of [key] to [listener]; [id] is the download whose `<id>.resume` its resume data goes to. */
    fun attach(key: String, id: Long?, listener: TorrentListener) {
        lastActivity = System.currentTimeMillis()
        listeners[key] = listener
        if (id != null) ids[key] = id else ids.remove(key)
    }

    fun detach(key: String, listener: TorrentListener) {
        if (listeners.remove(key, listener)) ids.remove(key)
    }

    fun isAttached(key: String) = listeners.containsKey(key)

    /**
     * Runs [add] now, or once a pending removal of [key] is confirmed. A failure goes to [listener]'s
     * [TorrentListener.onAddFailed]. Returns false when it was deferred.
     */
    fun whenFree(key: String, listener: TorrentListener?, add: (TorrentEngine) -> Unit): Boolean {
        val e = start()
        val action: () -> Unit = {
            try {
                add(e)
            } catch (ex: Exception) {
                Logger.error("Torrent", "Adding $key failed", ex)
                listener?.onAddFailed(ex)
            }
        }
        synchronized(deferred) {
            if (removing.containsKey(key)) {
                deferred.getOrPut(key) { ArrayList() }.add(action)
                return false
            }
        }
        action()
        return true
    }

    /** Drops adds still waiting for [key] (the download was stopped before it could be added). */
    fun cancelDeferred(key: String) {
        synchronized(deferred) { deferred.remove(key) }
    }

    /** Removes [key] from the session; adds for it wait until libtorrent confirms ([whenFree]). */
    fun remove(key: String, deleteFiles: Boolean) {
        val e = engine ?: return
        synchronized(deferred) { removing[key] = deleteFiles }
        try {
            e.remove(key, deleteFiles)
        } catch (ex: TorrentEngineException) {
            // Not in the session (already gone): nothing to wait for.
            Logger.info("Torrent", "Remove $key: ${ex.message}")
            freed(key)
        }
    }

    private fun freed(key: String) {
        val actions = synchronized(deferred) {
            removing.remove(key)
            deferred.remove(key)
        }
        actions?.forEach { it() }
    }

    /**
     * Deletes the data of a torrent that is not in the session: adds it paused and in upload mode from
     * [resume] or [torrent] into [folder], then removes it with its files (and the hidden `.parts` file).
     * Asynchronous; nothing is reported back.
     */
    fun deleteData(key: String, torrent: ByteArray?, resume: ByteArray?, folder: String) {
        if (torrent == null && resume == null) return
        if (!available) return
        val sink = object : TorrentListener {
            override fun onEvent(event: TorrentEvent) {
                if (event.type == TorrentEventType.DELETED || event.type == TorrentEventType.DELETE_FAILED) {
                    detach(key, this)
                }
            }
        }
        runCatching {
            whenFree(key, sink) { e ->
                attach(key, null, sink)
                e.addTorrent(torrent, resume, folder, null, TorrentFlags.PAUSED or TorrentFlags.UPLOAD_MODE)
                remove(key, deleteFiles = true)
            }
        }.onFailure { Logger.error("Torrent", "Unable to delete the data of $key", it) }
    }

    /**
     * Fetches a magnet's metadata without writing files (upload mode, in a temp folder under [tempRoot] that is
     * deleted afterwards). [done] gets the .torrent bytes, or null when the fetch failed or was cancelled;
     * on the event thread. A torrent already in the session answers from its own metadata.
     */
    fun fetchMetadata(magnet: String, tempRoot: String, done: (ByteArray?) -> Unit): MetadataFetch {
        val e = start()
        val key = e.magnetKey(magnet) ?: run {
            done(null)
            return object : MetadataFetch { override fun cancel() {} }
        }
        if (isAttached(key)) {
            done(runCatching { e.metadata(key) }.getOrNull())
            return object : MetadataFetch { override fun cancel() {} }
        }
        val folder = File(tempRoot, "torrent-meta-$key")
        val fetch = object : TorrentListener, MetadataFetch {
            @Volatile var finished = false

            fun finish(bytes: ByteArray?) {
                synchronized(this) {
                    if (finished) return
                    finished = true
                }
                done(bytes)
                remove(key, deleteFiles = true)
            }

            override fun onEvent(event: TorrentEvent) {
                when (event.type) {
                    TorrentEventType.METADATA -> finish(runCatching { e.metadata(key) }.getOrNull())
                    TorrentEventType.REMOVED, TorrentEventType.DELETED, TorrentEventType.DELETE_FAILED -> {
                        if (event.type != TorrentEventType.REMOVED) {
                            detach(key, this)
                            FileUtils.deleteFolder(folder.absolutePath)
                        }
                    }
                    else -> {}
                }
            }

            override fun onAddFailed(error: Exception) {
                synchronized(this) { finished = true }
                detach(key, this)
                // Created just before the add; nothing in the session will delete it.
                FileUtils.deleteFolder(folder.absolutePath)
                done(null)
            }

            override fun cancel() = finish(null)
        }
        whenFree(key, fetch) { en ->
            attach(key, null, fetch)
            folder.mkdirs()
            en.addMagnet(magnet, null, folder.absolutePath, TorrentFlags.UPLOAD_MODE)
        }
        return fetch
    }

    /**
     * Saves resume data of every modified torrent (waiting up to [timeoutMs] for the answers) and the session
     * state, then closes the session. For app exit.
     */
    fun shutdown(timeoutMs: Long = 30_000) {
        val e = synchronized(lock) { engine } ?: return
        try {
            resumeAnswers.set(0)
            val asked = runCatching { e.requestResumeAll(TorrentFlags.RESUME_IF_MODIFIED) }.getOrDefault(0)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (resumeAnswers.get() < asked && System.currentTimeMillis() < deadline) Thread.sleep(50)
            Logger.info("Torrent", "Shutdown: ${resumeAnswers.get()} of $asked resume data answers")
            runCatching { e.state() }.getOrNull()?.let { state ->
                AtomicIO.writeTransacted(STATE_FILE, configDir) { it.write(state) }
                    .onFailure { Logger.error("Torrent", "Unable to save the session state", it) }
            }
        } finally {
            stopThread()
            synchronized(lock) {
                engine = null
                runCatching { e.close() }.onFailure { Logger.error("Torrent", "Closing the session", it) }
            }
        }
    }

    /**
     * Closes the session when nothing has used it for [idleMs] and it is not kept open: no torrent in it, none
     * being removed or waiting to be added. Saves the state (DHT table, port) first, as at exit, so the next
     * start is warm. Runs on the event thread, which then ends; a later [start] opens a new session.
     */
    private fun closeIfIdle(): Boolean {
        if (keepOpen() || listeners.isNotEmpty() || System.currentTimeMillis() - lastActivity < idleMs) return false
        synchronized(lock) {
            if (keepOpen() || listeners.isNotEmpty() || removing.isNotEmpty() || deferred.isNotEmpty()) return false
            if (System.currentTimeMillis() - lastActivity < idleMs) return false
            val e = engine ?: return false
            // Cleared first: a concurrent start() then waits on the lock and opens a new session after this.
            engine = null
            running = false
            thread = null
            runCatching { e.state() }.getOrNull()?.let { state ->
                AtomicIO.writeTransacted(STATE_FILE, configDir) { it.write(state) }
                    .onFailure { Logger.error("Torrent", "Unable to save the session state", it) }
            }
            runCatching { e.close() }.onFailure { Logger.error("Torrent", "Closing the idle session", it) }
            statuses.clear()
            Logger.info("Torrent", "Session closed: no torrents for ${idleMs / 60_000} min")
            return true
        }
    }

    private fun stopThread() {
        running = false
        val t = thread ?: return
        if (t !== Thread.currentThread()) t.join(5_000)
        thread = null
    }

    private fun eventLoop() {
        var nextUpdate = 0L
        var nextStats = 0L
        var nextResume = System.currentTimeMillis() + resumeIntervalMs
        while (running) {
            val e = engine ?: break
            try {
                val now = System.currentTimeMillis()
                if (now >= nextUpdate) {
                    e.postUpdates()
                    nextUpdate = now + UPDATE_MS
                }
                if (now >= nextStats) {
                    e.postSessionStats()
                    nextStats = now + STATS_MS
                }
                if (now >= nextResume) {
                    e.requestResumeAll(TorrentFlags.RESUME_IF_MODIFIED)
                    nextResume = now + resumeIntervalMs
                }
                for (ev in e.poll(POLL_MS)) dispatch(ev)
                if (closeIfIdle()) break
            } catch (ex: Exception) {
                if (!running) break
                Logger.error("Torrent", "Event loop", ex)
                Thread.sleep(200)
            }
        }
    }

    private fun dispatch(ev: TorrentEvent) {
        when (ev.type) {
            TorrentEventType.SESSION_STATS -> { dhtNodes = ev.dhtNodes; return }
            TorrentEventType.LISTEN_OK -> {
                // One event per local address libtorrent binds: log the port once.
                if (loggedPort.getAndSet(ev.port) != ev.port) Logger.info("Torrent", "Listening on port ${ev.port}")
                return
            }
            TorrentEventType.LISTEN_FAILED -> { Logger.error("Torrent", "Listen failed: ${ev.message}"); return }
            TorrentEventType.LOG -> Logger.info("Torrent", "${ev.key}: ${ev.message}")
            TorrentEventType.RESUME_DATA -> {
                resumeAnswers.incrementAndGet()
                val id = ids[ev.key]
                if (id != null && ev.data != null) {
                    AtomicIO.writeTransacted(resumeFile(id), configDir, ownerOnly = true) { it.write(ev.data) }
                        .onFailure { Logger.error("Torrent", "Unable to save resume data of $id", it) }
                }
            }
            TorrentEventType.RESUME_SKIPPED, TorrentEventType.RESUME_FAILED -> resumeAnswers.incrementAndGet()
            TorrentEventType.STATUS -> ev.status?.let { statuses[ev.key] = it }
            TorrentEventType.REMOVED, TorrentEventType.DELETED -> statuses.remove(ev.key)
            else -> {}
        }
        val listener = listeners[ev.key]
        if (listener != null) {
            try {
                listener.onEvent(ev)
            } catch (ex: Exception) {
                Logger.error("Torrent", "Listener of ${ev.key} failed on ${ev.type}", ex)
            }
        }
        // After the listener, so it sees the removal before an add it deferred runs.
        when (ev.type) {
            TorrentEventType.REMOVED -> if (removing[ev.key] != true) freed(ev.key)
            TorrentEventType.DELETED, TorrentEventType.DELETE_FAILED -> freed(ev.key)
            else -> {}
        }
    }

    /**
     * What the torrent window shows of a torrent in the session: its latest status, files (with progress and
     * priority), pieces, peers and trackers. Null when it is not in the session. Reads from the engine: call
     * it off the EDT, about once a second.
     */
    fun details(key: String): TorrentDetails? {
        val e = engine ?: return null
        if (!isAttached(key)) return null
        return runCatching {
            TorrentDetails(statuses[key], e.info(key), e.pieces(key), e.peers(key), e.trackers(key))
        }.onFailure { Logger.error("Torrent", "Details of $key", it) }.getOrNull()
    }

    /**
     * Adds [tiers] of trackers after a torrent's own (in the session only); false when it is not in it. A private
     * torrent is refused: its tracker must stay its only one.
     */
    fun addTrackers(key: String, tiers: List<List<String>>): Boolean {
        val e = engine ?: return false
        if (!isAttached(key)) return false
        if (runCatching { e.info(key) }.getOrNull()?.isPrivate == true) return false
        val base = runCatching { e.trackers(key) }.getOrDefault(emptyList()).maxOfOrNull { it.tier + 1 } ?: 0
        return runCatching {
            tiers.forEachIndexed { i, tier -> tier.forEach { e.addTracker(key, it, base + i) } }
        }.onFailure { Logger.error("Torrent", "Adding trackers to $key", it) }.isSuccess
    }

    /** Changes the file priorities of a torrent in the session (libtorrent's file order); false when it is not in it. */
    fun setFilePriorities(key: String, priorities: ByteArray): Boolean {
        val e = engine ?: return false
        if (!isAttached(key)) return false
        return runCatching { e.setFilePriorities(key, priorities) }
            .onFailure { Logger.error("Torrent", "Priorities of $key", it) }.isSuccess
    }

    fun readResume(id: Long): ByteArray? = readFile(resumeFile(id))
    fun readTorrent(id: Long): ByteArray? = readFile(torrentFile(id))

    /** The saved metadata of download [id], parsed (needs no session); null when there is none. */
    fun torrentInfo(id: Long): TorrentInfo? = readTorrent(id)?.let(::parse)

    /** Parses a .torrent file without a session; null when it is not one or the library is missing. */
    fun parse(torrent: ByteArray): TorrentInfo? = runCatching { created?.parseTorrent(torrent) }.getOrNull()

    /** The key of a magnet link without a session; null when it is not a valid one or the library is missing. */
    fun magnetKey(uri: String): String? = runCatching { created?.magnetKey(uri) }.getOrNull()

    fun saveTorrent(id: Long, bytes: ByteArray): Boolean =
        AtomicIO.writeTransacted(torrentFile(id), configDir) { it.write(bytes) }
            .onFailure { Logger.error("Torrent", "Unable to save the metadata of $id", it) }.isSuccess

    private fun readFile(name: String): ByteArray? =
        if (AtomicIO.exists(name, configDir)) AtomicIO.readTransacted(name, configDir) { it.readBytes() }.getOrNull() else null

    companion object {
        const val STATE_FILE = "torrent-session.dat"
        private const val POLL_MS = 250
        private const val UPDATE_MS = 1000L
        private const val STATS_MS = 5000L

        fun resumeFile(id: Long) = "$id.resume"
        fun torrentFile(id: Long) = "$id.torrent"

        /** Deletes a download's torrent files from [configDir] (with their backups). */
        fun deleteFiles(id: Long, configDir: String) {
            AtomicIO.delete(resumeFile(id), configDir)
            AtomicIO.delete(torrentFile(id), configDir)
        }
    }
}
