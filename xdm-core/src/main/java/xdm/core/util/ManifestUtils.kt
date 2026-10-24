package xdm.core.util


import xdm.core.network.http.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object ManifestUtils {
    /**
     * The largest manifest accepted, counted after content decoding (so a gzip bomb stops here too).
     * Manifests are fetched in the background for any page that plays media, from servers we don't
     * trust; a real playlist, even a day-long one, is a few megabytes.
     */
    const val MAX_MANIFEST_BYTES = 32L * 1024 * 1024

    /** Thrown when a body is larger than the caller's limit; reported through `onError`. */
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
        credentialsFor: String? = null,
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
            credentialsFor,
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
        /** A longer body is abandoned and reported as a [ManifestTooLargeException]. */
        maxBytes: Long = MAX_MANIFEST_BYTES,
        /** Where [headers] and [cookie] were captured; see [PoolingHttpClient.getResponse]. */
        credentialsFor: String? = null,
    ): String? {
        try {
            Logger.info("XDM","Downloading manifest: $url")
            // No Range: browsers never send one for a playlist, and some CDNs reject it (400 over HTTP/2).
            httpClient.getResponse(
                url, headers, cookie, null, credentialsFor
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
                    response.contentLength?.let { len ->
                        if (len > maxBytes) throw ManifestTooLargeException(maxBytes)
                    }
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
     *
     * The file is created owner-only (`rw-------` on POSIX): a manifest can carry signed URLs, and a key
     * file is the content key itself, so other users of the machine must not read it from the temp folder.
     */
    private fun fileCopy(response: HttpResponse, stopFlag: AtomicBoolean, maxBytes: Long): String? {
        val file = Files.createTempFile(UUID.randomUUID().toString(), ".m3u8").toFile()
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