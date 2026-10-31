// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.checksum

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.PoolingHttpClient
import xdm.core.util.CryptoProviders
import xdm.core.util.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CancellationException

/** The request the file was downloaded with; its headers and cookie go along to the same host only. */
class ChecksumSource(val url: String, val headers: HeaderMap?, val cookie: String?)

/**
 * What a verification ended in. [from] is the checksum URL the hash came from (or failed at),
 * null for a pasted hash.
 */
sealed class ChecksumResult {
    data class Match(val expected: ExpectedChecksum, val from: String? = null) : ChecksumResult()
    data class Mismatch(val expected: ExpectedChecksum, val actualHex: String, val from: String? = null) : ChecksumResult()

    /** No checksum could be had: every URL tried failed, or none listed this file. */
    data class NotFound(val reason: NotFoundReason, val detail: String? = null, val from: String? = null) : ChecksumResult()
}

enum class NotFoundReason {
    /** The input was neither a URL nor a hash. */
    BAD_INPUT,

    /** The checksum URL answered with an HTTP error ([ChecksumResult.NotFound.detail] has the status). */
    HTTP_ERROR,

    /** The checksum URL could not be reached ([ChecksumResult.NotFound.detail] has the message). */
    NETWORK_ERROR,

    /** The checksum file was fetched but holds no usable hash for this file. */
    NO_HASH_FOR_FILE,

    /** None of the conventional checksum URLs exists. */
    NO_SIDECAR,
}

/**
 * Verifies a finished file against a published checksum.
 *
 * Blocking: run it off the UI thread. [cancelled] is polled while fetching and hashing so a
 * closed dialog stops the work promptly; cancelling throws [CancellationException].
 */
