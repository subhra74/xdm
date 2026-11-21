// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.core.network.http.Range
import xdm.core.network.http.impl.HttpClientImpl
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * `Host` is the first header after the request line, as browsers and curl send it. A CDN refused (410)
 * every segment of a stream the browser was playing because OkHttp wrote `Host` after XDM's headers, and
 * answered 206 to the same request with `Host` first. Checked on the raw request bytes, across a redirect
 * to another host, which gets its own `Host`.
 */
class TestHostHeaderFirst {
    /** A server that records each request's head (request line and header lines) and answers [reply]. */
    private class RawServer(private val reply: (Int) -> String) {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port get() = socket.localPort
        val requests: MutableList<List<String>> = Collections.synchronizedList(ArrayList())

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val conn = runCatching { socket.accept() }.getOrNull() ?: break
                    conn.use { c ->
                        val reader = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val head = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                        requests.add(head)
                        c.getOutputStream().write(reply(port).toByteArray(Charsets.ISO_8859_1))
                        c.getOutputStream().flush()
                    }
                }
            }
        }
    }

    private val servers = ArrayList<RawServer>()
    private val clients = ArrayList<HttpClientImpl>()

    @AfterEach
    fun tearDown() {
        clients.forEach { it.close() }
        servers.forEach { it.socket.close() }
    }

    private fun server(reply: (Int) -> String) = RawServer(reply).also { servers.add(it) }

    private val ok = { _: Int -> "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok" }

    private val browserHeaders = linkedMapOf(
        "sec-ch-ua-platform" to listOf("\"macOS\""),
        "User-Agent" to listOf("Mozilla/5.0"),
        "Origin" to listOf("https://site.example"),
        "Referer" to listOf("https://site.example/watch"),
    )

    private fun fetch(url: String, range: Range? = null): Int {
        val client = HttpClientImpl(1).also { clients.add(it) }
        return client.getResponse(url, browserHeaders, "a=1", range).getOrThrow().use { it.statusCode }
    }

    @Test
    fun hostIsTheFirstHeader() {
        val s = server(ok)
        assertEquals(200, fetch("http://127.0.0.1:${s.port}/seg-1.m4s", Range(0, 0)), "status")
        val head = s.requests.single()
        assertEquals("GET /seg-1.m4s HTTP/1.1", head[0], "request line")
        assertEquals("Host: 127.0.0.1:${s.port}", head[1], "Host right after the request line")
        assertEquals(
            listOf("sec-ch-ua-platform", "User-Agent", "Origin", "Referer", "Range"),
            head.drop(2).map { it.substringBefore(':') }.filter { it !in setOf("Connection", "Accept-Encoding", "Cookie") },
            "the caller's headers keep their order",
        )
    }

    @Test
    fun redirectToAnotherHost_getsItsOwnHostFirst() {
        val target = server(ok)
        val first = server { _ ->
            "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${target.port}/b\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        }
        assertEquals(200, fetch("http://127.0.0.1:${first.port}/a"), "status after the redirect")
        assertEquals("Host: 127.0.0.1:${first.port}", first.requests.single()[1], "first hop")
        assertEquals("Host: 127.0.0.1:${target.port}", target.requests.single()[1], "redirect hop")
    }
}
