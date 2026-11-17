// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.downloaders.web.batch

import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.HttpResponse
import xdm.core.network.http.PoolingHttpClient
import xdm.core.network.http.Range
import xdm.core.network.http.isTlsVerificationError
import xdm.core.util.DownloadClock
import xdm.core.util.Logger
import xdm.core.util.PartRetry
import xdm.core.util.RetryDecision
import xdm.core.util.RetryPolicy
import xdm.core.util.Trouble
import xdm.core.storage.StorageFile
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** What a [BatchFileRetriever] reports back to its task. */
interface BatchFileListener {
    /** First response for [file] (every attempt): lets the task pick the file's final name. */
    fun onConnected(file: BatchFile, response: HttpResponse)

    fun onBytes(file: BatchFile, count: Long)

    /**
     * [file] is complete in its part file: the task moves it to its final name. Returns null on
     * success, or the error that makes the file (and the whole batch) fail.
     */
    fun onComplete(file: BatchFile): DownloadError?

    /** Always called last, whatever the outcome. */
    fun onDone(file: BatchFile)
}

/**
 * Downloads one item of a batch into its part file in the batch folder, resuming with a Range
 * from what is on disk. Unlike a streaming segment, a server that ignores the Range restarts this one
 * file from zero instead of failing it: a single file is cheap to fetch again.
 *
 * Leaves [BatchFile.status] Finished, Skipped (no content) or Failed (with [BatchFile.error]), or
 * unchanged when stopped.
 */
