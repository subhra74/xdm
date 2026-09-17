package xdm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xdm.integration.HttpParser
import xdm.integration.HttpServer
import xdm.integration.RequestContext
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression for CODE_REVIEW B8: the local integration server must write well-formed HTTP/1.1
 * responses and parse requests without depending on header-name case.
 *
 * Uses a real local socket pair: the test writes raw request bytes on the client side, the server
 * side goes through [HttpParser] and [RequestContext], and the raw response bytes are checked.
 */
class IntegrationHttpTest {
    private lateinit var listener: ServerSocket
    private lateinit var client: Socket
    private lateinit var server: Socket

    @Before
    fun setup() {
        listener = ServerSocket(0)
        client = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 5000 }
        server = listener.accept().apply { soTimeout = 5000 }
    }

    @After
    fun tearDown() {
        listOf(client, server, listener).forEach { runCatching { it.close() } }
    }

    private fun request(raw: String): RequestContext {
        client.getOutputStream().apply { write(raw.toByteArray()); flush() }
        return HttpParser.parseContext(server)
    }

    /** Reads one response: status line, header lines and the Content-Length body. */
    private fun readResponse(): Pair<List<String>, String> {
        val input: InputStream = client.getInputStream()
        val lines = ArrayList<String>()
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) break
            if (c == '\n'.code) {
                val line = sb.toString().removeSuffix("\r")
                sb.setLength(0)
                if (line.isEmpty()) break
                lines += line
            } else sb.append(c.toChar())
        }
        val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.toInt() ?: 0
        val body = ByteArray(length).also { var n = 0; while (n < length) n += input.read(it, n, length - n) }
        return lines to String(body)
    }

    private fun jsonResponse(ctx: RequestContext) = ctx.apply {
        statusCode = 200
        statusMessage = "OK"
        addResponseHeader("Content-Type", "application/json")
        addResponseHeader("Cache-Control", "max-age=0, no-cache, must-revalidate")
        responseBody = """{"ok":true}""".toByteArray()
    }

    @Test
    fun response_usesHttp11StatusLine() {
        jsonResponse(request("GET /sync HTTP/1.1\r\nHost: x\r\n\r\n")).sendResponse()
        assertEquals("HTTP/1.1 200 OK", readResponse().first.first())
    }

    @Test
    fun response_writesEachHeaderOnItsOwnWellFormedLine() {
        jsonResponse(request("GET /sync HTTP/1.1\r\nHost: x\r\n\r\n")).sendResponse()
        val (lines, body) = readResponse()
        assertTrue("Content-Type line missing: $lines", "Content-Type: application/json" in lines)
        assertTrue("Cache-Control line missing: $lines", "Cache-Control: max-age=0, no-cache, must-revalidate" in lines)
        lines.drop(1).forEach { assertTrue("malformed header line: '$it'", Regex("^[A-Za-z0-9-]+: .*").matches(it)) }
        assertEquals("""{"ok":true}""", body)
    }

    @Test
    fun response_headerWithTwoValues_isSentAsTwoLines() {
        request("GET /sync HTTP/1.1\r\nHost: x\r\n\r\n").apply {
            addResponseHeader("Vary", "Origin")
            addResponseHeader("Vary", "Accept")
            responseBody = ByteArray(0)
        }.sendResponse()
        val lines = readResponse().first
        assertTrue("Vary: Origin" in lines)
        assertTrue("Vary: Accept" in lines)
    }

    @Test
    fun response_withoutBody_stillSendsContentLength() {
        request("POST /clear HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n").apply {
            statusCode = 403
            statusMessage = "Forbidden"
        }.sendResponse()
        assertTrue("Content-Length: 0" in readResponse().first)
    }

    @Test
    fun response_connectionHeaderMatchesRequest() {
        jsonResponse(request("GET /sync HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")).sendResponse()
        val lines = readResponse().first
        assertTrue("expected Connection: close in $lines", "Connection: close" in lines)
        assertFalse(lines.any { it.equals("Connection: keep-alive", ignoreCase = true) })
    }

    @Test
    fun parser_readsBodyWithLowercaseContentLength() {
        val ctx = request("POST /download HTTP/1.1\r\nhost: x\r\ncontent-length: 5\r\n\r\nhello")
        assertEquals("hello", ctx.requestBody?.let { String(it) })
    }

    @Test
    fun parser_keepAliveFollowsHttpVersionDefaults() {
        assertTrue("HTTP/1.1 is persistent by default", request("GET /a HTTP/1.1\r\nHost: x\r\n\r\n").keepAlive)
        assertTrue("Connection value is case-insensitive", request("GET /b HTTP/1.1\r\nConnection: Keep-Alive\r\n\r\n").keepAlive)
        assertFalse("HTTP/1.0 closes by default", request("GET /c HTTP/1.0\r\nHost: x\r\n\r\n").keepAlive)
        assertFalse(request("GET /d HTTP/1.1\r\nConnection: close\r\n\r\n").keepAlive)
    }

    @Test
    fun endToEnd_strictClientParsesResponsesOnOneConnection() {
        val port = ServerSocket(0).use { it.localPort }
        val started = CountDownLatch(1)
        val http = HttpServer("127.0.0.1", port, { ctx -> jsonResponse(ctx).sendResponse() }, { started.countDown() }, {})
        http.start()
        try {
            assertTrue("server did not start", started.await(5, TimeUnit.SECONDS))
            val jdk = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()
            repeat(2) {
                val res = jdk.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port/sync")).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                assertEquals(200, res.statusCode())
                assertEquals("application/json", res.headers().firstValue("Content-Type").orElse(null))
                assertEquals("""{"ok":true}""", res.body())
            }
        } finally {
            http.stop()
        }
    }
}
