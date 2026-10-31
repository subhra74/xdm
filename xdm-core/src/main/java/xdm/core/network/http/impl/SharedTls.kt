// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.network.http.impl

import xdm.core.util.CryptoProviders
import xdm.core.util.Logger
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * A TLS context built once and shared by every [HttpClientImpl] (and the update check, which uses one).
 *
 * A client is created per download, and OkHttp's default is `SSLContext.getInstance("TLS")` per client,
 * each with its own TLS session cache. Sharing one context shares that cache, so repeat hosts resume
 * instead of doing a full handshake, and a download no longer pays for building a context. (It was
 * introduced for Conscrypt, where each context is a native BoringSSL context whose memory the platform
 * allocator never returns.)
 *
 * Clients that skip certificate checks get [trustAll], a context of their own, built only once the user
 * turns that setting on. It can't share [instance]: a resumed session skips the trust manager, so a
 * validating client would resume a session a trust-all handshake had accepted (TestSharedTls).
 */
class SharedTls private constructor(context: SSLContext, val trustManager: X509TrustManager) {
    val socketFactory: SSLSocketFactory = context.socketFactory
    val providerName: String = context.provider.name

    companion object {
        /**
         * The validating context, from [CryptoProviders.preferred] when it is set (so set that first),
         * else from the JDK. Should the preferred provider refuse, the JDK's TLS is used instead; only if
         * that fails too is this null, and OkHttp's per-client default takes over.
         */
        @JvmStatic
        val instance: SharedTls? by lazy {
            try {
                validating(CryptoProviders.tlsContext(), CryptoProviders::trustManager)
            } catch (e: Exception) {
                Logger.error("XDM", "Could not create the shared TLS context, trying the JDK's", e)
                try {
                    validating(SSLContext.getInstance("TLS")) { it }
                } catch (e: Exception) {
                    Logger.error("XDM", "Could not create a shared TLS context, using per-client defaults", e)
                    null
                }
            }?.also { Logger.info("XDM", "Shared TLS context: ${it.providerName}") }
        }

        /** The context that accepts any server chain; the clients using it also skip hostname checks. */
        @JvmStatic
        val trustAll: SharedTls by lazy {
            trustingAll(CryptoProviders.tlsContext()).also { Logger.info("XDM", "Trust-all TLS context: ${it.providerName}") }
        }

        /** [context] initialized with the platform's trust manager, after [adapt]; tests pass their own. */
        @JvmStatic
        fun validating(context: SSLContext, adapt: (X509TrustManager) -> X509TrustManager): SharedTls {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            val platform = factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: throw IllegalStateException("No X509TrustManager from ${factory.algorithm}")
            return of(context, adapt(platform))
        }

        /** [context] initialized to accept any server chain. */
        @JvmStatic
        fun trustingAll(context: SSLContext): SharedTls = of(context, TrustAllManager())

        private fun of(context: SSLContext, trustManager: X509TrustManager): SharedTls {
            context.init(null, arrayOf<TrustManager>(trustManager), null)
            return SharedTls(context, trustManager)
        }
    }

    /** Named so the native image can register it, like [CryptoProviders]' trust manager. */
    private class TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
