package xdm.integration


import java.net.Socket
import java.nio.charset.StandardCharsets

const val CRLF = "\r\n"

class RequestContext(
    val requestPath: String,
    val requestHeaders: Map<String, List<String>>,
    val requestBody: ByteArray?,
    private val socket: Socket,
    val keepAlive: Boolean
) {

    var responseBody: ByteArray? = null

    private val responseHeaders: MutableMap<String, MutableList<String>> = HashMap()

    var statusCode: Int = 200

    var statusMessage = "OK"


    fun sendResponse() {
        val io = socket.getOutputStream()
        val headerContents =
            responseHeaders.filter { (k, _: List<String>) -> !"content-length".equals(k, ignoreCase = true) }
                .map { (k, v) -> "$k : $v" }
        val headerLine = "HTTP/1.0 $statusCode $statusMessage"
        val headers = mutableListOf(headerLine, headerContents)
        headers.add("Connection: keep-alive")
        responseBody?.let { headers.add("Content-Length: ${it.size}") }
        headers.add(CRLF)
        val headerText = headers.joinToString(CRLF)
        io.write(headerText.toByteArray(StandardCharsets.UTF_8))
        responseBody?.let { io.write(it, 0, it.size) }
        io.flush()
    }

    fun addResponseHeader(name: String, value: String) {
        val values = responseHeaders.getOrDefault(name, ArrayList())
        values.add(value)
        responseHeaders[name] = values
    }
}
