// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.downloaders.web.http

import xdm.core.network.http.HttpResponse
import xdm.core.network.http.Range
import xdm.core.util.Logger
import java.io.IOException
import kotlin.math.min

/**
 * Fetches the rest of a chunk too small to split on a second connection, into memory, while the chunk's
 * own connection keeps going: whichever gets the bytes first wins. The racer never touches the part file
 * itself; [commit] writes what it fetched, under the download's lock, only if the chunk still needs it.
 *
 * Any trouble (an error, a 200, a wrong length, a stall the read timeout ends) just ends the racer and
 * changes nothing: the chunk's own connection is still there. Not saved; a pause drops it.
 */
internal class TailRacer(
    val chunkId: Long,
    private val context: HttpTaskContext,
    private val graceMs: Long,
    /** Writes `[start, end)` from the buffer if the chunk still needs it; true if it did. Takes the lock itself. */
    private val commit: (start: Long, end: Long, data: ByteArray) -> Boolean,
    /** Called once when the racer's thread ends, whatever the outcome. */
    private val onExit: (TailRacer) -> Unit,
) {
    @Volatile
    private var cancelled = false

    @Volatile
    private var response: HttpResponse? = null

    fun start() {
        try {
            Thread({ run() }, "$THREAD_PREFIX$chunkId").apply { isDaemon = true }.start()
        } catch (e: Throwable) {
            Logger.error("XDM", "Unable to start a tail racer for chunk $chunkId", e)
            onExit(this)
        }
    }

    /** Stops the racer from any thread, aborting a read it is blocked on. */
    fun cancel() {
        cancelled = true
        response?.cancel()
    }

    private fun run() {
        try {
            race()
        } catch (e: Throwable) {
            if (!isCancelled()) Logger.error("XDM", "Tail racer for chunk $chunkId failed", e)
        } finally {
            response = null
            onExit(this)
        }
    }

    private fun race() {
        // The chunk may well finish on its own: give it a moment before opening another connection.
        if (!sleepUnlessCancelled(graceMs)) return
        var start = 0L
        var end = 0L
        context.read {
            val chunk = context.chunks[chunkId] ?: return
            start = chunk.offset + chunk.downloaded.get()
            end = chunk.offset + chunk.length.get()
        }
        val len = end - start
        if (len !in 1..HttpDownloaderTask.RACE_MAX_BYTES) return
        Logger.info("XDM", "Tail racer for chunk $chunkId: fetching $start-${end - 1}")
        val url = context.finalUrl ?: context.url
        val res = context.httpClient.getResponse(url, context.rangeHeaders(bounded = true), context.cookie, Range(start, end - 1))
            .getOrElse {
                Logger.info("XDM", "Tail racer for chunk $chunkId could not connect: $it")
                return
            }
        res.use {
            response = res
            // A cancel that came before [response] was set did not abort it.
            if (isCancelled()) return
            if (res.statusCode != 206 || res.contentLength != len) {
                Logger.info("XDM", "Tail racer for chunk $chunkId: unusable response ${res.statusCode}, ${res.contentLength} bytes")
                return
            }
            val buf = ByteArray(len.toInt())
            var read = 0
            while (read < buf.size) {
                val n = try {
                    res.inputStream.read(buf, read, buf.size - read)
                } catch (e: IOException) {
                    Logger.info("XDM", "Tail racer for chunk $chunkId: read failed: $e")
                    return
                }
                if (n == -1 || isCancelled()) return
                read += n
            }
            if (commit(start, end, buf)) Logger.info("XDM", "Tail racer finished chunk $chunkId")
        }
    }

    /** Over once the racer is cancelled, the download stops, or the chunk is finished or gone. */
    private fun isCancelled(): Boolean {
        if (cancelled || context.stopFlag.get() || context.completed.get()) return true
        context.read {
            val status = context.chunks[chunkId]?.status?.get() ?: return true
            return status == ChunkStatus.Finished || status == ChunkStatus.Cancelled
        }
        return true
    }

    /** False if the racer was cancelled meanwhile. */
    private fun sleepUnlessCancelled(millis: Long): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (!isCancelled()) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return true
            try {
                Thread.sleep(min(left, SLEEP_STEP_MS))
            } catch (e: InterruptedException) {
                return false
            }
        }
        return false
    }

    internal companion object {
        const val THREAD_PREFIX = "xdm-tail-race-"
        const val SLEEP_STEP_MS = 100L
    }
}
