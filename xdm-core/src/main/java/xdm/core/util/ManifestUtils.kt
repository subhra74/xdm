// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.util


import xdm.core.network.http.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object ManifestUtils {
    /**
     * Largest manifest body accepted, counted as read (after content decoding, so a gzip bomb stops
     * here too). Manifests are fetched in the background for any page that plays media; a real
     * playlist, even a day-long one, is a few megabytes.
     */
    const val MAX_MANIFEST_BYTES = 32L * 1024 * 1024

    /** A body longer than the caller's limit; reported through `onError`. */
    class ManifestTooLargeException(limit: Long) : IOException("Response is larger than $limit bytes")

    fun downloadManifestBytes(
        httpClient: PoolingHttpClient,
        url: String,
        headers: HeaderMap?,
        cookie: String?,
        stopFlag: AtomicBoolean,
        onError: ((Throwable) -> Unit)? = null,
        onHttpError: ((Int) -> Unit)? = null,
        maxBytes: Long = MAX_MANIFEST_BYTES,
    ): ByteArray? {
        downloadManifestAsFile(
            httpClient,
            url,
            headers,
            cookie,
            stopFlag,
            onError,
            onHttpError,
            maxBytes,
        )?.let {
            val tempFile = File(it)
            try {
                return tempFile.readBytes()
            } finally {
                // The manifest has been read; failing to remove the temp file must not discard it.
                if (!tempFile.delete() && tempFile.exists()) {
                    Logger.error("XDM", "Could not delete manifest temp file $tempFile")
                }
            }
        }
        return null
    }

    fun downloadManifestAsFile(
        httpClient: PoolingHttpClient,
        url: String,
        headers: HeaderMap?,
        cookie: String?,
        stopFlag: AtomicBoolean,
        onError: ((Throwable) -> Unit)? = null,
        /** The status of a response that was neither 200 nor 206 (onError is only for exceptions). */
        onHttpError: ((Int) -> Unit)? = null,
        /** A longer body is abandoned and reported to [onError] as a [ManifestTooLargeException]. */
        maxBytes: Long = MAX_MANIFEST_BYTES,
    ): String? {
        try {
            Logger.info("XDM","Downloading manifest: $url")
            // No Range: browsers never send one for a playlist, and some CDNs reject it (400 over HTTP/2).
            httpClient.getResponse(
                url, headers, cookie, null
            ).onSuccess {
                it.use { response ->
                    if (stopFlag.get()) return null
                    val code: Int = response.statusCode
                    Logger.info("Manifest download", "Return code: $code")
                    if (code != 200 && code != 206) {
                        Logger.error("Manifest download failed")
                        onHttpError?.invoke(code)
                        return null
                    }
                    response.contentLength?.let { if (it > maxBytes) throw ManifestTooLargeException(maxBytes) }
                    return fileCopy(response, stopFlag, maxBytes)
                }
            }.onFailure {
                Logger.error("Error downloading manifest", it)
                onError?.invoke(it)
            }
        } catch (ex: IOException) {
            Logger.error("Error downloading manifest", ex)
            onError?.invoke(ex)
        }
        return null
    }

    /**
     * Copies the body to a new temp file and returns its path, or null if [stopFlag] was set before the
     * body ended. A stopped or failed copy deletes the file, so a partial manifest is never parsed or left behind.
     * A body longer than [maxBytes] throws [ManifestTooLargeException].
     */
    private fun fileCopy(response: HttpResponse, stopFlag: AtomicBoolean, maxBytes: Long): String? {
        val file = File.createTempFile(
            UUID.randomUUID().toString(),
            ".m3u8"
        )
        var complete = false
        var total = 0L
        try {
            FileOutputStream(file).use { w ->
                val b = ByteArray(8192)
                response.inputStream.use { inputStream ->
                    while (!stopFlag.get()) {
                        val x: Int = inputStream.read(b)
                        if (x == -1) {
                            complete = true
                            break
                        }
                        total += x
                        if (total > maxBytes) throw ManifestTooLargeException(maxBytes)
                        w.write(b, 0, x)
                    }
                }
            }
        } finally {
            if (!complete && !file.delete() && file.exists()) {
                Logger.error("XDM", "Could not delete partial manifest $file")
            }
        }
        return if (complete) file.absolutePath else null
    }
}