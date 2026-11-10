// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager.torrent

import xdm.core.util.Logger
import java.util.concurrent.ConcurrentHashMap

/** When seeding stops by itself: [ratio] (uploaded over size, <= 0 none) or [minutes] (<= 0 none). */
data class SeedLimits(val ratio: Float, val minutes: Int)

/** What the seeder tells the download manager; called on the event thread. */
interface SeedingListener {
    fun seedingStarted(id: Long)
    fun seedingProgress(id: Long, status: TorrentStatus)

    /** Seeding of [id] ended (limit reached, stopped by hand, or it could not start); the torrent left the session. */
    fun seedingStopped(id: Long)

    /** Seeding of [id] hit a file error (its files were deleted or moved, say) and is stopping. */
    fun seedingFailed(id: Long, message: String) {}
}

/**
 * Seeds finished torrents outside XDM's queue: they take no download slot and do not keep the machine awake.
 * A torrent seeds after it finishes when the settings say so ([adopt]), or when the user starts it ([start]);
 * either way until a limit in [limits] is reached or the user stops it. Limits count from when this seeding
 * began (upload during it over the torrent's size, minutes since it began), so a manual start always seeds.
 * Seeding is never resumed after the app restarts.
 */
class TorrentSeeder(
    private val service: TorrentService,
    private val limits: () -> SeedLimits,
    private val listener: SeedingListener,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private inner class Seed(val id: Long, val key: String) : TorrentListener {
        @Volatile var stopping = false
        @Volatile var running = false
        private var baseUpload = -1L
        private val since = clock()

        override fun onEvent(event: TorrentEvent) {
            when (event.type) {
                TorrentEventType.STATUS -> event.status?.let(::status)
                TorrentEventType.RESUME_DATA, TorrentEventType.RESUME_SKIPPED, TorrentEventType.RESUME_FAILED ->
                    if (stopping) end()
                TorrentEventType.ERROR -> {
                    Logger.error("Torrent", "Seeding $id failed: ${event.message}")
                    listener.seedingFailed(id, event.message)
                    stop()
                }
                else -> {}
            }
        }

        override fun onAddFailed(error: Exception) = end()

        private fun status(s: TorrentStatus) {
            if (stopping) return
            if (!running) {
                running = true
                listener.seedingStarted(id)
            }
            if (baseUpload < 0) baseUpload = s.allTimeUpload
            listener.seedingProgress(id, s)
            val l = limits()
            val size = s.totalWanted.coerceAtLeast(1)
            val ratio = (s.allTimeUpload - baseUpload).toFloat() / size
            val minutes = (clock() - since) / 60_000
            if ((l.ratio > 0 && ratio >= l.ratio) || (l.minutes > 0 && minutes >= l.minutes)) {
                Logger.info("Torrent", "Seeding $id reached its limit (ratio $ratio, $minutes min)")
                stop()
            }
        }

        fun stop() {
            if (stopping) return
            stopping = true
            val e = runCatching { service.start() }.getOrNull()
            try {
                e?.requestResume(key, 0) ?: end()
            } catch (ex: TorrentEngineException) {
                end()
            }
        }

        fun end() {
            if (seeds.remove(id, this)) {
                service.detach(key, this)
                service.remove(key, deleteFiles = false)
                listener.seedingStopped(id)
            }
        }
    }

    private val seeds = ConcurrentHashMap<Long, Seed>()

    fun isSeeding(id: Long) = seeds.containsKey(id)

    /** Takes over a torrent that just finished and is still in the session. */
    fun adopt(id: Long, key: String) {
        val seed = Seed(id, key)
        seeds[id] = seed
        service.attach(key, id, seed)
    }

    /**
     * Starts seeding a finished download from its resume data (no recheck: the data says which pieces are
     * there). Returns false when there is nothing to seed from.
     */
    fun start(id: Long, key: String, folder: String): Boolean {
        if (seeds.containsKey(id)) return true
        val resume = service.readResume(id)
        val torrent = service.readTorrent(id)
        if (resume == null && torrent == null) return false
        val seed = Seed(id, key)
        seeds[id] = seed
        return try {
            service.whenFree(key, seed) { e ->
                service.attach(key, id, seed)
                e.addTorrent(torrent, resume, folder, null, service.addFlags())
                runCatching { e.setLimits(key, 0, 0, TorrentSettings.MAX_CONNECTIONS_PER_TORRENT) }
            }
            true
        } catch (ex: Exception) {
            Logger.error("Torrent", "Unable to start seeding $id", ex)
            seeds.remove(id)
            false
        }
    }

    /** Stops seeding [id]: saves its resume data, then removes it ([SeedingListener.seedingStopped]). */
    fun stop(id: Long) {
        seeds[id]?.let {
            if (!it.running) service.cancelDeferred(it.key)
            it.stop()
        }
    }

    /** Stops seeding [id] at once for a delete, removing the torrent with its files when [deleteFiles]. */
    fun discard(id: Long, deleteFiles: Boolean): Boolean {
        val seed = seeds.remove(id) ?: return false
        service.detach(seed.key, seed)
        service.remove(seed.key, deleteFiles)
        return true
    }
}