class ChecksumVerifier(
    private val client: PoolingHttpClient,
    private val cancelled: () -> Boolean = { false },
) {
    /** Called before each step: fetching a URL, or hashing (with bytes done and total). */
    interface Progress {
        fun fetching(url: String) {}
        fun hashing(algorithm: ChecksumAlgorithm, done: Long, total: Long) {}
    }

    /**
     * Verifies [file] against [input], which is a pasted hash or a checksum URL. [algorithm] null
     * means "Auto": detect it from the hash, and when [probeAuto] is set, try every conventional
     * sidecar of [source] in turn instead of only [input].
     */
    fun verify(
        file: File,
        input: String,
        algorithm: ChecksumAlgorithm?,
        source: ChecksumSource?,
        fileNames: Collection<String>,
        probeAuto: Boolean,
        progress: Progress,
    ): ChecksumResult {
        val text = input.trim()
        ChecksumParser.parseRawHash(text, algorithm)?.let { return compare(file, it, progress) }
        if (!isHttpUrl(text)) return ChecksumResult.NotFound(NotFoundReason.BAD_INPUT)

        val candidates = if (probeAuto && algorithm == null && source != null) {
            SidecarUrls.autoCandidates(source.url).ifEmpty { listOf(text) }
        } else listOf(text)

        val failures = mutableListOf<ChecksumResult.NotFound>()
        for (url in candidates) {
            checkCancelled()
            progress.fetching(url)
            val body = try {
                fetchWithRetry(url, source)
            } catch (e: HttpStatusException) {
                Logger.info(TAG, "$url -> HTTP ${e.status}")
                failures += ChecksumResult.NotFound(NotFoundReason.HTTP_ERROR, e.status.toString(), url)
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.error(TAG, "$url -> failed", e)
                failures += ChecksumResult.NotFound(NotFoundReason.NETWORK_ERROR, e.message ?: e.javaClass.simpleName, url)
                continue
            }
            val expected = ChecksumParser.parse(body, fileNames, algorithm)
            if (expected == null) {
                Logger.info(TAG, "$url -> no ${algorithm?.label ?: ""} hash for $fileNames")
                failures += ChecksumResult.NotFound(NotFoundReason.NO_HASH_FOR_FILE, null, url)
                continue
            }
            Logger.info(TAG, "$url -> ${expected.algorithm.label} ${expected.hex}")
            return when (val result = compare(file, expected, progress)) {
                is ChecksumResult.Match -> result.copy(from = url)
                is ChecksumResult.Mismatch -> result.copy(from = url)
                is ChecksumResult.NotFound -> result
            }
        }
        return mostTelling(failures, probing = candidates.size > 1)
    }

    /**
     * The failure worth showing. A single URL's failure is shown as it is. Probing several, 404s are
     * expected (most candidates do not exist), so they only mean "no checksum file" when nothing
     * else went wrong: a file without this file's hash, a network error or a server error is the
     * real story, and must not be hidden behind the 404s of the candidates after it.
     */
    private fun mostTelling(failures: List<ChecksumResult.NotFound>, probing: Boolean): ChecksumResult.NotFound {
        if (!probing) return failures.firstOrNull() ?: ChecksumResult.NotFound(NotFoundReason.NO_SIDECAR)
        return failures.firstOrNull { it.reason == NotFoundReason.NO_HASH_FOR_FILE }
            ?: failures.firstOrNull { it.reason == NotFoundReason.NETWORK_ERROR }
            ?: failures.firstOrNull { it.reason == NotFoundReason.HTTP_ERROR && it.detail !in MISSING_STATUSES }
            ?: ChecksumResult.NotFound(NotFoundReason.NO_SIDECAR)
    }

    /**
     * [fetch], tried a second time after a network error. A fresh client's first request can fail
     * on its own (a connection the server dropped, a hiccup in the TLS handshake); a status
     * from the server is an answer and is not retried.
     */
    private fun fetchWithRetry(url: String, source: ChecksumSource?): String = try {
        fetch(url, source)
    } catch (e: HttpStatusException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Logger.info(TAG, "$url -> ${e.message ?: e.javaClass.simpleName}, retrying")
        checkCancelled()
        fetch(url, source)
    }

    private fun compare(file: File, expected: ExpectedChecksum, progress: Progress): ChecksumResult {
        val actual = hash(file, expected.algorithm, progress)
        return if (actual.equals(expected.hex, ignoreCase = true)) ChecksumResult.Match(expected)
        else ChecksumResult.Mismatch(expected, actual)
    }

    /** Lower-case hex digest of [file]. */
    fun hash(file: File, algorithm: ChecksumAlgorithm, progress: Progress): String {
        val digest = CryptoProviders.messageDigest(algorithm.javaName)
        val total = file.length()
        var done = 0L
        var lastReport = 0L
        progress.hashing(algorithm, 0, total)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                checkCancelled()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                done += n
                // Reporting every buffer would flood the UI on a fast disk.
                if (done - lastReport >= REPORT_EVERY || done == total) {
                    lastReport = done
                    progress.hashing(algorithm, done, total)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun fetch(url: String, source: ChecksumSource?): String {
        // The download's cookie and headers may be credentials: send them only to the host they came from.
        val sameHost = source != null && hostOf(source.url)?.equals(hostOf(url), ignoreCase = true) == true
        val headers = if (sameHost) source!!.headers?.filterKeys { it.lowercase() !in DROPPED_HEADERS } else null
        val cookie = if (sameHost) source!!.cookie else null
        val response = client.getResponse(url, headers, cookie, null).getOrThrow()
        response.use {
            if (it.statusCode !in 200..299) throw HttpStatusException(it.statusCode)
            if ((it.contentLength ?: 0) > MAX_CHECKSUM_FILE) throw IOException("Checksum file too large")
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            it.inputStream.use { input ->
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_CHECKSUM_FILE) throw IOException("Checksum file too large")
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun checkCancelled() {
        if (cancelled()) throw CancellationException()
    }

    private class HttpStatusException(val status: Int) : IOException("HTTP $status")

    private companion object {
        const val TAG = "Checksum"

        /** Statuses that only say the candidate does not exist. */
        val MISSING_STATUSES = setOf("404", "410")

        const val BUFFER_SIZE = 256 * 1024
        const val REPORT_EVERY = 8L * 1024 * 1024

        /** A checksum file is a few lines; even a large SHA256SUMS fits well inside this. */
        const val MAX_CHECKSUM_FILE = 1024 * 1024

        /** Headers that describe the original file request, not the checksum one. */
        val DROPPED_HEADERS = setOf("range", "if-range", "if-match", "if-none-match", "if-modified-since", "accept-encoding")

        fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host

        fun isHttpUrl(text: String): Boolean = text.toHttpUrlOrNull() != null
    }
}