class BatchFileRetriever(
    private val file: BatchFile,
    private val url: String,
    private val partFile: StorageFile,
    private val httpClient: PoolingHttpClient,
    private val headers: HeaderMap?,
    private val cookie: String?,
    private val stopFlag: AtomicBoolean,
    /** Network errors in a row while the batch is stalled before the file fails. */
    private val maxRetries: Int,
    private val listener: BatchFileListener,
    /** When the batch last received data, shared by all its items; see [RetryPolicy]. */
    private val clock: DownloadClock = DownloadClock(),
) : Runnable {

    override fun run() {
        try {
            if (file.status.get() == ChunkStatus.Finished) return
            file.status.set(ChunkStatus.Downloading)
            file.error.set(null)
            download()
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (ex: Exception) {
            Logger.error("XDM", "Batch item ${file.index} failed", ex)
            if (!stopFlag.get()) fail(DownloadError.InternalError)
        } finally {
            // A stop leaves the file resumable: it is picked up again on the next start.
            if (stopFlag.get() && file.status.get() == ChunkStatus.Downloading) {
                file.status.set(ChunkStatus.Ready)
            }
            listener.onDone(file)
        }
    }

    private fun fail(error: DownloadError) {
        file.error.set(error)
        file.status.set(ChunkStatus.Failed)
    }

    private fun download() {
        val retry = PartRetry(clock, maxRetries)
        while (!stopFlag.get()) {
            // Only bytes that are both recorded and on disk count: the state is saved every few
            // seconds, the file is written continuously, and either can be ahead after a crash.
            val onDisk = partFile.length().coerceAtLeast(0L)
            if (file.downloaded.get() != onDisk) file.downloaded.set(minOf(file.downloaded.get(), onDisk))
            val downloadedBefore = file.downloaded.get()
            val decision: RetryDecision = try {
                val range = if (downloadedBefore > 0) Range(downloadedBefore, null) else null
                // Null once the body is in; a decision when the answer was an error.
                val failed: RetryDecision? = httpClient.getResponse(url, headers, cookie, range).getOrThrow().use { response ->
                    if (stopFlag.get()) return
                    val code = response.statusCode
                    // No content (often an analytics beacon the page scan picked up): nothing to save.
                    if ((code == 204 || code == 205) && downloadedBefore == 0L) {
                        Logger.info("XDM", "Batch item ${file.index} skipped: $code, no content")
                        partFile.delete()
                        file.status.set(ChunkStatus.Skipped)
                        return
                    }
                    if (code != 200 && code != 206) {
                        val retryAfter = RetryPolicy.retryAfterMs(response.getHeader("Retry-After"))
                        val trouble = RetryPolicy.classify(code, response.getHeader("WWW-Authenticate"), retryAfter != null)
                        if (trouble == null) {
                            Logger.error("XDM", "Batch item ${file.index} failed - $code")
                            fail(DownloadError.InvalidResponse)
                            return
                        }
                        return@use retry.next(trouble, retryAfter).also {
                            if (it is RetryDecision.Wait) Logger.info("XDM", "Batch item ${file.index}: $code, again in ${it.ms} ms")
                        }
                    }
                    // A 200 to a Range request is the whole file again: start it over.
                    val offset = if (code == 206 && range != null) downloadedBefore else 0L
                    file.downloaded.set(offset)
                    file.length.set(response.contentLength?.let { offset + it } ?: -1L)
                    listener.onConnected(file, response)
                    if (!copy(response, offset)) return
                    null
                }
                if (failed != null) {
                    failed
                } else {
                    if (file.status.get() == ChunkStatus.Downloading) {
                        val error = listener.onComplete(file)
                        if (error != null) fail(error) else file.status.set(ChunkStatus.Finished)
                    }
                    return
                }
            } catch (ex: IOException) {
                if (stopFlag.get()) return
                Logger.error("XDM", "Error downloading batch item ${file.index}: ${ex.message}")
                if (isTlsVerificationError(ex)) {
                    fail(DownloadError.TlsError)
                    return
                }
                if (file.downloaded.get() > downloadedBefore) retry.onData()
                retry.next(Trouble.Network)
            }
            when (decision) {
                is RetryDecision.GiveUp -> {
                    Logger.error("XDM", "Batch item ${file.index} failed: ${decision.error}")
                    // A refusal is only an expiry if this file had started; otherwise the link never worked.
                    fail(if (decision.error == DownloadError.LinkExpired && file.downloaded.get() == 0L) DownloadError.InvalidResponse else decision.error)
                    return
                }
                is RetryDecision.Wait -> if (!RetryPolicy.sleep(decision.ms) { stopFlag.get() }) return
            }
        }
    }

    /**
     * Writes the body to the part file from [offset]. Returns true when the whole body arrived, false
     * when stopped or the write failed (the file is then failed). A body that ends short of its
     * Content-Length throws, so the caller retries from where it stopped.
     */
    private fun copy(response: HttpResponse, offset: Long): Boolean {
        val buffer = BUFFER.get()
        partFile.open(create = true).use { raf ->
            try {
                raf.setLength(offset)
                raf.seek(offset)
            } catch (io: IOException) {
                Logger.error("XDM", "Unable to open part file $partFile", io)
                fail(DownloadError.OutputWriteError)
                return false
            }
            response.inputStream.use { input ->
                while (!stopFlag.get()) {
                    val n = input.read(buffer)
                    if (n == -1) {
                        val length = file.length.get()
                        if (length >= 0 && file.downloaded.get() < length) {
                            throw IOException("Body ended at ${file.downloaded.get()} of $length")
                        }
                        if (length < 0) file.length.set(file.downloaded.get())
                        return true
                    }
                    try {
                        raf.write(buffer, 0, n)
                    } catch (io: IOException) {
                        Logger.error("XDM", "Unable to write to $partFile", io)
                        fail(DownloadError.DiskSpaceError)
                        return false
                    }
                    file.downloaded.addAndGet(n.toLong())
                    clock.onData()
                    listener.onBytes(file, n.toLong())
                }
            }
        }
        return false
    }

    private companion object {
        /** One buffer per worker thread, reused for every file it downloads. */
        val BUFFER: ThreadLocal<ByteArray> = object : ThreadLocal<ByteArray>() {
            override fun initialValue() = ByteArray(256 * 1024)
        }
    }
}
