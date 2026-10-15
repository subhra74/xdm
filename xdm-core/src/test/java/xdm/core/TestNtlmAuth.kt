package xdm.core

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.network.http.AuthScope
import xdm.core.network.http.BasicCredentials
import xdm.core.network.http.CredentialPrompt
import xdm.core.network.http.HttpAuth
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.isLinkExpiredStatus
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

/**
 * NTLM against servers and proxies that bind the handshake to one connection, as IIS and Squid do.
 * The server side is the JDK's own NTLM implementation ([JdkNtlmServer]), so the Type3 messages are
 * checked by code independent of `Ntlm`.
 */
class TestNtlmAuth {
    private val right = BasicCredentials("CORP\\alice", "right")
    private val wrong = BasicCredentials("CORP\\alice", "wrong")
    private val closeables = mutableListOf<Closeable>()
    private val clients = mutableListOf<HttpClientImpl>()

    @AfterEach
    fun tearDown() {
        clients.forEach { it.close() }
        closeables.forEach { runCatching { it.close() } }
    }

    private class ScriptedPrompt(vararg answers: BasicCredentials?) : CredentialPrompt {
        private val queue = ArrayDeque(answers.toList())
        val asked: MutableList<Pair<AuthScope, Boolean>> = Collections.synchronizedList(mutableListOf())
        override fun ask(scope: AuthScope, rejected: Boolean): BasicCredentials? {
            asked += scope to rejected
            Thread.sleep(100) // let parallel segments pile up on the same scope
            return synchronized(queue) { queue.removeFirstOrNull() }
        }
    }

    /**
     * A minimal HTTP/1.1 server or proxy demanding NTLM under [scheme], keeping the pending challenge
     * per connection. A proxy answers CONNECT by tunnelling to [tunnelPort] once authenticated; with
     * [upstreamPort] it forwards plain requests there instead of answering them, and like Squid keeps
     * an authenticated client connection authenticated and pinned to one upstream connection.
     */
    private inner class NtlmServer(
        val proxy: Boolean,
        val scheme: String = "NTLM",
        dropFirstType3: Boolean = false,
        val tunnelPort: Int? = null,
        val upstreamPort: Int? = null,
        accounts: Map<String, String> = mapOf("alice" to "right"),
    ) : Closeable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port get() = socket.localPort
        /** `DOMAIN\user` of every Type3 the JDK verified. */
        val verified: MutableList<String> = CopyOnWriteArrayList()
        /** The user name of every Type3 received, accepted or not. */
        val offered: MutableList<String> = CopyOnWriteArrayList()
        val type1s = AtomicInteger()
        val basicHeaders = AtomicInteger()
        private val dropped = AtomicBoolean(!dropFirstType3)
        private val ntlm = JdkNtlmServer("CORP", accounts)

        private val authHeader = if (proxy) "proxy-authorization" else "authorization"
        private val challengeLine = if (proxy) "407 Proxy Authentication Required" else "401 Unauthorized"
        private val challengeHeader = if (proxy) "Proxy-Authenticate" else "WWW-Authenticate"

