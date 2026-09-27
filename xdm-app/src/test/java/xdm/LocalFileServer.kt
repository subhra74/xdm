package xdm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** One request as the server saw it. */
data class ServedRequest(
    val query: String?,
    val rangeStart: Long,
    val cookie: String?,
    val headers: Map<String, List<String>>,
)

/**
 * A small local HTTP file server for the refresh-link test: it serves one in-memory body with
 * Range support, slowly enough that a download can be paused in the middle, and only to requests
 * that carry the currently valid signature and cookie. Changing [validToken] / [requiredCookie]
 * expires the link the download was started with, which is what "refresh link" exists for.
 */
class LocalFileServer(private val data: ByteArray) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool()

    val requests = CopyOnWriteArrayList<ServedRequest>()

    @Volatile
    var validToken: String = "old"

    @Volatile
    var requiredCookie: String? = null

    @Volatile
    private var closing = false

    init {
        server.executor = executor
        server.createContext("/file", ::serve)
        server.start()
    }

    fun url(token: String): String = "http://127.0.0.1:${server.address.port}/file?token=$token"

    fun stop() {
        closing = true
        try {
            server.stop(0)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun serve(ex: HttpExchange) {
        val total = data.size.toLong()
        var start = 0L
        var end = total - 1
        var ranged = false
        ex.requestHeaders.getFirst("Range")?.let { header ->
            Regex("""bytes=(\d+)-(\d*)""").find(header)?.let { m ->
                ranged = true
                start = m.groupValues[1].toLong()
                if (m.groupValues[2].isNotEmpty()) end = m.groupValues[2].toLong()
            }
        }
        val cookie = ex.requestHeaders.getFirst("Cookie")
        requests.add(
            ServedRequest(
                query = ex.requestURI.query,
                rangeStart = start,
                cookie = cookie,
                headers = ex.requestHeaders.mapKeys { it.key.lowercase() },
            )
        )

        val token = ex.requestURI.query?.substringAfter("token=", "")
        val expected = requiredCookie
        if (token != validToken || (expected != null && cookie != expected)) {
            // The stale link: what the server does once the signed URL / session has expired.
            ex.sendResponseHeaders(403, -1)
            ex.close()
            return
        }

        try {
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            if (ranged) ex.responseHeaders.add("Content-Range", "bytes $start-$end/$total")
            ex.sendResponseHeaders(if (ranged) 206 else 200, end - start + 1)

            val os = ex.responseBody
            var pos = start
            while (pos <= end && !closing) {
                val n = minOf(CHUNK.toLong(), end - pos + 1).toInt()
                os.write(data, pos.toInt(), n)
                os.flush()
                pos += n
                // Throttled so the test can pause the download while it is still in flight.
                Thread.sleep(SLEEP_MS)
            }
            runCatching { os.close() }
        } catch (e: Exception) {
            // A connection broken by a pause is expected here.
        } finally {
            ex.close()
        }
    }

    companion object {
        private const val CHUNK = 8 * 1024
        private const val SLEEP_MS = 40L
    }
}
