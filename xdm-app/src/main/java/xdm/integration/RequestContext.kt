package xdm.integration


import java.net.Socket
import java.nio.charset.StandardCharsets

const val CRLF = "\r\n"

class RequestContext(
    val requestMethod: String,
    val requestPath: String,
    val requestHeaders: Map<String, List<String>>,
    val requestBody: ByteArray?,
    private val socket: Socket,
    keepAlive: Boolean
) {

    /**
     * Whether the socket stays open for another request. Initialised from the request's HTTP
     * version and `Connection` header, but the handler may force it to `false` to make the response
     * the last one on this connection (and so end the thread serving it).
     */
    var keepAlive: Boolean = keepAlive

    var responseBody: ByteArray? = null

    /** Insertion-ordered, so headers go out in the order they were added. */
    private val responseHeaders: MutableMap<String, MutableList<String>> = LinkedHashMap()

    var statusCode: Int = 200

    var statusMessage = "OK"

    /**
     * Writes an HTTP/1.1 response: one `Name: value` line per header value, then `Content-Length`
     * (always, 0 without a body) and a `Connection` header that matches whether the server keeps the
     * socket open for another request ([keepAlive]).
     */
    fun sendResponse() {
        val lines = mutableListOf("HTTP/1.1 $statusCode $statusMessage")
        responseHeaders
            .filterKeys { !it.equals("Content-Length", ignoreCase = true) && !it.equals("Connection", ignoreCase = true) }
            .forEach { (name, values) -> values.forEach { lines += "$name: $it" } }
        lines += "Content-Length: ${responseBody?.size ?: 0}"
        lines += "Connection: ${if (keepAlive) "keep-alive" else "close"}"

        val io = socket.getOutputStream()
        io.write((lines.joinToString(CRLF) + CRLF + CRLF).toByteArray(StandardCharsets.UTF_8))
        responseBody?.let { io.write(it, 0, it.size) }
        io.flush()
    }

    fun getRequestHeader(name: String): String? =
        requestHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    fun addResponseHeader(name: String, value: String) {
        val values = responseHeaders.getOrDefault(name, ArrayList())
        values.add(value)
        responseHeaders[name] = values
    }
}