        init {
            closeables += this
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val s = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { s.use { handle(it) } }
                }
            }
        }

        override fun close() = socket.close()

        private fun handle(s: Socket) {
            s.soTimeout = 15_000
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            var nonce: ByteArray? = null
            var authenticated = false
            var upstream: Socket? = null
            fun forward(head: List<String>) {
                val up = upstream ?: Socket(InetAddress.getLoopbackAddress(), upstreamPort!!).also { upstream = it }
                val path = head[0].replace(Regex("^(\\S+) https?://[^/]+"), "$1 ")
                val lines = listOf(path) + head.drop(1).filterNot { it.lowercase().startsWith("proxy-") }
                up.getOutputStream().apply { write((lines.joinToString("\r\n") + "\r\n\r\n").toByteArray()); flush() }
                val upIn = up.getInputStream()
                val reply = readHead(upIn) ?: return
                val length = reply.firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
                out.write((reply.joinToString("\r\n") + "\r\n\r\n").toByteArray())
                out.write(ByteArray(length).also { var n = 0; while (n < length) n += upIn.read(it, n, length - n) })
                out.flush()
            }
            while (true) {
                val head = readHead(input) ?: return upstream?.close() ?: Unit
                if (authenticated) {
                    forward(head)
                    continue
                }
                val auth = head.drop(1).firstOrNull { it.lowercase().startsWith("$authHeader:") }?.substringAfter(':')?.trim()
                if (auth != null && auth.startsWith("Basic ", ignoreCase = true)) basicHeaders.incrementAndGet()
                val token = auth?.takeIf { it.startsWith("$scheme ", ignoreCase = true) }
                    ?.let { Base64.getDecoder().decode(it.substringAfter(' ').trim()) }
                when (token?.get(8)?.toInt()) {
                    1 -> {
                        type1s.incrementAndGet()
                        val n = ByteArray(8).also { SecureRandom().nextBytes(it) }
                        nonce = n
                        val type2 = ntlm.type2(token, n)
                        challenge(out, " " + Base64.getEncoder().encodeToString(type2))
                    }
                    3 -> {
                        val n = nonce
                        nonce = null
                        offered += type3User(token)
                        // No pending challenge on this connection (or the scripted "crossed" one): start over.
                        val ok = n != null && dropped.getAndSet(true) && ntlm.verify(token, n)?.also { verified += it } != null
                        if (!ok) {
                            challenge(out, "")
                            continue
                        }
                        if (upstreamPort != null && !head[0].startsWith("CONNECT")) {
                            authenticated = true
                            forward(head)
                            continue
                        }
                        if (head[0].startsWith("CONNECT")) {
                            out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                            out.flush()
                            tunnel(s, input, out)
                            return
                        }
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                        out.flush()
                    }
                    else -> challenge(out, "")
                }
            }
        }

        private fun type3User(type3: ByteArray): String {
            fun short(at: Int) = (type3[at].toInt() and 0xFF) or ((type3[at + 1].toInt() and 0xFF) shl 8)
            val offset = short(40) or (short(42) shl 16)
            return String(type3, offset, short(36), Charsets.UTF_16LE)
        }

        private fun challenge(out: OutputStream, token: String) {
            out.write("HTTP/1.1 $challengeLine\r\n$challengeHeader: $scheme$token\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
        }

        private fun tunnel(client: Socket, input: InputStream, out: OutputStream) {
            Socket(InetAddress.getLoopbackAddress(), tunnelPort!!).use { target ->
                val up = thread(isDaemon = true) { runCatching { input.copyTo(target.getOutputStream()) }; runCatching { target.shutdownOutput() } }
                runCatching { target.getInputStream().copyTo(out) }
                runCatching { client.shutdownOutput() }
                up.join(5_000)
            }
        }

        private fun readHead(input: InputStream): List<String>? {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            while (true) {
                val c = runCatching { input.read() }.getOrDefault(-1)
                if (c < 0) return null
                if (c == '\n'.code) {
                    val text = line.toString().trimEnd('\r')
                    line.setLength(0)
                    if (text.isEmpty()) return lines
                    lines += text
                } else line.append(c.toChar())
            }
        }
    }

    /** An HTTPS origin with a throwaway self-signed certificate, answering 200 "secure". */
    private fun httpsOrigin(): HttpsServer {
        val store = File.createTempFile("ntlm-test", ".p12").apply { delete(); deleteOnExit() }
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
            "-validity", "2", "-storetype", "PKCS12", "-keystore", store.path, "-storepass", "changeit", "-keypass", "changeit",
        ).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, "keytool failed")
        val ks = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "changeit".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
        val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        return HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(ssl)
            createContext("/") { ex ->
                ex.sendResponseHeaders(200, 6)
                ex.responseBody.use { it.write("secure".toByteArray()) }
            }
            executor = Executors.newCachedThreadPool()
            start()
            closeables += Closeable { stop(0) }
        }
    }

    private fun client(auth: HttpAuth?, proxy: Proxy? = null, user: String = "", pass: String = "", ignoreCert: Boolean = false) =
        HttpClientImpl(4, proxy, ignoreCert, readTimeoutSeconds = 10, proxyUser = user, proxyPassword = pass, auth = auth, serverAuth = true)
            .also { clients += it }

    private fun HttpClientImpl.fetch(url: String): Pair<Int, String> =
        getResponse(url, null, null, null).getOrThrow().use { it.statusCode to it.inputStream.readBytes().toString(Charsets.UTF_8) }

    private fun proxyOf(server: NtlmServer) = Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), server.port))

    private fun origin(server: NtlmServer) = "http://127.0.0.1:${server.port}/file"

    @Test
    fun direct_typedDomainCredentials_accepted() {
        val prompt = ScriptedPrompt(right)
        val srv = NtlmServer(proxy = false)

        assertEquals(200 to "ok", client(HttpAuth(prompt)).fetch(origin(srv)), "NTLM handshake did not complete")
        assertEquals(listOf("CORP\\alice"), srv.verified, "server should verify the domain user")
        assertEquals(1, prompt.asked.size, "one prompt")
        assertTrue(prompt.asked[0].first.ntlm, "prompt should know it is NTLM")
        assertFalse(prompt.asked[0].first.proxy, "server scope")
    }

    @Test
    fun direct_wrongPassword_promptsAgainUntilAccepted() {
        val prompt = ScriptedPrompt(wrong, right)
        val srv = NtlmServer(proxy = false)

        assertEquals(200, client(HttpAuth(prompt)).fetch(origin(srv)).first, "right pair after a wrong one")
        assertEquals(listOf(false, true), prompt.asked.map { it.second }, "prompts and their rejected flags")
    }

    @Test
    fun direct_cancel_leaves401_thatIsNotAnExpiredLink() {
        val prompt = ScriptedPrompt(null)
        val srv = NtlmServer(proxy = false)
        val c = client(HttpAuth(prompt))

        val response = c.getResponse(origin(srv), null, null, null).getOrThrow()
        response.use {
            assertEquals(401, it.statusCode, "cancel should leave the 401")
            assertFalse(isLinkExpiredStatus(it.statusCode, it.getHeader("WWW-Authenticate")), "NTLM 401 read as an expired link")
        }
        assertEquals(401, c.fetch(origin(srv)).first, "cancelled download should keep failing")
        assertEquals(1, prompt.asked.size, "prompted again after cancel")
    }

    @Test
    fun direct_crossedConnection_restartsWithoutPrompt() {
        val prompt = ScriptedPrompt(right)
        val srv = NtlmServer(proxy = false, dropFirstType3 = true)

        assertEquals(200, client(HttpAuth(prompt)).fetch(origin(srv)).first, "restart after a lost Type3")
        assertEquals(2, srv.type1s.get(), "one restart")
        assertEquals(1, prompt.asked.size, "a lost handshake must not prompt")
    }

    @Test
    fun direct_negotiateOnly_answeredWithNtlm() {
        val srv = NtlmServer(proxy = false, scheme = "Negotiate")

        assertEquals(200, client(HttpAuth(ScriptedPrompt(right))).fetch(origin(srv)).first, "Negotiate-only server")
        assertEquals(listOf("CORP\\alice"), srv.verified, "server should verify the domain user")
    }

    @Test
    fun direct_parallelSegments_eachConnectionAuthenticates_onePrompt() {
        val prompt = ScriptedPrompt(right)
        val srv = NtlmServer(proxy = false)
        val c = client(HttpAuth(prompt))
        val pool = Executors.newFixedThreadPool(8)
        val results = (1..8).map { pool.submit<Int> { c.fetch(origin(srv)).first } }.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(List(8) { 200 }, results, "every segment should get through")
        assertEquals(1, prompt.asked.size, "one dialog per scope, not per segment")
    }

    @Test
    fun proxy_plainHttp_configuredCredentials_noPrompt() {
        val prompt = ScriptedPrompt()
        val proxy = NtlmServer(proxy = true)
        val c = client(HttpAuth(prompt), proxyOf(proxy), right.user, right.password)

        assertEquals(200 to "ok", c.fetch("http://example.invalid/file"), "proxy NTLM with the Settings pair")
        assertEquals(listOf("CORP\\alice"), proxy.verified, "proxy should verify the domain user")
        assertEquals(0, prompt.asked.size, "configured credentials should not prompt")
    }

    @Test
    fun proxy_plainHttp_wrongConfigured_promptsUntilAccepted() {
        val prompt = ScriptedPrompt(right)
        val proxy = NtlmServer(proxy = true)
        val c = client(HttpAuth(prompt), proxyOf(proxy), wrong.user, wrong.password)

        assertEquals(200, c.fetch("http://example.invalid/file").first, "typed pair after the Settings one failed")
        assertEquals(listOf(true), prompt.asked.map { it.second }, "configured pair is tried before any prompt")
        assertTrue(prompt.asked.all { it.first.proxy && it.first.ntlm }, "prompt should be the NTLM proxy")
    }

    @Test
    fun proxy_plainHttp_withoutPrompt_configuredOnly() {
        val proxy = NtlmServer(proxy = true)

        assertEquals(200, client(null, proxyOf(proxy), right.user, right.password).fetch("http://example.invalid/file").first, "configured pair")
        assertEquals(407, client(null, proxyOf(proxy), wrong.user, wrong.password).fetch("http://example.invalid/file").first, "wrong pair stands")
    }

    @Test
    fun proxyAndServerBothNtlm_serverNeverGetsProxyCredentials() {
        val origin = NtlmServer(proxy = false)
        val proxy = NtlmServer(proxy = true, upstreamPort = origin.port, accounts = mapOf("bob" to "proxypass"))
        val cancel = ScriptedPrompt(null)
        val c = client(HttpAuth(cancel), proxyOf(proxy), "CORP\\bob", "proxypass")

        assertEquals(401, c.fetch("http://example.invalid/file").first, "server 401 should stand after cancel")
        assertEquals(listOf("CORP\\bob"), proxy.verified.distinct(), "proxy should accept its own account")
        assertFalse(origin.offered.any { it.equals("bob", ignoreCase = true) }, "server was offered the proxy account")
        assertEquals(listOf(false), cancel.asked.map { it.first.proxy }, "the one prompt is for the server")

        val prompt = ScriptedPrompt(right)
        val c2 = client(HttpAuth(prompt), proxyOf(proxy), "CORP\\bob", "proxypass")
        assertEquals(200 to "ok", c2.fetch("http://example.invalid/file"), "both handshakes should complete")
        assertEquals(listOf("CORP\\alice"), origin.verified, "server should verify its own account")
        assertFalse(origin.offered.any { it.equals("bob", ignoreCase = true) }, "server was offered the proxy account")
    }

    @Test
    fun proxy_httpsTunnel_authenticatesConnect() {
        val origin = httpsOrigin()
        val proxy = NtlmServer(proxy = true, tunnelPort = origin.address.port)
        val prompt = ScriptedPrompt()
        val c = client(HttpAuth(prompt), proxyOf(proxy), right.user, right.password, ignoreCert = true)

        assertEquals(200 to "secure", c.fetch("https://localhost:${origin.address.port}/file"), "HTTPS through the NTLM proxy")
        assertEquals(listOf("CORP\\alice"), proxy.verified, "CONNECT should be NTLM-authenticated")
        assertEquals(0, prompt.asked.size, "configured credentials should not prompt")

        // OkHttp's preemptive tunnel call may carry the Settings pair as Basic once; NTLM follows.
        assertTrue(proxy.basicHeaders.get() <= 1, "Basic should be offered at most once")
    }
}
