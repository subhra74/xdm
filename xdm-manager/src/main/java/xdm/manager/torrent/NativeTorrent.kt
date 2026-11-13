// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager.torrent

import java.nio.ByteBuffer

/**
 * JNI binding of the native torrent library (`xt_jni.cpp` in the separate libtorrent-builds repo): one function per C API call,
 * same arguments, same return conventions (int: >= 0 success, < 0 an `XT_E_*` code with [lastError]; a key or a
 * byte array, or null on failure). Load the library ([PlatformHooks.loadTorrentLibrary]) before the first call.
 *
 * Use it through [JniTorrentEngine]; nothing else should call it directly.
 */
object NativeTorrent {
    /** The C API version this binding is written for (`XT_ABI_VERSION`). */
    const val ABI = 4

    /**
     * Whether the loaded library speaks [ABI]: `version()` starts with "xdmtorrent <abi> ". Call right after
     * loading; a library of another version would fail later on a missing or changed JNI function.
     */
    fun abiMatches(): Boolean = runCatching { version().startsWith("xdmtorrent $ABI ") }.getOrDefault(false)

    const val E_INVALID = -1
    const val E_NOT_FOUND = -2
    const val E_EXISTS = -3
    const val E_FAILED = -4
    const val E_NO_META = -5

    @JvmStatic external fun version(): String
    @JvmStatic external fun lastError(): String

    @JvmStatic external fun sessionNew(settings: String, state: ByteArray?): Long
    @JvmStatic external fun sessionState(session: Long): ByteArray?
    @JvmStatic external fun postSessionStats(session: Long): Int
    @JvmStatic external fun sessionApply(session: Long, settings: String): Int
    @JvmStatic external fun sessionPause(session: Long): Int
    @JvmStatic external fun sessionResume(session: Long): Int
    @JvmStatic external fun listenPort(session: Long): Int
    @JvmStatic external fun postUpdates(session: Long): Int
    @JvmStatic external fun requestResumeAll(session: Long, flags: Int): Int
    @JvmStatic external fun sessionFree(session: Long)

    @JvmStatic external fun addTorrent(
        session: Long, torrent: ByteArray?, resume: ByteArray?, savePath: String?, priorities: ByteArray?, flags: Int,
    ): String?

    @JvmStatic external fun addMagnet(session: Long, uri: String, resume: ByteArray?, savePath: String?, flags: Int): String?
    @JvmStatic external fun magnetHash(uri: String): String?

    @JvmStatic external fun pause(session: Long, key: String): Int
    @JvmStatic external fun resume(session: Long, key: String): Int
    @JvmStatic external fun recheck(session: Long, key: String): Int
    @JvmStatic external fun reannounce(session: Long, key: String): Int
    @JvmStatic external fun flush(session: Long, key: String): Int
    @JvmStatic external fun remove(session: Long, key: String, deleteFiles: Boolean): Int
    @JvmStatic external fun setFilePriorities(session: Long, key: String, priorities: ByteArray): Int
    @JvmStatic external fun setFlags(session: Long, key: String, set: Int, clear: Int): Int
    @JvmStatic external fun setLimits(session: Long, key: String, down: Int, up: Int, maxConnections: Int): Int
    @JvmStatic external fun addTracker(session: Long, key: String, url: String, tier: Int): Int
    @JvmStatic external fun moveStorage(session: Long, key: String, path: String): Int
    @JvmStatic external fun requestResume(session: Long, key: String, flags: Int): Int
    @JvmStatic external fun addPeer(session: Long, key: String, ip: String, port: Int): Int

    /** [buffer] must be direct. Returns bytes written, 0 on timeout, or the size needed (> capacity). */
    @JvmStatic external fun pollEvents(session: Long, timeoutMs: Int, buffer: ByteBuffer): Int
    @JvmStatic external fun parseTorrent(torrent: ByteArray): ByteArray?
    @JvmStatic external fun torrentInfo(session: Long, key: String): ByteArray?
    @JvmStatic external fun metadata(session: Long, key: String): ByteArray?
    @JvmStatic external fun pieces(session: Long, key: String): ByteArray?
    @JvmStatic external fun peers(session: Long, key: String): ByteArray?
    @JvmStatic external fun trackers(session: Long, key: String): ByteArray?

    /** Tests only: a .torrent (v1+v2) of the file or folder at [path]; [pieceSize] 0 picks one. */
    @JvmStatic external fun testMakeTorrent(path: String, pieceSize: Int): ByteArray?
}
