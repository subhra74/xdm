package xdm.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.core.network.http.AuthScope
import xdm.core.network.http.BasicCredentials
import xdm.core.network.http.CredentialPrompt
import xdm.core.network.http.HttpAuth
import xdm.core.network.http.impl.HttpClientImpl
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Base64
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 401 / 407 Basic challenges are answered from the user, who may keep trying until accepted or cancelled. */
class TestHttpAuth {
    private val right = BasicCredentials("alice", "right")
    private val wrong = BasicCredentials("alice", "wrong")
    private val servers = mutableListOf<HttpServer>()
    private val clients = mutableListOf<HttpClientImpl>()

    @AfterEach
    fun tearDown() {
        clients.forEach { it.close() }
        servers.forEach { it.stop(0) }
    }

    /** Records every prompt and answers from [answers] in turn (null = Cancel). */
    private class ScriptedPrompt(vararg answers: BasicCredentials?, val delayMs: Long = 0) : CredentialPrompt {
        private val queue = ArrayDeque(answers.toList())
        val asked: MutableList<Pair<AuthScope, Boolean>> = Collections.synchronizedList(mutableListOf())
        override fun ask(scope: AuthScope, rejected: Boolean): BasicCredentials? {
            asked += scope to rejected
            if (delayMs > 0) Thread.sleep(delayMs)
            return synchronized(queue) { queue.removeFirstOrNull() }
        }
    }

    private fun basic(c: BasicCredentials) =
        "Basic " + Base64.getEncoder().encodeToString("${c.user}:${c.password}".toByteArray(Charsets.UTF_8))

    /** Answers 200 to [right] in [header], otherwise [status] with [challengeHeader]: [challenge]. */
    private fun server(header: String, status: Int, challengeHeader: String, challenge: String): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex: HttpExchange ->
                if (ex.requestHeaders.getFirst(header) == basic(right)) {
                    ex.sendResponseHeaders(200, 2)
                    ex.responseBody.use { it.write("ok".toByteArray()) }
                } else {
                    ex.responseHeaders.add(challengeHeader, challenge)
                    ex.sendResponseHeaders(status, -1)
                    ex.close()
                }
            }
            executor = Executors.newCachedThreadPool()
            start()
            servers += this
        }

    private fun originServer(challenge: String = "Basic realm=\"files\"") =
        server("Authorization", 401, "WWW-Authenticate", challenge)

    private fun client(
        auth: HttpAuth?, serverAuth: Boolean = true, proxy: Proxy? = null, user: String = "", pass: String = "",
    ) = HttpClientImpl(
        4, proxy, readTimeoutSeconds = 10, proxyUser = user, proxyPassword = pass, auth = auth, serverAuth = serverAuth,
    ).also { clients += it }

    private fun HttpClientImpl.status(url: String): Int =
        getResponse(url, null, null, null).getOrThrow().use { it.statusCode }

    private fun url(server: HttpServer) = "http://127.0.0.1:${server.address.port}/file"

    @Test
    fun server401_promptsAgainUntilAccepted() {
        val prompt = ScriptedPrompt(wrong, wrong, right)
        val srv = originServer()

        assertEquals(200, client(HttpAuth(prompt)).status(url(srv)), "download not authorized after the right pair")
        assertEquals(listOf(false, true, true), prompt.asked.map { it.second }, "prompts and their rejected flags")
        assertEquals("files", prompt.asked.first().first.realm, "realm not passed to the prompt")
    }

    @Test
    fun server401_cancelStopsThatDownload() {
        val prompt = ScriptedPrompt(null)
        val srv = originServer()
        val c = client(HttpAuth(prompt))

        assertEquals(401, c.status(url(srv)), "cancel should leave the 401")
        assertEquals(401, c.status(url(srv)), "cancelled download should keep failing")
        assertEquals(1, prompt.asked.size, "prompted again after cancel")
    }

    @Test
    fun nonBasicChallenge_doesNotPrompt() {
        val prompt = ScriptedPrompt(right)
        val srv = originServer(challenge = "Bearer realm=\"api\"")

        assertEquals(401, client(HttpAuth(prompt)).status(url(srv)), "Bearer challenge should stand")
        assertEquals(0, prompt.asked.size, "prompted for a Bearer challenge")
    }

    @Test
    fun serverAuthOff_doesNotPrompt() {
        val prompt = ScriptedPrompt(right)
        val srv = originServer()

        assertEquals(401, client(HttpAuth(prompt), serverAuth = false).status(url(srv)), "background client authenticated")
        assertEquals(0, prompt.asked.size, "background client prompted")
    }

    @Test
    fun parallelSegments_shareOnePrompt() {
        val prompt = ScriptedPrompt(right, delayMs = 300)
        val srv = originServer()
        val c = client(HttpAuth(prompt))
        val pool = Executors.newFixedThreadPool(8)
        val results = (1..8).map { pool.submit<Int> { c.status(url(srv)) } }.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(List(8) { 200 }, results, "every segment should get through")
        assertEquals(1, prompt.asked.size, "one dialog per scope, not per segment")
    }

    @Test
    fun secondDownload_reusesTypedCredentials() {
        val prompt = ScriptedPrompt(right)
        val srv = originServer()
        val auth = HttpAuth(prompt)

        assertEquals(200, client(auth).status(url(srv)), "first download")
        assertEquals(200, client(auth).status(url(srv)), "second download")
        assertEquals(1, prompt.asked.size, "second download prompted again")
    }

    @Test
    fun proxy407_rejectedConfiguredCredentials_promptUntilAccepted() {
        val prompt = ScriptedPrompt(wrong, right)
        val proxy = server("Proxy-Authorization", 407, "Proxy-Authenticate", "Basic realm=\"proxy\"")
        val p = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.address.port))
        val auth = HttpAuth(prompt)

        val status = client(auth, proxy = p, user = wrong.user, pass = wrong.password).status("http://example.invalid/file")
        assertEquals(200, status, "proxy did not accept the corrected credentials")
        assertEquals(listOf(true, true), prompt.asked.map { it.second }, "configured pair is tried before any prompt")
        assertEquals(true, prompt.asked.all { it.first.proxy }, "prompt not marked as proxy")

        // A new download with the same (still wrong) Settings uses what the user typed.
        val again = client(auth, proxy = p, user = wrong.user, pass = wrong.password).status("http://example.invalid/file")
        assertEquals(200, again, "typed proxy credentials not reused")
        assertEquals(2, prompt.asked.size, "prompted again for the next download")
    }

    @Test
    fun changedSettings_replaceTypedCredentials() {
        val auth = HttpAuth(ScriptedPrompt(right))
        val scope = AuthScope(proxy = true, host = "proxy", port = 8080, realm = null)
        val cancelled = AtomicBoolean(false)

        assertEquals(right, auth.onChallenge(scope, wrong, cancelled, configured = wrong), "typed pair after rejection")
        val edited = BasicCredentials("bob", "new")
        assertEquals(edited, auth.onChallenge(scope, null, cancelled, configured = edited), "Settings edit should win")
    }
}
