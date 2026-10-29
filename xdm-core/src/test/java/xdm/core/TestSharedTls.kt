// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.conscrypt.Conscrypt
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.network.http.impl.SharedTls
import xdm.core.util.CryptoProviders
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * Every validating client shares one TLS context, and a client that ignores certificate errors uses the
 * other one: a validating client rejects a self-signed server even right after a trust-all client reached
 * it. With one context for both, it resumed the trust-all client's session and never checked the
 * certificate. On the JDK's TLS through [HttpClientImpl], and on Conscrypt.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestSharedTls {
    private lateinit var server: SSLServerSocket

    @BeforeAll
    fun startServer(@TempDir dir: File) {
        val store = File(dir, "server.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1", "-validity", "2",
            "-storetype", "PKCS12", "-keystore", store.path, "-storepass", "changeit", "-keypass", "changeit",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        assertEquals(0, process.waitFor(), "keytool: $output")

        val keys = KeyStore.getInstance("PKCS12").apply { FileInputStream(store).use { load(it, "changeit".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "changeit".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        thread(isDaemon = true, name = "tls-test-server") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    try {
                        socket.use {
                            val input = it.getInputStream().bufferedReader()
                            while (input.readLine()?.isNotEmpty() == true) Unit
                            it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                        }
                    } catch (_: Exception) { // a rejected handshake
                    }
                }
            }
        }
    }

    @AfterAll
    fun stopServer() {
        server.close()
    }

    private val url get() = "https://127.0.0.1:${server.localPort}/"

    @Test
    fun httpClientImpl_validatesAfterTrustAllHandshake() {
        val trustAll = HttpClientImpl(1, ignoreCertErrors = true)
        val validating = HttpClientImpl(1)
        try {
            val ok = trustAll.getResponse(url, null, null, null).getOrThrow()
            val body = ok.use { it.inputStream.readBytes().decodeToString() }
            assertEquals(200, ok.statusCode, "trust-all client reaches the self-signed server")
            assertEquals("ok", body, "body")

            val refused = validating.getResponse(url, null, null, null).exceptionOrNull()
            assertTrue(refused is SSLException, "validating client rejects the self-signed server, got $refused")

            val again = trustAll.getResponse(url, null, null, null).getOrThrow()
            again.close()
            assertEquals(200, again.statusCode, "a rejected handshake does not change what the trust-all client accepts")
            assertTrue(SharedTls.instance !== SharedTls.trustAll, "trust-all has a context of its own")
        } finally {
            trustAll.close()
            validating.close()
        }
    }

    @Test
    fun conscrypt_validatesAfterTrustAllHandshake() {
        assumeTrue(Conscrypt.isAvailable(), "no Conscrypt native library on this platform")
        val provider = Conscrypt.newProvider()
        val previous = CryptoProviders.preferred
        CryptoProviders.preferred = provider
        val validating = try {
            SharedTls.validating(SSLContext.getInstance("TLS", provider), CryptoProviders::trustManager)
        } finally {
            CryptoProviders.preferred = previous
        }
        val trustingAll = SharedTls.trustingAll(SSLContext.getInstance("TLS", provider))
        assertEquals(provider.name, validating.providerName, "context provider")

        fun client(trustAll: Boolean) = OkHttpClient.Builder()
            .apply {
                val tls = if (trustAll) trustingAll else validating
                sslSocketFactory(tls.socketFactory, tls.trustManager)
                if (trustAll) hostnameVerifier { _, _ -> true }
            }
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()
        client(trustAll = true).newCall(request).execute().use {
            assertEquals(200, it.code, "trust-all client reaches the self-signed server")
        }
        val refused = runCatching { client(trustAll = false).newCall(request).execute().close() }.exceptionOrNull()
        assertTrue(refused is SSLException, "validating client rejects the self-signed server, got $refused")
    }
}
