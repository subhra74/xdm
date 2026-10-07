package xdm.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Context describing a single incoming request, handed to an [Endpoint]'s plan function so a
 * test can decide, per connection, how the server should behave.
 */
data class ReqCtx(
    /** 1-based index of this connection to the endpoint (survives across the whole test). */
    val connIndex: Int,
    val hasRange: Boolean,
    val rangeStart: Long,
    val rangeEnd: Long,
    val total: Long,
    /** Request headers as received, keyed by lower-case name. */
    val headers: Map<String, List<String>> = emptyMap(),
    /** The request's path and query. */
    val uri: String = "",
)

/**
 * Per-connection behaviour. Defaults describe a well-behaved connection that serves the whole
 * requested (possibly ranged) body at full speed.
 */
data class ConnPlan(
    /** If >= 400, respond with this status and no body (e.g. 500, 503, 429). */
    val failStatus: Int = 0,
    /** Value for the Retry-After header when [failStatus] == 429. */
    val retryAfterSecs: Int = 0,
    /** Ignore the Range header and serve the full body with 200 (simulates a non-resumable server). */
    val ignoreRange: Boolean = false,
    /** Send with chunked encoding / unknown length (Content-Length absent). */
    val omitContentLength: Boolean = false,
    /** Sleep this long before sending any headers/body. */
    val delayBeforeMs: Long = 0,
    /** If > 0, send the body in slices of this size... */
    val throttleChunk: Int = 0,
    /** ...sleeping this long between slices. */
    val throttleSleepMs: Long = 0,
    /** After sending this many body bytes, stall (hold the connection open, sending nothing). */
    val stallAfterBytes: Long = -1,
    /** How long to stall: < 0 means "until the server is stopped"; otherwise resume after this. */
    val stallMs: Long = -1,
    /** After sending this many bytes, abruptly close the connection (premature end -> client IOException). */
    val truncateAfterBytes: Long = -1,
    /** Serve this version of the file instead of the endpoint's, e.g. one that changed on the server. */
    val version: Version? = null,
)

/** A version of a served file: its bytes (the same size as the endpoint's), its ETag and its Last-Modified. */
class Version(val data: ByteArray, val etag: String?, val lastModified: String? = null)

/**
 * A registered file served by [MockHttpServer].
 */
class Endpoint(
    val data: ByteArray,
    val resumeSupported: Boolean = true,
    /** If > 0, at most this many requests are served at once; the rest get [limitStatus] and no body. */
    val maxConcurrent: Int = 0,
    val limitStatus: Int = 503,
    /**
     * Sent as ETag and Last-Modified. A range request whose If-Range matches neither gets the whole
     * file with 200.
     */
    val etag: String? = null,
    val lastModified: String? = null,
    val plan: (ReqCtx) -> ConnPlan = { ConnPlan() },
) {
    val connCount = AtomicInteger(0)
    val requests = CopyOnWriteArrayList<ReqCtx>()

    /** Requests being served right now, the most seen at once, and how many the limit turned away. */
    val active = AtomicInteger(0)
    val peakActive = AtomicInteger(0)
    val refusedByLimit = AtomicInteger(0)

    @Volatile
    var closing = false
}

/**
 * A tiny, configurable in-process HTTP server (JDK [HttpServer], no extra dependency) for
 * exercising the segmented HTTP downloader. Serves in-memory byte arrays with full Range /
 * 206 support and lets each test inject per-connection behaviour: stalls, throttling,
 * truncation, failures, redirects-free no-resume, etc.
 */
class MockHttpServer {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool()
    private val endpoints = CopyOnWriteArrayList<Endpoint>()

    init {
        server.executor = executor
        server.start()
    }

    val port: Int get() = server.address.port

    fun url(path: String): String = "http://127.0.0.1:$port$path"

    fun register(path: String, endpoint: Endpoint): Endpoint {
        endpoints.add(endpoint)
        server.createContext(path) { ex -> serve(endpoint, ex) }
        return endpoint
    }

    /** Answers every request to [path] with a 302 to [location], which is asked for anew each time. */
    fun redirect(path: String, location: () -> String) {
        server.createContext(path) { ex ->
            try {
                ex.responseHeaders.add("Location", location())
                ex.sendResponseHeaders(302, -1)
            } finally {
                ex.close()
            }
        }
    }

