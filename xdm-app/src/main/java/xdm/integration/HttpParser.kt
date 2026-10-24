package xdm.integration

import xdm.integration.LineReader.readLine
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.TreeMap

/**
 * Parses requests in two steps, so a request can be judged before its body is read: [parseHead]
 * reads the request line and headers, then the caller decides whether to accept the body and reads
 * it through [BoundedInputStream]. Everything is capped: a page can reach the integration port with
 * `fetch`, and a request it sends must not be able to grow the heap or hold a thread for long.
 */
object HttpParser {
    /** Longest request or header line, without its line break. */
    const val MAX_LINE_BYTES = 256 * 1024

    /** Most header lines in one request. */
    const val MAX_HEADERS = 100

    /** Most bytes in the request line and headers together. */
    const val MAX_HEAD_BYTES = 512 * 1024

    /** Body limit when the caller gives none ([parseContext]). */
    const val DEFAULT_MAX_BODY_BYTES = 8L * 1024 * 1024

    /** Time allowed to receive a whole request, counted from its first byte. */
    const val REQUEST_DEADLINE_MS = 60_000L

    /** A request line and headers; the body is still unread on the connection. */
    class RequestHead(
        val method: String,
        val path: String,
        val version: String,
        /** Case-insensitive names. */
        val headers: Map<String, List<String>>,
        /** [System.nanoTime] by which the rest of this request, body included, must have arrived. */
        val deadlineNanos: Long,
    ) {
        /** The path without its query string. */
        val route: String get() = path.substringBefore('?')

        fun header(name: String): String? = headers[name]?.firstOrNull()

        /**
         * HTTP/1.1 connections are persistent unless the client sends `Connection: close`; HTTP/1.0 ones
         * only when it sends `Connection: keep-alive`. Header values are compared ignoring case.
         */
        val keepAlive: Boolean
            get() {
                val tokens = headers["Connection"].orEmpty().flatMap { it.split(",") }.map { it.trim().lowercase() }
                return if (version == "HTTP/1.1") "close" !in tokens else "keep-alive" in tokens
            }
    }

    /**
     * A request that is answered with [status] and not handled. The connection is closed after the
     * reply: whatever is left of the request on it is unread.
     */
    class RequestRejectedException(val status: Int, val reason: String) : IOException("$status $reason")

    /**
     * Thrown when the client closes the connection before sending another request: a normal end of a
     * kept-alive connection, not an error.
     */
    class ConnectionClosedException : IOException("Connection closed by client")

    /** Reads the request line and headers, within [MAX_LINE_BYTES], [MAX_HEADERS] and [MAX_HEAD_BYTES]. */
    fun parseHead(io: InputStream): RequestHead {
        // Waiting for the first byte is idle time on a kept-alive connection (bounded by the socket's
        // read timeout); the request's own deadline starts once it begins to arrive.
        var first = readFirstByte(io)
        val deadline = System.nanoTime() + REQUEST_DEADLINE_MS * 1_000_000
        val budget = LineReader.Budget(MAX_HEAD_BYTES, deadline)

        var requestLine = ""
        while (requestLine.isEmpty()) { // tolerate blank lines before a request line
            requestLine = when (first) {
                -1 -> readLine(io, MAX_LINE_BYTES, budget)
                '\n'.code -> ""
                '\r'.code -> readLine(io, MAX_LINE_BYTES, budget)
                else -> first.toChar() + readLine(io, MAX_LINE_BYTES - 1, budget)
            }
            first = -1
        }
        val (method, path, version) = parseRequestLine(requestLine)

        val headers: MutableMap<String, MutableList<String>> = TreeMap(String.CASE_INSENSITIVE_ORDER)
        var count = 0
        while (true) {
            val line = readLine(io, MAX_LINE_BYTES, budget)
            if (line.isEmpty()) break
            if (++count > MAX_HEADERS) throw RequestRejectedException(431, "Request Header Fields Too Large")
            val (key, value) = parseHeader(line)
            headers.getOrPut(key) { ArrayList() }.add(value)
        }
        return RequestHead(method, path, version, headers, deadline)
    }

    /**
     * The body length the head announces: 0 without `Content-Length`. Bodies are only ever framed by
     * `Content-Length`; `Transfer-Encoding` is refused, since ignoring it would leave its body to be read
     * as the next request.
     */
    fun contentLength(head: RequestHead): Long {
        if (head.headers.containsKey("Transfer-Encoding")) throw RequestRejectedException(400, "Bad Request")
        val values = head.headers["Content-Length"] ?: return 0
        val lengths = values.flatMap { it.split(",") }.map { it.trim() }.distinct()
        val length = lengths.singleOrNull()?.toLongOrNull()
        if (length == null || length < 0) throw RequestRejectedException(400, "Bad Request")
        return length
    }

    /**
     * Reads one whole request with the default limits. The integration server goes through
     * [parseHead] itself, so it can refuse a request before reading the body; this is the one-call
     * form for everything else.
     */
    fun parseContext(socket: Socket): RequestContext {
        val io = socket.getInputStream()
        val head = parseHead(io)
        val length = contentLength(head)
        if (length > DEFAULT_MAX_BODY_BYTES) throw RequestRejectedException(413, "Content Too Large")
        return RequestContext(head, BoundedInputStream(io, length, head.deadlineNanos), length, socket)
    }

    /** Returns (method, path, version) of a request line such as "POST /download HTTP/1.1". */
    private fun parseRequestLine(line: String): Triple<String, String, String> {
        val arr = line.split(" ")
        if (arr.size > 2) {
            return Triple(arr[0].uppercase(), arr[1], arr[2].uppercase())
        }
        throw RequestRejectedException(400, "Bad Request")
    }

    private fun parseHeader(headerLine: String): Pair<String, String> {
        val index = headerLine.indexOf(":")
        if (index > 0) {
            return Pair(headerLine.substring(0, index).trim(), headerLine.substring(index + 1).trim())
        }
        throw RequestRejectedException(400, "Bad Request")
    }

    /** End of stream before any byte of a request means the client closed the connection. */
    private fun readFirstByte(io: InputStream): Int = io.read().also {
        if (it == -1) throw ConnectionClosedException()
    }
}
