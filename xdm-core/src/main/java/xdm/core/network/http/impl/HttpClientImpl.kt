package xdm.core.network.http.impl

import okhttp3.*
import xdm.core.CoreConfig
import xdm.core.network.http.*
import xdm.core.util.Logger
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * OkHttp-backed client.
 *
 * TLS certificate and hostname verification use the platform defaults. Only when
 * [ignoreCertErrors] is true (an explicit, off-by-default user setting) are certificate chain and
 * hostname checks skipped, which leaves connections open to interception.
 */
class HttpClientImpl @JvmOverloads constructor(
    poolSize: Int,
    proxy: Proxy? = null,
    ignoreCertErrors: Boolean = false,
    /** How long a read may wait for data before failing with a timeout, so a stalled server is retried. */
    readTimeoutSeconds: Int = CoreConfig.DEFAULT_READ_TIMEOUT_SECONDS,
    /** Credentials for an authenticating HTTP proxy; empty user means the proxy needs no auth. */
    proxyUser: String = "",
    proxyPassword: String = "",
) : PoolingHttpClient {
    /**
     * Calls whose response is still open. OkHttp's dispatcher stops tracking a synchronous call once
     * `execute()` returns the headers, so `cancelAll()` cannot abort a thread blocked reading the body;
     * [close] cancels these instead.
     */
    private val openCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    private val dispatcher: Dispatcher = Dispatcher().apply {
        maxRequests = poolSize
        maxRequestsPerHost = poolSize
    }

    private val connectionPool: ConnectionPool = ConnectionPool(poolSize, 5, TimeUnit.SECONDS)
    private val client: OkHttpClient =
        OkHttpClient.Builder().dispatcher(dispatcher).connectionPool(connectionPool)
            .proxy(proxy)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(readTimeoutSeconds.toLong(), TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .apply { if (ignoreCertErrors) trustAllCertificates(this) else sharedTls(this) }
            .apply { if (proxy != null && proxyUser.isNotEmpty()) proxyAuthentication(this, proxy, proxyUser, proxyPassword) }
            .build()

    /**
     * Reuses one process-wide [SSLContext] instead of letting OkHttp build its own per client.
     *
     * A client is created per download, and OkHttp's default is `SSLContext.getInstance("TLS")` per
     * client. With Conscrypt as the first provider that allocates a fresh native BoringSSL context
     * and session cache each time; closing the client frees them, but the platform allocator keeps
     * the blocks, so the process footprint grew by ~0.8 MB per HTTPS download and never shrank.
     * Sharing the context also shares the TLS session cache, so repeat hosts resume instead of doing
     * a full handshake.
     */
    private fun sharedTls(builder: OkHttpClient.Builder) {
        val tls = sharedTlsConfig ?: return
        builder.sslSocketFactory(tls.first, tls.second)
    }

    /**
     * OkHttp does not consult [java.net.Authenticator] for proxies, so answer the proxy's 407
     * challenge here, with the credentials from the config. If the proxy refuses those (it comes
     * back 407 on a request that already carried the header), ask the default authenticator for
     * another pair, which is how the app gets to prompt the user — see [ProxyAuth.REJECTED_PROMPT].
     * Offering the same credentials twice would loop, so that ends the attempt instead.
     *
     * Only Basic is implemented; a proxy demanding Digest or NTLM is not supported. SOCKS
     * authentication does not come through here at all: the JDK socket layer asks the default
     * authenticator itself.
     */
    private fun proxyAuthentication(builder: OkHttpClient.Builder, proxy: Proxy, user: String, password: String) {
        val address = proxy.address() as? InetSocketAddress
        val configured = Credentials.basic(user, password)
        builder.proxyAuthenticator { _, response ->
            val alreadySent = response.request.header("Proxy-Authorization")
            val credential = if (alreadySent == null) configured else rejectedCredentials(address)
            if (credential == null || credential == alreadySent) return@proxyAuthenticator null
            response.request.newBuilder().header("Proxy-Authorization", credential).build()
        }
    }

    /** Asks the default [java.net.Authenticator] for credentials to replace the refused ones. */
    private fun rejectedCredentials(address: InetSocketAddress?): String? {
        if (address == null) return null
        Logger.info("XDM", "Proxy ${address.hostString}:${address.port} refused the configured credentials")
        val auth = java.net.Authenticator.requestPasswordAuthentication(
            address.hostString,
            address.address,
            address.port,
            "http",
            ProxyAuth.REJECTED_PROMPT,
            "basic",
            null,
            java.net.Authenticator.RequestorType.PROXY,
        ) ?: return null
        return Credentials.basic(auth.userName, String(auth.password))
    }

    private fun trustAllCertificates(builder: OkHttpClient.Builder) {
        Logger.info("XDM", "TLS certificate and hostname verification disabled by user setting")
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        builder.sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
    }

    override fun close() {
        openCalls.forEach { it.cancel() }
        openCalls.clear()
        client.dispatcher.cancelAll()
        dispatcher.executorService.shutdownNow()
        connectionPool.evictAll()
        Thread {
            Logger.info("XDM", "Trying connection pool clean up..")
            dispatcher.executorService.awaitTermination(Int.MAX_VALUE.toLong(), TimeUnit.HOURS)
            client.cache?.close()
            Logger.info("XDM", "Connection pool clean up.. triggering GC")
            System.gc()
        }.apply { isDaemon = true }.start()
    }

    override fun getResponse(url: String, headers: HeaderMap?, cookie: String?, range: Range): Result<HttpResponse> {
        val requestBuilder = Request.Builder().url(url).get()
        val cookies: MutableSet<String> = LinkedHashSet()

        headers?.forEach { (name, value) ->
            if (name.lowercase() == "cookie" && value.isNotEmpty()) {
                cookies.add(value.first())
            } else {
                for (headerValue in value) {
                    requestBuilder.addHeader(name, headerValue)
                }
            }
        }

        val end = range.end ?: 0
        if (end <= 0) {
            requestBuilder.addHeader("Range", String.format("bytes=%d-", range.start))
        } else {
            requestBuilder.addHeader(
                "Range", String.format("bytes=%d-%d", range.start, range.end)
            )
        }

        cookie?.let { cookies.add(it) }
        if (cookies.isNotEmpty()) {
            requestBuilder.addHeader("Cookie", cookies.joinToString("; "))
        }

        return kotlin.runCatching {
            val request = requestBuilder.build()
            val call = client.newCall(request)
            openCalls.add(call)
            val response = try {
                call.execute()
            } catch (e: Throwable) {
                openCalls.remove(call)
                throw e
            }
            val body = response.body ?: run {
                response.close()
                openCalls.remove(call)
                throw IOException("Body missing")
            }
            val inputStreamBody = body.byteStream()

            val finalUrl = response.request.url.toUri().toASCIIString()
            val redirected = response.priorResponse?.isRedirect ?: false

            Logger.info(response.headers)

            HttpResponseImpl(
                contentDisposition = response.headers.get("Content-Disposition"),
                statusCode = response.code,
                statusMessage = response.message,
                contentLength = if (body.contentLength() > 0) body.contentLength() else null,
                contentType = body.contentType()?.let { "${it.type}/${it.subtype}" }
                    ?: response.header("Content-Type"),
                lastModified = response.headers.getDate("Last-Modified")
                    ?.let { LocalDateTime.ofInstant(it.toInstant(), ZoneId.systemDefault()) },
                inputStream = inputStreamBody,
                isRedirected = redirected,
                finalUrl = finalUrl,
                closeCallback = {
                    try {
                        inputStreamBody.close()
                    } catch (ex: Exception) {// Swallow error
                    }
                    try {
                        body.close()
                    } catch (ex: Exception) {// Swallow error
                    }
                    try {
                        response.close()
                    } catch (ex: Exception) {// Swallow error
                    }
                    openCalls.remove(call)
                },
                headerCallback = {
                    response.header(
                        it
                    )
                })
        }
    }

    private companion object {
        /**
         * The socket factory and trust manager shared by every client, built on first use (after
         * `AppMain` has installed Conscrypt) and null if the platform refuses, in which case each
         * client falls back to OkHttp's own default.
         */
        val sharedTlsConfig: Pair<SSLSocketFactory, X509TrustManager>? by lazy {
            try {
                val trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                trustManagerFactory.init(null as KeyStore?)
                val trustManager = trustManagerFactory.trustManagers
                    .filterIsInstance<X509TrustManager>().firstOrNull()
                    ?: return@lazy null
                val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustManager), null) }
                Logger.info("XDM", "Shared TLS context: ${context.provider.name}")
                context.socketFactory to trustManager
            } catch (e: Exception) {
                Logger.error("XDM", "Could not create a shared TLS context, using per-client defaults", e)
                null
            }
        }
    }
}