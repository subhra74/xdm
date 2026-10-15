package xdm.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.downloaders.web.http.HttpDownloaderTask
import xdm.core.network.http.AuthScope
import xdm.core.network.http.BasicCredentials
import xdm.core.network.http.CredentialPrompt
import xdm.core.network.http.HttpAuth
import xdm.core.network.http.impl.HttpClientImpl
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections

/**
 * NTLM against a real server (IIS with Windows Authentication), optionally through a real proxy.
 * Skipped unless `XDM_NTLM_BASE` is set. The server must serve, under both `/auth/` (Negotiate, NTLM)
 * and `/negotiate/` (Negotiate only), a `hello.txt` and a `big.zip` (see private-docs/docs/ntlm.md).
 *
 * - `XDM_NTLM_BASE`: `http://host` or `https://host` (certificate checks are off for the test).
 * - `XDM_NTLM_USER`: `DOMAIN\user`; `XDM_NTLM_PASSWORD_FILE`: a file holding the password.
 * - `XDM_NTLM_PROXY`: optional `host:port` of an HTTP proxy, with optional `XDM_NTLM_PROXY_USER` and
 *   `XDM_NTLM_PROXY_PASSWORD_FILE` as the Settings pair.
 */
class TestNtlmLive : HttpDownloadTestBase() {
    private val base = System.getenv("XDM_NTLM_BASE")?.takeIf { it.isNotBlank() }?.trimEnd('/')
    private val right by lazy {
        BasicCredentials(System.getenv("XDM_NTLM_USER"), File(System.getenv("XDM_NTLM_PASSWORD_FILE")).readText().trim())
    }
    private val proxy = System.getenv("XDM_NTLM_PROXY")?.takeIf { it.isNotBlank() }?.let {
        Proxy(Proxy.Type.HTTP, InetSocketAddress(it.substringBeforeLast(':'), it.substringAfterLast(':').toInt()))
    }
    private val proxyUser = System.getenv("XDM_NTLM_PROXY_USER") ?: ""
    private val proxyPassword = System.getenv("XDM_NTLM_PROXY_PASSWORD_FILE")?.takeIf { it.isNotBlank() }?.let { File(it).readText().trim() } ?: ""
    private val liveClients = mutableListOf<HttpClientImpl>()
    private val liveTasks = mutableListOf<HttpDownloaderTask>()

    @AfterEach
    fun closeLive() {
        liveTasks.forEach { runCatching { it.stop() } }
        liveClients.forEach { it.close() }
    }

    private class ScriptedPrompt(vararg answers: BasicCredentials?) : CredentialPrompt {
        private val queue = ArrayDeque(answers.toList())
        val asked: MutableList<Pair<AuthScope, Boolean>> = Collections.synchronizedList(mutableListOf())
        override fun ask(scope: AuthScope, rejected: Boolean): BasicCredentials? {
            asked += scope to rejected
            return synchronized(queue) { queue.removeFirstOrNull() }
        }
    }

    private fun client(prompt: ScriptedPrompt) = HttpClientImpl(
        8, proxy, ignoreCertErrors = true, readTimeoutSeconds = 30, proxyUser = proxyUser, proxyPassword = proxyPassword,
        auth = HttpAuth(prompt), serverAuth = true,
    ).also { liveClients += it }

    private fun HttpClientImpl.fetch(path: String): Pair<Int, String> =
        getResponse("$base$path", null, null, null).getOrThrow().use { it.statusCode to it.inputStream.readBytes().toString(Charsets.UTF_8).trim() }

    private fun live() = assumeTrue(base != null, "XDM_NTLM_BASE not set")

    /** Prompts for the server only; a proxy is answered from its Settings pair. */
    private fun ScriptedPrompt.serverPrompts() = asked.filter { !it.first.proxy }

    @Test
    fun auth_ntlmOffered_accepted() {
        live()
        val prompt = ScriptedPrompt(right)
        assertEquals(200 to "hello from auth", client(prompt).fetch("/auth/hello.txt"), "/auth with NTLM")
        assertEquals(1, prompt.serverPrompts().size, "one prompt")
    }

    @Test
    fun negotiateOnly_answeredWithNtlm_accepted() {
        live()
        val prompt = ScriptedPrompt(right)
        assertEquals(200 to "hello from negotiate", client(prompt).fetch("/negotiate/hello.txt"), "/negotiate with NTLM tokens")
    }

    @Test
    fun wrongPassword_thenRight_promptsAgain() {
        live()
        val prompt = ScriptedPrompt(BasicCredentials(right.user, "wrong-password"), right)
        assertEquals(200, client(prompt).fetch("/auth/hello.txt").first, "right pair after a wrong one")
        assertEquals(listOf(false, true), prompt.serverPrompts().map { it.second }, "prompts and their rejected flags")
    }

    @Test
    fun cancel_leaves401() {
        live()
        val prompt = ScriptedPrompt(null)
        assertEquals(401, client(prompt).fetch("/auth/hello.txt").first, "cancel should leave the 401")
    }

    @Test
    fun auth_segmentedDownload_completes() = segmentedDownload("/auth/big.zip")

    @Test
    fun negotiateOnly_segmentedDownload_completes() = segmentedDownload("/negotiate/big.zip")

    /** A real 8-segment download: every connection runs its own handshake, the user is asked once. */
    private fun segmentedDownload(path: String) {
        live()
        val prompt = ScriptedPrompt(right)
        val host = host()
        val info = HttpDownloadTaskInfo(
            id = 1, url = "$base$path", fileName = "big.zip", respectFileName = false, cookie = null, headers = null,
            origin = null, autoCategorize = false, defaultDownloadFolder = tmpDir.absolutePath,
            userSelectedDownloadFolder = null, maxPiece = 0, authInfo = null, knownFileSize = null,
        )
        HttpDownloaderTask(info, host, client(prompt), work.absolutePath, TestConfig(8)).also { liveTasks += it }.start()

        awaitSuccess(host, timeoutSec = 300)
        assertEquals(200L * 1024 * 1024, host.finalFile!!.length(), "file size")
        assertTrue(host.maxSegmentsSeen.get() > 1, "expected a segmented download, saw ${host.maxSegmentsSeen.get()}")
        assertEquals(1, prompt.serverPrompts().size, "one prompt for all segments")
    }
}