    fun stop() {
        endpoints.forEach { it.closing = true }
        try {
            server.stop(0)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun serve(ep: Endpoint, ex: HttpExchange) {
        val idx = ep.connCount.incrementAndGet()
        val total = ep.data.size.toLong()

        var start = 0L
        var end = total - 1
        var hasRange = false
        val rangeHeader = ex.requestHeaders.getFirst("Range")
        if (rangeHeader != null) {
            val m = Regex("""bytes=(\d+)-(\d*)""").find(rangeHeader)
            if (m != null) {
                hasRange = true
                start = m.groupValues[1].toLong()
                if (m.groupValues[2].isNotEmpty()) end = m.groupValues[2].toLong()
            }
        }
        val ctx = ReqCtx(
            idx, hasRange, start, end, total, ex.requestHeaders.mapKeys { it.key.lowercase() }, ex.requestURI.toString()
        )
        ep.requests.add(ctx)

        val now = ep.active.incrementAndGet()
        if (ep.maxConcurrent > 0 && now > ep.maxConcurrent) {
            ep.active.decrementAndGet()
            ep.refusedByLimit.incrementAndGet()
            try {
                ex.sendResponseHeaders(ep.limitStatus, -1)
            } catch (e: IOException) {
                // client went away
            } finally {
                ex.close()
            }
            return
        }
        ep.peakActive.updateAndGet { maxOf(it, now) }
        try {
            serveAdmitted(ep, ex, ctx)
        } finally {
            ep.active.decrementAndGet()
        }
    }

    private fun serveAdmitted(ep: Endpoint, ex: HttpExchange, ctx: ReqCtx) {
        val total = ctx.total
        val start = ctx.rangeStart
        val end = ctx.rangeEnd
        val hasRange = ctx.hasRange
        val plan = ep.plan(ctx)
        val data = plan.version?.data ?: ep.data
        val etag = if (plan.version != null) plan.version.etag else ep.etag
        val lastModified = if (plan.version != null) plan.version.lastModified else ep.lastModified

        try {
            if (plan.delayBeforeMs > 0) sleepChecking(ep, plan.delayBeforeMs)

            if (plan.failStatus >= 400) {
                if (plan.failStatus == 429 && plan.retryAfterSecs > 0) {
                    ex.responseHeaders.add("Retry-After", plan.retryAfterSecs.toString())
                }
                ex.sendResponseHeaders(plan.failStatus, -1)
                return
            }

            // If-Range: the range only while the client's validator is still current, else the whole file.
            val ifRange = ctx.headers["if-range"]?.firstOrNull()
            val current = ifRange == null || ifRange == etag || ifRange == lastModified
            val useRange = ep.resumeSupported && !plan.ignoreRange && hasRange && current
            val rStart = if (useRange) start else 0L
            val rEnd = if (useRange) end else total - 1
            val length = rEnd - rStart + 1
            if (etag != null) ex.responseHeaders.add("ETag", etag)
            if (lastModified != null) ex.responseHeaders.add("Last-Modified", lastModified)
            if (useRange) {
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                ex.responseHeaders.add("Content-Range", "bytes $rStart-$rEnd/$total")
            }
            // A response we intend to cut short must not be kept alive / pooled, or the client
            // may reuse the poisoned connection for its retry and block forever. Force close.
            if (plan.truncateAfterBytes >= 0) {
                ex.responseHeaders.set("Connection", "close")
            }
            val code = if (useRange) 206 else 200
            if (plan.omitContentLength) {
                ex.sendResponseHeaders(code, 0) // 0 -> chunked, unknown length
            } else {
                ex.sendResponseHeaders(code, length)
            }

            val os = ex.responseBody
            val chunk = if (plan.throttleChunk > 0) plan.throttleChunk else 64 * 1024
            var pos = rStart
            var sent = 0L
            var stalled = false
            while (pos <= rEnd) {
                if (ep.closing) break
                var allow = rEnd - pos + 1
                if (plan.stallAfterBytes in 0..Long.MAX_VALUE && !stalled) {
                    allow = minOf(allow, plan.stallAfterBytes - sent)
                }
                if (plan.truncateAfterBytes >= 0) {
                    allow = minOf(allow, plan.truncateAfterBytes - sent)
                }
                if (allow <= 0) {
                    if (plan.truncateAfterBytes >= 0 && sent >= plan.truncateAfterBytes) {
                        // Abruptly stop before the declared length -> OkHttp raises a
                        // "premature end" IOException on the client, which is retryable.
                        // Closing the body stream short only marks the exchange closed and leaves the
                        // socket open, so the client would just block. Closing the exchange first makes
                        // that short stream close fail, and the JDK then closes the connection itself.
                        ex.close()
                        return
                    }
                    // Stall boundary reached.
                    if (plan.stallMs < 0) {
                        sleepChecking(ep, Long.MAX_VALUE)
                        break
                    }
                    sleepChecking(ep, plan.stallMs)
                    stalled = true
                    continue
                }
                val n = minOf(chunk.toLong(), allow).toInt()
                try {
                    os.write(data, pos.toInt(), n)
                    os.flush()
                } catch (e: IOException) {
                    break // client went away
                }
                pos += n
                sent += n
                if (plan.throttleSleepMs > 0) sleepChecking(ep, plan.throttleSleepMs)
            }
            try {
                os.close()
            } catch (e: Exception) {
                // ignore
            }
        } catch (e: Exception) {
            // swallow - a broken connection here is expected in many scenarios
        } finally {
            ex.close()
        }
    }

    private fun sleepChecking(ep: Endpoint, ms: Long) {
        val cap = if (ms < 0 || ms > 30_000) 30_000 else ms
        var slept = 0L
        while (slept < cap && !ep.closing) {
            val step = minOf(50L, cap - slept)
            try {
                Thread.sleep(step)
            } catch (e: InterruptedException) {
                return
            }
            slept += step
        }
    }
}
