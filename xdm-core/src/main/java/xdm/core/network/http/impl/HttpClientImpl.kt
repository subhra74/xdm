package xdm.core.network.http.impl

import okhttp3.*
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import xdm.core.CoreConfig
import xdm.core.network.http.*
import xdm.core.util.Logger
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    /** Credentials for an authenticating HTTP proxy; empty user means none are configured. */
    proxyUser: String = "",
    proxyPassword: String = "",
    /** Asks the user when a proxy (407) or, with [serverAuth], a server (401) wants credentials. */
    private val auth: HttpAuth? = null,
    /** Answer a server's 401 Basic challenge; off for background fetches the user did not start. */
    serverAuth: Boolean = false,
) : PoolingHttpClient {
    /** Set when the user cancels a credentials prompt: this client stops asking. */
    private val authCancelled = AtomicBoolean(false)

    /** Set once the proxy has asked for NTLM: tunnels then skip OkHttp's preemptive Basic header. */
    private val proxyWantsNtlm = AtomicBoolean(false)

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
            .apply { if (proxy != null) proxyAuthentication(this, proxy, proxyUser, proxyPassword) }
            .apply { if (serverAuth && auth != null) serverAuthentication(this, auth) }
            .build()

    /**
     * Reuses one process-wide [SSLContext] instead of letting OkHttp build its own per client.
     *
     * A client is created per download, and OkHttp's default is `SSLContext.getInstance("TLS")` per
     * client, each with its own TLS session cache. Sharing one context shares that cache, so repeat
     * hosts resume instead of doing a full handshake, and a download no longer pays for building a
     * context. (It was introduced for Conscrypt, where each context was a native BoringSSL context
     * whose memory the platform allocator never returned.)
     */
    private fun sharedTls(builder: OkHttpClient.Builder) {
        val tls = sharedTlsConfig ?: return
        builder.sslSocketFactory(tls.first, tls.second)
    }

    /**
     * OkHttp does not consult [java.net.Authenticator] for proxies, so answer the proxy's 407 here:
     * first with the credentials from the config, then, each time the proxy refuses what was sent,
     * with what the user enters through [auth]. Without [auth] the configured pair is offered once.
     *
     * Basic and NTLM are implemented (see [ntlmStep]); Digest and Kerberos are not. SOCKS
     * authentication does not come through here at all: the JDK socket layer asks the default
     * authenticator itself.
     */
    private fun proxyAuthentication(builder: OkHttpClient.Builder, proxy: Proxy, user: String, password: String) {
        val address = proxy.address() as? InetSocketAddress ?: return
        val configured = if (user.isNotEmpty()) BasicCredentials(user, password) else null
        val scope = AuthScope(proxy = true, host = address.hostString, port = address.port, realm = null)
        fun credentials(sent: BasicCredentials?, scope: AuthScope): BasicCredentials? =
            if (auth != null) auth.onChallenge(scope, sent, authCancelled, configured) else configured?.takeIf { sent == null }

        builder.proxyAuthenticator { _, response ->
            val preemptive = response.header("Proxy-Authenticate").equals(PREEMPTIVE_CHALLENGE, ignoreCase = true)
            // An NTLM proxy answers a preemptive Basic header with a 407 anyway; do not hand it the password.
            if (preemptive && proxyWantsNtlm.get()) return@proxyAuthenticator null
            ntlmChallenge(response, "Proxy-Authenticate")?.let { challenge ->
                proxyWantsNtlm.set(true)
                return@proxyAuthenticator ntlmStep(response, challenge, "Proxy-Authorization") { sent ->
                    credentials(sent, scope.withNtlm())
                }
            }
            val sent = decodeBasic(response.request.header("Proxy-Authorization"))
            val next = credentials(sent, scope)
            // [HttpAuth] hands back the refused pair only if the user typed it again, so that is a
            // retry, not a loop; without it, the configured pair is only ever offered once.
            if (next == null) return@proxyAuthenticator null
            Logger.info("XDM", "Authenticating to proxy ${scope.host}:${scope.port} as ${next.user}")
            response.request.newBuilder().header("Proxy-Authorization", encodeBasic(next)).build()
        }
    }

    /**
     * Answers a server's 401 with credentials from [auth], asking the user again each time the server
     * refuses them: NTLM (or Negotiate, answered with NTLM) when offered, else Basic. A challenge
     * offering neither (Bearer, Digest) cannot be met with a user name and password, so the 401 stands.
     */
    private fun serverAuthentication(builder: OkHttpClient.Builder, auth: HttpAuth) {
        builder.authenticator { _, response ->
            val url = response.request.url
            ntlmChallenge(response, "WWW-Authenticate")?.let { challenge ->
                val scope = AuthScope(proxy = false, host = url.host, port = url.port, realm = null).withNtlm()
                return@authenticator ntlmStep(response, challenge, "Authorization") { sent ->
                    auth.onChallenge(scope, sent, authCancelled)
                }
            }
            val basic = response.challenges().firstOrNull { it.scheme.equals("Basic", ignoreCase = true) }
                ?: return@authenticator null
            val scope = AuthScope(proxy = false, host = url.host, port = url.port, realm = basic.realm)
            val sent = decodeBasic(response.request.header("Authorization"))
            val next = auth.onChallenge(scope, sent, authCancelled) ?: return@authenticator null
            Logger.info("XDM", "Authenticating to ${url.host} as ${next.user}")
            response.request.newBuilder().header("Authorization", encodeBasic(next)).build()
        }
    }

    /**
     * One step of the NTLM handshake for a 401 / 407 carrying [challenge], or null to let it stand.
     *
     * NTLM authenticates a connection (Type1, Type2, Type3 on one socket), but OkHttp sends a retry
     * on whichever pooled connection is idle. So each step is decided from the challenge and the
     * [NtlmAttempt] tagged on the request that drew it (one per proxy and per server): a Type2 is answered with a Type3; a bare
     * challenge after a Type1 or Type3 restarts the handshake once with the same credentials (it may
     * have crossed connections), and after that counts as a refusal, which asks [credentials] again
     * with the refused pair.
     */
    private fun ntlmStep(
        response: Response,
        challenge: NtlmChallenge,
        header: String,
        credentials: (sent: BasicCredentials?) -> BasicCredentials?,
    ): Request? {
        // The proxy's and the server's handshakes are tracked apart: a request can carry both, and
        // mistaking one for the other would answer a server with the proxy's credentials.
        val proxy = header == "Proxy-Authorization"
        val attempt = if (proxy) response.request.tag(ProxyNtlm::class.java)?.attempt else response.request.tag(ServerNtlm::class.java)?.attempt
        val type2 = challenge.token?.let { Ntlm.parseType2(it) }
        if (type2 != null && attempt != null && !attempt.sentType3) {
            val (domain, user) = Ntlm.splitUser(attempt.credentials.user)
            val type3 = Ntlm.type3(type2, user, domain, attempt.credentials.password)
            return ntlmRequest(response, header, challenge.scheme, type3, NtlmAttempt(attempt.credentials, true, attempt.restarts))
        }
        if (attempt != null && attempt.restarts < NTLM_RESTARTS) {
            val restart = NtlmAttempt(attempt.credentials, false, attempt.restarts + 1)
            return ntlmRequest(response, header, challenge.scheme, Ntlm.type1(), restart)
        }
        val next = credentials(attempt?.credentials) ?: return null
        val target = if (proxy) "proxy" else response.request.url.host
        Logger.info("XDM", "NTLM authentication to $target as ${next.user}")
        return ntlmRequest(response, header, challenge.scheme, Ntlm.type1(), NtlmAttempt(next, false, 0))
    }

    private fun ntlmRequest(response: Response, header: String, scheme: String, message: ByteArray, attempt: NtlmAttempt) =
        response.request.newBuilder()
            .header(header, scheme + " " + message.toByteString().base64())
            .apply {
                if (header == "Proxy-Authorization") tag(ProxyNtlm::class.java, ProxyNtlm(attempt))
                else tag(ServerNtlm::class.java, ServerNtlm(attempt))
            }
            .build()

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
        connectionPool.evictAll()
        // The dispatcher's executor is deliberately left alone: every request goes through
        // `call.execute()` (synchronous), which never uses it, so touching `executorService` here
        // only forced the thread pool to be created just to shut it down again — once per download.
        Thread {
            Logger.info("XDM", "Trying connection pool clean up..")
            client.cache?.close()
            Logger.info("XDM", "Connection pool clean up.. triggering GC")
            System.gc()
        }.apply { isDaemon = true }.start()
    }

    override fun getResponse(url: String, headers: HeaderMap?, cookie: String?, range: Range?): Result<HttpResponse> {
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

        if (range != null) {
            val end = range.end ?: 0
            if (end <= 0) {
                requestBuilder.addHeader("Range", String.format("bytes=%d-", range.start))
            } else {
                requestBuilder.addHeader(
                    "Range", String.format("bytes=%d-%d", range.start, range.end)
                )
            }
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

            // Set-Cookie values are session credentials; log that they came, not what they are.
            Logger.info(response.headers.joinToString("\n") { (name, value) ->
                if (name.equals("Set-Cookie", ignoreCase = true)) "$name: <redacted>" else "$name: $value"
            })

            HttpResponseImpl(
                contentDisposition = response.headers.get("Content-Disposition"),
                statusCode = response.code,
                statusMessage = response.message,
                contentLength = if (body.contentLength() > 0) body.contentLength() else null,
                contentType = body.contentType()?.let { "${it.type}/${it.subtype}" }
                    ?: response.header("Content-Type"),
                lastModifiedMillis = response.headers.getDate("Last-Modified")?.time,
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
        /** The challenge OkHttp puts on the fake 407 it uses to ask for preemptive tunnel credentials. */
        const val PREEMPTIVE_CHALLENGE = "OkHttp-Preemptive"

        /** Handshakes restarted with the same credentials before a bare NTLM challenge counts as a refusal. */
        const val NTLM_RESTARTS = 1

        /**
         * The socket factory and trust manager shared by every client, built on first use and null if
         * the platform refuses, in which case each client falls back to OkHttp's own default.
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

/** What an NTLM request carried, so the next challenge can tell a Type2 reply from a refusal. */
private class NtlmAttempt(val credentials: BasicCredentials, val sentType3: Boolean, val restarts: Int)

/** Request tags: the proxy's handshake and the server's, kept apart. */
private class ProxyNtlm(val attempt: NtlmAttempt)
private class ServerNtlm(val attempt: NtlmAttempt)

/** An `NTLM` or `Negotiate` challenge, with its token (a Type2) or null for a bare one. */
private class NtlmChallenge(val scheme: String, val token: ByteArray?)

/**
 * The NTLM challenge in [response]'s [header], preferring `NTLM` over `Negotiate` (answered with raw
 * NTLM tokens, which Windows' Negotiate package accepts). Null when neither is offered, or when a
 * Negotiate token is not NTLM (a Kerberos reply).
 */
private fun ntlmChallenge(response: Response, header: String): NtlmChallenge? {
    val entries = response.headers(header).flatMap { it.split(',') }.map { it.trim() }
    fun find(scheme: String) = entries.firstOrNull { it.equals(scheme, ignoreCase = true) || it.startsWith("$scheme ", ignoreCase = true) }
    val entry = find("NTLM") ?: find("Negotiate") ?: return null
    val space = entry.indexOf(' ')
    if (space < 0) return NtlmChallenge(entry, null)
    val token = entry.substring(space + 1).trim().decodeBase64()?.toByteArray()
        ?.takeIf { Ntlm.isNtlmMessage(it) } ?: return null
    return NtlmChallenge(entry.substring(0, space), token)
}

private fun encodeBasic(c: BasicCredentials): String = Credentials.basic(c.user, c.password, Charsets.UTF_8)

/** The pair inside a `Basic` authorization header, or null for none or another scheme (Bearer). */
private fun decodeBasic(header: String?): BasicCredentials? {
    if (header == null || !header.startsWith("Basic ", ignoreCase = true)) return null
    val decoded = header.substring(6).trim().decodeBase64()?.utf8() ?: return null
    val colon = decoded.indexOf(':')
    if (colon < 0) return null
    return BasicCredentials(decoded.substring(0, colon), decoded.substring(colon + 1))
}
