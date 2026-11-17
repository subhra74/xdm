// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.downloaders.web.streaming.downloader

import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.PoolingHttpClient
import xdm.core.network.http.Range
import xdm.core.network.http.isTlsVerificationError
import xdm.core.util.DownloadClock
import xdm.core.util.Logger
import xdm.core.util.PartRetry
import xdm.core.util.RetryDecision
import xdm.core.util.RetryPolicy
import xdm.core.util.Trouble
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Downloads one segment. A failed request is asked again by [RetryPolicy]'s rules: while the download as a
 * whole receives data, every kind of answer (a busy server, a refusal, a missing segment) only makes this
 * segment wait and ask again, so a server that limits connections doesn't fail it.
 */
class StreamingChunkRetriever(
    private val piece: StreamingChunk,
    private val httpClient: PoolingHttpClient,
    private val stopFlag: AtomicBoolean,
    private val headers: HeaderMap?,
    private val cookie: String?,
    private val tempDir: String,
    private val progressCallback: (StreamingChunk, Long) -> Unit,
    private val fileNameCallback: (StreamingChunk) -> String,
    /** Network errors in a row while the download is stalled before the segment fails. */
    private val maxRetries: Int = Int.MAX_VALUE,
    /** When the download last received data, shared by all its segments. */
    private val clock: DownloadClock = DownloadClock(),
    /** Called with the wall-clock time a wait ends when this segment starts waiting, and with 0 when it stops. */
    private val waitCallback: (StreamingChunk, Long) -> Unit = { _, _ -> },
    private val completionCallback: (StreamingChunk) -> Unit,
) : Runnable {
    private var fileHandle = AtomicReference<RandomAccessFile?>()

    override fun run() {
        val buffer = ByteArray(256 * 1024)
        val retry = PartRetry(clock, maxRetries)
        // After a 416 to a resumed request: ask for the whole segment again.
        var fromStart = false
        // Ready (or Failed, from an earlier run) until a thread takes it: the progress window lists the live ones.
        piece.status.updateAndGet { if (it == ChunkStatus.Finished) it else ChunkStatus.Downloading }
        try {
            while (!stopFlag.get()) {
                if (piece.status.get() == ChunkStatus.Finished) return
                val downloadedBefore = piece.downloaded.get()
                // byteRange is (offset, length) per the HLS/DASH parsers; an HTTP Range needs the
                // absolute inclusive end (offset + length - 1), not the raw length. Passing the
                // length straight through requested "bytes=offset-length", corrupting every
                // single-file byte-range segment. A null byteRange means "whole resource".
                val (offset, absEnd) = piece.byteRange?.let { (off, len) -> off to (off + len - 1) }
                    ?: (0L to null)
                val realRange = Range(offset + if (fromStart) 0 else downloadedBefore, absEnd)
                val decision: RetryDecision = try {
                    httpClient.getResponse(piece.url, headers, cookie, realRange).getOrThrow().use { response ->
                        if (stopFlag.get()) return
                        val code: Int = response.statusCode
                        if (code != 200 && code != 206) {
                            if (code == 416 && piece.byteRange == null && downloadedBefore > 0 && !fromStart) {
                                // The rest asked for doesn't exist: start the segment over.
                                fromStart = true
                                return@use RetryDecision.Wait(0)
                            }
                            val retryAfter = RetryPolicy.retryAfterMs(response.getHeader("Retry-After"))
                            val trouble = RetryPolicy.classify(
                                code, response.getHeader("WWW-Authenticate"), retryAfter != null, notFoundIsRefusal = true
                            ) ?: return fail(DownloadError.InvalidResponse, "answered $code")
                            return@use retry.next(trouble, retryAfter).also { logRetry(it, code.toString()) }
                        }
                        // A byte-range segment must come as that range: a 200 is the whole file it is cut from.
                        if (piece.byteRange != null && code != 206) {
                            return fail(DownloadError.ResumeNotSupported, "server ignored the byte range ($code)")
                        }
                        // The whole segment again (a 200 to a resumed request, or asked from the start):
                        // write it from byte 0, counting only the bytes beyond what was already counted.
                        val writeFrom = if (fromStart || (code == 200 && downloadedBefore > 0)) 0L else downloadedBefore
                        response.contentLength?.let {
                            // On a resumed segment the 206 reports what is left, not the whole segment.
                            piece.length.set(writeFrom + it)
                        }
                        response.contentType?.let { piece.contentType = it }
                        val tempFile = File(fileNameCallback(piece))
                        RandomAccessFile(tempFile, "rw").use { raf ->
                            this.piece.fileHandle.set(raf)
                            this.fileHandle.set(raf)
                            response.inputStream.use { res ->
                                var pos = writeFrom
                                raf.seek(pos)
                                if (writeFrom == 0L) raf.setLength(0)
                                while (!stopFlag.get()) {
                                    val x: Int = res.read(buffer)
                                    if (x == -1) {
                                        piece.status.set(ChunkStatus.Finished)
                                        piece.error.set(null)
                                        return
                                    }
                                    try {
                                        raf.write(buffer, 0, x)
                                    } catch (io: IOException) {
                                        Logger.error("XDM", "Unable to write to file", io)
                                        return fail(DownloadError.DiskSpaceError, "write failed")
                                    }
                                    pos += x
                                    retry.onData()
                                    clock.onData()
                                    val counted = piece.downloaded.get()
                                    if (pos > counted) {
                                        piece.downloaded.set(pos)
                                        progressCallback(this.piece, pos - counted)
                                    }
                                }
                            }
                        }
                        return
                    }
                } catch (ex: IOException) {
                    if (stopFlag.get()) return
                    if (isTlsVerificationError(ex)) return fail(DownloadError.TlsError, "TLS verification failed")
                    if (piece.downloaded.get() > downloadedBefore) retry.onData()
                    retry.next(Trouble.Network).also { logRetry(it, ex.message ?: ex.javaClass.simpleName) }
                }
                when (decision) {
                    is RetryDecision.GiveUp -> return fail(decision.error, "gave up")
                    is RetryDecision.Wait -> if (decision.ms > 0) {
                        waitCallback(piece, System.currentTimeMillis() + decision.ms)
                        try {
                            if (!RetryPolicy.sleep(decision.ms) { stopFlag.get() }) return
                        } finally {
                            waitCallback(piece, 0)
                        }
                    }
                }
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (ex: Exception) {
            Logger.error("XDM", "Segment ${piece.sequence} failed", ex)
        } finally {
            this.fileHandle.set(null)
            // Stopped (or an unexpected error) before finishing or failing: no thread has it any more, and
            // `.state` must not save it as in progress, or a resumed download would list it as live.
            piece.status.compareAndSet(ChunkStatus.Downloading, ChunkStatus.Ready)
            completionCallback(this.piece)
        }
    }

    private fun fail(error: DownloadError, why: String) {
        Logger.error("XDM", "Segment ${piece.sequence} failed: $why, $error")
        piece.status.set(ChunkStatus.Failed)
        piece.error.set(error)
    }

    private fun logRetry(decision: RetryDecision, what: String) {
        if (decision is RetryDecision.Wait) {
            Logger.info("XDM", "Segment ${piece.sequence}: $what, again in ${decision.ms} ms")
        }
    }

    fun stop() {
        try {
            this.fileHandle.get()?.close()
        } catch (ex: Exception) {
            // Do nothing
        }
        this.fileHandle.set(null)
    }
}
