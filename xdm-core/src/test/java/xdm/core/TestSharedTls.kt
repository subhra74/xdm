// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.conscrypt.Conscrypt
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import xdm.core.network.http.CertExceptions
import xdm.core.network.http.isTlsVerificationError
import xdm.core.network.http.untrustedCertificate
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.network.http.impl.SharedTls
import xdm.core.util.CryptoProviders
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * The shared validating context against local self-signed servers: the platform refuses them, the
 * refusal carries the certificate's fingerprint, and only a certificate the user trusted for that host
 * ([CertExceptions]) gets through, also after a session was cached and the exception removed. On the
 * JDK's TLS through [HttpClientImpl], and on Conscrypt.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestSharedTls {
    /** A TLS server answering every request with "ok", and its certificate's fingerprint. */
    private class Server(val socket: SSLServerSocket, val fingerprint: String, val keys: KeyStore) {
        val url get() = "https://127.0.0.1:${socket.localPort}/"
    }

    private lateinit var main: Server

    /** Another key, also named for 127.0.0.1. */
    private lateinit var other: Server

    /** A certificate for other.example only, served on 127.0.0.1. */
    private lateinit var wrongName: Server

    private lateinit var dir: File

    @BeforeAll
    fun startServers(@TempDir dir: File) {
        this.dir = dir
        main = server(dir, "main", "SAN=ip:127.0.0.1")
        other = server(dir, "other", "SAN=ip:127.0.0.1")
        wrongName = server(dir, "wrong", "SAN=dns:other.example")
    }

    @AfterAll
    fun stopServers() {
        listOf(main, other, wrongName).forEach { it.socket.close() }
    }

    @BeforeEach
    fun freshExceptions() {
        CertExceptions.init(File(dir, "data-${System.nanoTime()}").apply { mkdirs() }.path)
    }

    private fun server(dir: File, name: String, san: String): Server {
        val store = File(dir, "$name.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=$name", "-ext", san, "-validity", "2",
            "-storetype", "PKCS12", "-keystore", store.path, "-storepass", "changeit", "-keypass", "changeit",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        assertEquals(0, process.waitFor(), "keytool: $output")

        val keys = KeyStore.getInstance("PKCS12").apply { FileInputStream(store).use { load(it, "changeit".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "changeit".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val socket = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        thread(isDaemon = true, name = "tls-test-server-$name") {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    try {
                        client.use {
                            val input = it.getInputStream().bufferedReader()
                            while (input.readLine()?.isNotEmpty() == true) Unit
                            it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                        }
                    } catch (_: Exception) { // a rejected handshake
                    }
                }
            }
        }
        return Server(socket, CertExceptions.fingerprint(keys.getCertificate("server")), keys)
    }

    /** A client of the shared context recording what it was refused. */
    private class Client {
        val refusals: MutableList<Pair<String, String>> = Collections.synchronizedList(ArrayList())
        val http = HttpClientImpl(1, onRefused = { host, fp -> refusals += host to fp })

        fun get(url: String): Result<Int> = http.getResponse(url, null, null, null).map { r -> r.use { it.statusCode } }
    }

    private fun <T> withClient(block: (Client) -> T): T {
        val c = Client()
        try {
            return block(c)
        } finally {
            c.http.close()
        }
    }

    /** An OkHttp client over [tls] whose hostname check reports to [refusals]. */
    private fun okClient(tls: SharedTls, refusals: MutableList<Pair<String, String>>) = OkHttpClient.Builder()
        .sslSocketFactory(tls.socketFactory, tls.trustManager)
        .hostnameVerifier(tls.hostnameVerifier { host, fp -> refusals += host to fp })
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun refusalList(): MutableList<Pair<String, String>> = Collections.synchronizedList(ArrayList())

    @Test
    fun refused_reportsHostAndFingerprint() = withClient { c ->
        val error = c.get(main.url).exceptionOrNull()
        assertTrue(error is SSLException, "self-signed server refused, got $error")
        assertTrue(isTlsVerificationError(error), "still a verification error")
        assertEquals(main.fingerprint, untrustedCertificate(error)?.fingerprint, "fingerprint in the cause chain")
        assertEquals(listOf("127.0.0.1" to main.fingerprint), c.refusals.toList(), "onRefused")
    }

    @Test
    fun acceptedAfterAdd_otherCertStillRefused() = withClient { c ->
        CertExceptions.add("127.0.0.1", main.fingerprint)
        assertEquals(200, c.get(main.url).getOrThrow(), "trusted certificate accepted")
        assertTrue(c.refusals.isEmpty(), "nothing refused: ${c.refusals}")

        val error = c.get(other.url).exceptionOrNull()
        assertTrue(error is SSLException, "a different certificate for the same host is refused, got $error")
        assertEquals(listOf("127.0.0.1" to other.fingerprint), c.refusals.toList(), "the other certificate reported")
    }

    @Test
    fun exceptionForAnotherHost_refused() = withClient { c ->
        CertExceptions.add("localhost", main.fingerprint)
        val error = c.get(main.url).exceptionOrNull()
        assertTrue(error is SSLException, "trusted for another host only, got $error")
        assertEquals(listOf("127.0.0.1" to main.fingerprint), c.refusals.toList(), "refusal reported for this host")
    }

    @Test
    fun hostnameMismatch_refusedThenAccepted() = withClient { c ->
        val error = c.get(wrongName.url).exceptionOrNull()
        assertTrue(error is SSLException, "untrusted and misnamed, got $error")
        assertEquals(listOf("127.0.0.1" to wrongName.fingerprint), c.refusals.toList(), "reported")

        CertExceptions.add("127.0.0.1", wrongName.fingerprint)
        assertEquals(200, c.get(wrongName.url).getOrThrow(), "the exception covers the name too")
    }

    /** A certificate the platform trusts but named for another host: the hostname check refuses and reports it. */
    @Test
    fun trustedCertificateWrongName_verifierRefusesThenAccepts() {
        val anchors = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("ca", wrongName.keys.getCertificate("server"))
        }
        val trusting = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(anchors) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val tls = SharedTls.validating(SSLContext.getInstance("TLS")) { trusting }
        val refusals = refusalList()
        val client = okClient(tls, refusals)
        val request = Request.Builder().url(wrongName.url).build()

        val error = runCatching { client.newCall(request).execute().close() }.exceptionOrNull()
        assertTrue(isTlsVerificationError(error), "hostname mismatch refused, got $error")
        assertEquals(listOf("127.0.0.1" to wrongName.fingerprint), refusals.toList(), "verifier reported the refusal")

        CertExceptions.add("127.0.0.1", wrongName.fingerprint)
        client.newCall(request).execute().use { assertEquals(200, it.code, "accepted once trusted for the host") }
    }

    /** A cached session must not outlive the exception it was accepted under. */
    @Test
    fun removal_noSessionResumptionBypass() {
        CertExceptions.add("127.0.0.1", main.fingerprint)
        withClient { c -> assertEquals(200, c.get(main.url).getOrThrow(), "accepted while trusted") }
        CertExceptions.remove("127.0.0.1", main.fingerprint)
        withClient { c ->
            val error = c.get(main.url).exceptionOrNull()
            assertTrue(error is SSLException, "refused again after removal, got $error")
            assertEquals(listOf("127.0.0.1" to main.fingerprint), c.refusals.toList(), "reported again")
        }
    }

    /**
     * Even with the session left in the cache (removal drops only the shared context's sessions), the
     * hostname check refuses a resumed session whose certificate the platform refused and the user no
     * longer trusts.
     */
    @Test
    fun resumedSessionWithoutException_refusedByVerifier() {
        val tls = SharedTls.validating(SSLContext.getInstance("TLS")) { it }
        val refusals = refusalList()
        val request = Request.Builder().url(main.url).build()
        CertExceptions.add("127.0.0.1", main.fingerprint)
        okClient(tls, refusals).newCall(request).execute().use { assertEquals(200, it.code, "accepted while trusted") }

        CertExceptions.remove("127.0.0.1", main.fingerprint)
        val error = runCatching { okClient(tls, refusals).newCall(request).execute().close() }.exceptionOrNull()
        assertTrue(isTlsVerificationError(error), "refused, resumed or not, got $error")
        assertEquals(listOf("127.0.0.1" to main.fingerprint), refusals.toList(), "reported")
    }

    @Test
    fun conscrypt_refusedWithFingerprintThenAccepted() {
        assumeTrue(Conscrypt.isAvailable(), "no Conscrypt native library on this platform")
        val provider = Conscrypt.newProvider()
        val previous = CryptoProviders.preferred
        CryptoProviders.preferred = provider
        val tls = try {
            SharedTls.validating(SSLContext.getInstance("TLS", provider), CryptoProviders::trustManager)
        } finally {
            CryptoProviders.preferred = previous
        }
        assertEquals(provider.name, tls.providerName, "context provider")
        val client = okClient(tls, refusalList())
        val request = Request.Builder().url(main.url).build()

        val error = runCatching { client.newCall(request).execute().close() }.exceptionOrNull()
        assertTrue(error is SSLException, "self-signed server refused, got $error")
        val untrusted = untrustedCertificate(error)
        assertNotNull(untrusted, "Conscrypt keeps the cause chain: $error")
        assertEquals(main.fingerprint, untrusted!!.fingerprint, "fingerprint")
        assertEquals("127.0.0.1", untrusted.host, "host from the socket overload")

        CertExceptions.add("127.0.0.1", main.fingerprint)
        client.newCall(request).execute().use { assertEquals(200, it.code, "accepted once trusted") }
        assertNotEquals(main.fingerprint, other.fingerprint, "distinct keys")
        val again = runCatching { client.newCall(Request.Builder().url(other.url).build()).execute().close() }.exceptionOrNull()
        assertEquals(other.fingerprint, untrustedCertificate(again)?.fingerprint, "a different certificate is still refused")
    }
}
