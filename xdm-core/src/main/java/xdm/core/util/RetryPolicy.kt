// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.util

import xdm.core.downloaders.DownloadError
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

/** What kind of failure an answer (or the lack of one) is, for deciding whether to ask again. */
enum class Trouble {
    /** The server can't serve us right now: 429, 5xx, a few 4xx that mean "later", or a refusal carrying Retry-After. */
    Server,

    /** The link is refused: 403, 410, a 401 that no login answers, and a few "expired" codes. */
    Refused,

    /** Not found and the other 4xx. */
    OtherLink,

    /** No usable answer: connect or read errors, timeouts. */
    Network,
}

/** The times [RetryPolicy] works with. Tests shorten them through [RetryPolicy.timings]. */
data class RetryTimings(
    /** A download that received nothing for this long is stalled: refusals stop counting as a connection limit. */
    val stallMs: Long = 15_000,
    /** A download stuck on server errors gives up after receiving nothing for this long. */
    val budgetMs: Long = 5 * 60_000,
    /** No single wait is longer, whatever Retry-After says. */
    val maxWaitMs: Long = 5 * 60_000,
    val firstBackoffMs: Long = 1_000,
    val maxBackoffMs: Long = 10_000,
    /** Waits wake this often to notice Pause. */
    val stepMs: Long = 200,
)

/**
 * When the parts of a download (HTTP chunks, HLS/DASH segments, batch items) ask again after a failed request.
 * Every part keeps asking while the download as a whole receives data, so a server that limits connections
 * (with 503, 403 or anything else) only slows the extra connections down; once the download has received
 * nothing for a while ([RetryTimings.stallMs]) refusals count as an expired link, and server errors are waited
 * out up to [RetryTimings.budgetMs].
 */
object RetryPolicy {
    @Volatile
    var timings = RetryTimings()

    private val LOGIN_SCHEMES = listOf("basic", "ntlm", "negotiate")

    /**
     * The kind of a non-2xx answer, or null when asking again can't help (a 401 asking for a login).
     * [notFoundIsRefusal]: a missing HLS/DASH segment is usually an expired one.
     */
    fun classify(code: Int, wwwAuthenticate: String?, hasRetryAfter: Boolean, notFoundIsRefusal: Boolean = false): Trouble? {
        if (code == 401 && LOGIN_SCHEMES.any { wwwAuthenticate?.contains(it, ignoreCase = true) == true }) return null
        if (code in 500..599 || code == 408 || code == 413 || code == 425 || code == 429) return Trouble.Server
        if (isLinkExpiredStatus(code, wwwAuthenticate) || (notFoundIsRefusal && code == 404)) {
            // The server says when to come back: that is a busy server, not a dead link.
            return if (hasRetryAfter) Trouble.Server else Trouble.Refused
        }
        return Trouble.OtherLink
    }

    /** Retry-After as milliseconds: delta-seconds or an HTTP date. Null when absent, zero, negative or unreadable. */
    fun retryAfterMs(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        v.toLongOrNull()?.let { return if (it > 0) it * 1000 else null }
        return try {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
            (fmt.parse(v).time - nowMillis).takeIf { it > 0 }
        } catch (e: Exception) {
            null
        }
    }

    /** Sleeps [ms] in short steps; false (at once) when [cancelled] says so. */
    fun sleep(ms: Long, cancelled: () -> Boolean): Boolean {
        val deadline = System.nanoTime() / 1_000_000 + ms
        while (true) {
            if (cancelled()) return false
            val left = deadline - System.nanoTime() / 1_000_000
            if (left <= 0) return true
            Thread.sleep(min(left, timings.stepMs))
        }
    }
}

/**
 * When a download last received data, shared by all its parts. Monotonic time; [startRun] when the download
 * starts or resumes, so a run begins with a fresh window.
 */
class DownloadClock(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    @Volatile
    private var runStart = now()
    private val lastData = AtomicLong(Long.MIN_VALUE)

    fun startRun() {
        runStart = now()
    }

    fun onData() {
        lastData.set(now())
    }

    /** How long the download has received nothing, counting the start of this run as data. */
    fun quietFor(): Long = now() - maxOf(runStart, lastData.get())

    fun stalled(): Boolean = quietFor() >= RetryPolicy.timings.stallMs
}

/** What a part does after a failed attempt. */
sealed interface RetryDecision {
    data class Wait(val ms: Long) : RetryDecision
    data class GiveUp(val error: DownloadError) : RetryDecision
}

/** The retry state of one part (chunk, segment, item). Not thread safe: one per part's thread. */
class PartRetry(
    private val clock: DownloadClock,
    /** The Settings value: network errors counted while the download is stalled. */
    private val maxRetries: Int,
    private val random: Random = Random.Default,
) {
    private var failures = 0
    private var counted = 0

    /** The part received data: its backoff and its count start over. */
    fun onData() {
        failures = 0
        counted = 0
    }

    fun next(trouble: Trouble, retryAfterMs: Long? = null): RetryDecision {
        val t = RetryPolicy.timings
        val quiet = clock.quietFor()
        val stalled = quiet >= t.stallMs
        val wait = retryAfterMs?.let { min(it, t.maxWaitMs) } ?: backoff()
        return when (trouble) {
            Trouble.Server -> {
                val left = t.budgetMs - quiet
                when {
                    left <= 0 -> RetryDecision.GiveUp(DownloadError.ServerBusy)
                    // Asked to wait longer than nothing-downloading may last: say so now rather than sit there.
                    stalled && retryAfterMs != null && retryAfterMs > left -> RetryDecision.GiveUp(DownloadError.ServerBusy)
                    else -> RetryDecision.Wait(min(wait, left).coerceAtLeast(1))
                }
            }
            Trouble.Refused -> if (stalled) RetryDecision.GiveUp(DownloadError.LinkExpired) else RetryDecision.Wait(wait)
            Trouble.OtherLink -> if (stalled) RetryDecision.GiveUp(DownloadError.InvalidResponse) else RetryDecision.Wait(wait)
            Trouble.Network ->
                if (stalled && ++counted > maxRetries) RetryDecision.GiveUp(DownloadError.NetworkError)
                else RetryDecision.Wait(wait)
        }
    }

    /** 1, 2, 4, 8, then 10 s (by default), plus up to a quarter more so parts don't come back together. */
    fun backoff(): Long {
        val t = RetryPolicy.timings
        val base = min(t.firstBackoffMs shl min(failures, 20), t.maxBackoffMs)
        failures++
        return base + random.nextLong(base / 4 + 1)
    }
}
