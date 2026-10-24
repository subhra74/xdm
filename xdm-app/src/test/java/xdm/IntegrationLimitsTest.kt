package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.integration.BrowserIntegration
import xdm.integration.HttpServer
import xdm.integration.RequestContext
import xdm.integration.RequestPolicy
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The integration server's limits: requests are judged on their head before the body is read, and
 * sizes, Host, connection count and idle time are all bounded, since any web page can reach the port.
 */
class IntegrationLimitsTest {
    private val servers = ArrayList<HttpServer>()
    private val sockets = ArrayList<Socket>()

    /** Bodies the handler received, in order. */
    private val handled = CopyOnWriteArrayList<String>()

    @AfterEach
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        servers.forEach { it.stop() }
    }

    /** Starts a server whose handler decodes the body from the stream and answers 200. */
    private fun start(
        policy: (Int) -> RequestPolicy = { BrowserIntegration.RequestFilter(it) },
        maxConnections: Int = HttpServer.MAX_CONNECTIONS,
        readTimeoutMs: Int = HttpServer.READ_TIMEOUT_MS,
        readBody: Boolean = true,
        keepAlive: Boolean = false,
    ): Int {
        val listening = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val port = listening.localPort
        val server = HttpServer(listening, policy(port), maxConnections, readTimeoutMs) { ctx: RequestContext ->
            val body = if (readBody) ctx.requestBodyStream?.readAllBytes() else null
            handled += body?.toString(StandardCharsets.UTF_8) ?: ""
            ctx.keepAlive = keepAlive && ctx.keepAlive
            ctx.apply { responseBody = "ok".toByteArray() }.sendResponse()
        }
        server.start()
        servers += server
        return port
    }

    private fun connect(port: Int): Socket =
        Socket("127.0.0.1", port).apply { soTimeout = 5000 }.also { sockets += it }

    private fun Socket.send(raw: String) = getOutputStream().apply {
        write(raw.toByteArray(StandardCharsets.ISO_8859_1))
        flush()
    }

    /** Reads one response's status line and skips its headers and body. */
    private fun InputStream.readResponse(): String {
        fun line(): String {
            val sb = StringBuilder()
            while (true) {
                val c = read()
                if (c == -1) throw IOException("closed after '$sb'")
                if (c == '\n'.code) return sb.toString().removeSuffix("\r")
                sb.append(c.toChar())
            }
        }

        val status = line()
        var length = 0
        while (true) {
            val h = line()
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", ignoreCase = true)) length = h.substringAfter(":").trim().toInt()
        }
        readNBytes(length)
        return status
    }

    private fun statusOf(port: Int, raw: String): String = connect(port).run {
        send(raw)
        getInputStream().readResponse()
    }

    @Test
    fun oversizedBody_isRefusedWithoutReadingIt() {
        val port = start()
        // Announces 100 MB and sends none of it: the reply has to come from the head alone.
        val status = statusOf(port, "POST /download HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 100000000\r\n\r\n")
        assertEquals("HTTP/1.1 413 Content Too Large", status)
        assertTrue(handled.isEmpty(), "handler must not run")
    }

    @Test
    fun batch_acceptsBodyLargerThanTheDefaultLimit() {
        val port = start()
        val body = "x".repeat(9 * 1024 * 1024)
        val status = statusOf(port, "POST /batch HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: ${body.length}\r\n\r\n$body")
        assertEquals("HTTP/1.1 200 OK", status)
        assertEquals(body.length, handled.single().length)
    }

    @Test
    fun webPageOrigin_isRefusedBeforeItsBody() {
        val port = start()
        val status = statusOf(
            port,
            "POST /download HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nOrigin: https://evil.example\r\nContent-Length: 1000000\r\n\r\n"
        )
        assertEquals("HTTP/1.1 403 Forbidden", status)
        assertTrue(handled.isEmpty(), "handler must not run")
    }

    @Test
    fun foreignHost_isRefused() {
        // DNS rebinding: a same-origin GET from the page carries no Origin, only the page's host name.
        val port = start()
        assertEquals("HTTP/1.1 403 Forbidden", statusOf(port, "GET /sync HTTP/1.1\r\nHost: evil.example:$port\r\n\r\n"))
        assertEquals("HTTP/1.1 403 Forbidden", statusOf(port, "GET /sync HTTP/1.1\r\nHost: 127.0.0.1:1\r\n\r\n"))
        assertEquals("HTTP/1.1 403 Forbidden", statusOf(port, "GET /sync HTTP/1.0\r\n\r\n"))
        assertTrue(handled.isEmpty(), "handler must not run")
    }

    @Test
    fun loopbackHosts_andExtensionOrigin_areAccepted() {
        val port = start()
        assertEquals("HTTP/1.1 200 OK", statusOf(port, "GET /sync HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n"))
        assertEquals("HTTP/1.1 200 OK", statusOf(port, "GET /sync HTTP/1.1\r\nHost: LOCALHOST:$port\r\n\r\n"))
        assertEquals(
            "HTTP/1.1 200 OK",
            statusOf(
                port,
                "POST /download HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nOrigin: chrome-extension://abc\r\nContent-Length: 2\r\n\r\n{}"
            )
        )
        assertEquals("{}", handled.last())
    }

    @Test
    fun stateChangingGet_isRefused() {
        val port = start()
        assertEquals("HTTP/1.1 405 Method Not Allowed", statusOf(port, "GET /download HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n"))
    }

    @Test
    fun overlongHeaderLine_isRefused() {
        val port = start()
        val status = statusOf(port, "GET /sync HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nX-Long: ${"a".repeat(300 * 1024)}\r\n\r\n")
        assertEquals("HTTP/1.1 431 Request Header Fields Too Large", status)
    }

    @Test
    fun tooManyHeaders_isRefused() {
        val port = start()
        val headers = (1..101).joinToString("") { "X-$it: v\r\n" }
        val status = statusOf(port, "GET /sync HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n$headers\r\n")
        assertEquals("HTTP/1.1 431 Request Header Fields Too Large", status)
    }

    @Test
    fun tooManyHeaderBytes_isRefused() {
        // Every line is under the line limit; together they pass the 512 KiB head limit.
        val port = start()
        val headers = (1..66).joinToString("") { "X-$it: ${"a".repeat(8000)}\r\n" }
        val status = statusOf(port, "GET /sync HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n$headers\r\n")
        assertEquals("HTTP/1.1 431 Request Header Fields Too Large", status)
    }

    @Test
    fun chunkedBody_isRefused() {
        val port = start()
        val status = statusOf(
            port,
            "POST /download HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n"
        )
        assertEquals("HTTP/1.1 400 Bad Request", status)
        assertTrue(handled.isEmpty(), "handler must not run")
    }

    @Test
    fun invalidContentLength_isRefused() {
        val port = start()
        assertEquals(
            "HTTP/1.1 400 Bad Request",
            statusOf(port, "POST /download HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: -1\r\n\r\n")
        )
        assertEquals(
            "HTTP/1.1 400 Bad Request",
            statusOf(port, "POST /download HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\n{}")
        )
    }

    @Test
    fun streamedBody_stopsAtContentLength_onAKeptAliveConnection() {
        val port = start(policy = { RequestPolicy.ACCEPT_ALL }, keepAlive = true)
        connect(port).run {
            send("POST /a HTTP/1.1\r\nContent-Length: 3\r\n\r\nonePOST /b HTTP/1.1\r\nContent-Length: 3\r\n\r\ntwo")
            assertEquals("HTTP/1.1 200 OK", getInputStream().readResponse())
            assertEquals("HTTP/1.1 200 OK", getInputStream().readResponse())
        }
        assertEquals(listOf("one", "two"), handled.toList())
    }

    @Test
    fun unreadBody_isSkipped_beforeTheNextRequest() {
        val port = start(policy = { RequestPolicy.ACCEPT_ALL }, keepAlive = true, readBody = false)
        connect(port).run {
            send("POST /a HTTP/1.1\r\nContent-Length: 3\r\n\r\noneGET /b HTTP/1.1\r\n\r\n")
            assertEquals("HTTP/1.1 200 OK", getInputStream().readResponse())
            assertEquals("HTTP/1.1 200 OK", getInputStream().readResponse())
        }
        assertEquals(2, handled.size)
    }

    @Test
    fun idleConnection_isClosedAfterTheReadTimeout() {
        val port = start(readTimeoutMs = 300)
        connect(port).run {
            send("GET /sync HTTP/1.1\r\nHost: 127.0.0.1")  // never finishes its head
            val started = System.nanoTime()
            assertEquals(-1, getInputStream().read(), "server should close the connection")
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < 4000, "closed after $elapsedMs ms")
        }
        assertTrue(handled.isEmpty(), "handler must not run")
    }

    @Test
    fun connectionsBeyondTheCap_areClosed() {
        val port = start(maxConnections = 2)
        // Two idle connections hold both slots; the accept loop takes them in order.
        val held = List(2) { connect(port) }
        Thread.sleep(200)
        val refused = connect(port)
        assertEquals(-1, refused.getInputStream().read(), "third connection should be closed at once")
        // A slot frees up when a held connection ends.
        held[0].close()
        Thread.sleep(200)
        assertEquals("HTTP/1.1 200 OK", statusOf(port, "GET /sync HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n"))
    }
}
