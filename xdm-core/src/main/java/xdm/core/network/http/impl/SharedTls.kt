// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.network.http.impl

import okhttp3.internal.tls.OkHostnameVerifier
import xdm.core.network.http.CertExceptions
import xdm.core.network.http.UntrustedCertificateException
import xdm.core.util.CryptoProviders
import xdm.core.util.Logger
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSessionContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * The TLS context built once and shared by every [HttpClientImpl] (and the update check, which uses one).
 *
 * A client is created per download, and OkHttp's default is `SSLContext.getInstance("TLS")` per client,
 * each with its own TLS session cache. Sharing one context shares that cache, so repeat hosts resume
 * instead of doing a full handshake, and a download no longer pays for building a context. (It was
 * introduced for Conscrypt, where each context is a native BoringSSL context whose memory the platform
 * allocator never returns.)
 *
 * Certificates are checked by the platform. One it refuses is still accepted when the user trusted it
 * for that host ([CertExceptions]); otherwise the handshake fails with an [UntrustedCertificateException]
 * saying what trusting it would take. Every client uses this one context: there is no context that
 * skips checks, so a resumed session was always checked by these rules. A resumed session skips the
 * trust manager, though, so [hostnameVerifier] checks again that a certificate the platform refused is
 * still trusted for the host, and [CertExceptions.remove] drops the host's cached sessions.
 */
class SharedTls private constructor(private val context: SSLContext, val trustManager: X509TrustManager) {
    val socketFactory: SSLSocketFactory = context.socketFactory
    val providerName: String = context.provider.name

    /** The client session cache; null when the provider has none. */
    private val sessions: SSLSessionContext? get() = runCatching { context.clientSessionContext }.getOrNull()

    /**
     * OkHttp's hostname check, else (a certificate for another name) the user's exception for this host.
     * A certificate the platform refused passes only while it is trusted for this host, which also holds
     * for a resumed session. Refusals go to [onRefused] as host and fingerprint.
     */
    fun hostnameVerifier(onRefused: ((host: String, fingerprint: String) -> Unit)?): HostnameVerifier =
        ExceptionHostnameVerifier(onRefused)

    /** Drops cached sessions with [host], so the next connection to it does a full handshake. */
    private fun forget(host: String) {
        val cache = sessions ?: return
        var dropped = 0
        try {
            for (id in cache.ids.toList()) {
                val session = cache.getSession(id) ?: continue
                if (session.peerHost.equals(host, ignoreCase = true)) {
                    session.invalidate()
                    dropped++
                }
            }
        } catch (e: Exception) {
            Logger.error("XDM", "Could not drop the TLS sessions of $host", e)
        }
        Logger.info("XDM", "Dropped $dropped TLS session(s) of $host")
    }

    companion object {
        private val lazyInstance = lazy {
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

        /**
         * The context, from [CryptoProviders.preferred] when it is set (so set that first), else from the
         * JDK. Should the preferred provider refuse, the JDK's TLS is used instead; only if that fails too
         * is this null, and OkHttp's per-client default takes over (without certificate exceptions).
         */
        @JvmStatic
        val instance: SharedTls? by lazyInstance

        /** Leaf fingerprints the platform refused in this process; see [hostnameVerifier]. */
        private val platformRefused: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * [context] initialized with the platform's trust manager, after [adapt], and the certificate
         * exceptions on top; tests pass their own context.
         */
        @JvmStatic
        fun validating(context: SSLContext, adapt: (X509TrustManager) -> X509TrustManager): SharedTls {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            val platform = factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: throw IllegalStateException("No X509TrustManager from ${factory.algorithm}")
            val trustManager = ExceptionTrustManager(adapt(platform))
            context.init(null, arrayOf<TrustManager>(trustManager), null)
            return SharedTls(context, trustManager)
        }

        /** Called by [CertExceptions.remove]; a no-op until the context exists. */
        internal fun forgetHost(host: String) {
            if (lazyInstance.isInitialized()) lazyInstance.value?.forget(host)
        }

        /** The host the handshake on [socket] / [engine] is with, when the TLS stack knows it. */
        private fun hostOf(socket: Socket?): String? =
            (socket as? SSLSocket)?.let { runCatching { it.handshakeSession?.peerHost }.getOrNull() }?.lowercase()

        private fun hostOf(engine: SSLEngine?): String? =
            engine?.let { runCatching { it.handshakeSession?.peerHost ?: it.peerHost }.getOrNull() }?.lowercase()

        private fun leafFingerprint(session: SSLSession): String? =
            runCatching { session.peerCertificates.firstOrNull() }.getOrNull()?.let { CertExceptions.fingerprint(it) }
    }

    /**
     * The platform's checks ([base]), then the user's exceptions for a chain they refuse. Always throws
     * when the chain is neither: there is no path that accepts a certificate nobody trusted.
     * Named (not anonymous) so the native image can register it: Conscrypt probes the trust manager's
     * class for Android's getNetworkSecurityPolicy() by reflection.
     */
    private class ExceptionTrustManager(private val base: X509TrustManager) : X509ExtendedTrustManager() {
        private val extended = base as? X509ExtendedTrustManager

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            check(chain, null) { base.checkServerTrusted(chain, authType) }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
            check(chain, hostOf(socket)) {
                if (extended != null) extended.checkServerTrusted(chain, authType, socket) else base.checkServerTrusted(chain, authType)
            }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
            check(chain, hostOf(engine)) {
                if (extended != null) extended.checkServerTrusted(chain, authType, engine) else base.checkServerTrusted(chain, authType)
            }

        private inline fun check(chain: Array<X509Certificate>, host: String?, platform: () -> Unit) {
            try {
                platform()
            } catch (e: CertificateException) {
                val leaf = chain.firstOrNull() ?: throw e
                val fingerprint = CertExceptions.fingerprint(leaf)
                platformRefused.add(fingerprint)
                val accepted = if (host != null) CertExceptions.isAccepted(host, fingerprint) else CertExceptions.isAcceptedFingerprint(fingerprint)
                if (!accepted) throw UntrustedCertificateException(host, fingerprint, e)
                Logger.info("XDM", "Certificate $fingerprint accepted by the user's exception" + (host?.let { " for $it" } ?: ""))
            }
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            base.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
            if (extended != null) extended.checkClientTrusted(chain, authType, socket) else base.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
            if (extended != null) extended.checkClientTrusted(chain, authType, engine) else base.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = base.acceptedIssuers
    }

    /** See [hostnameVerifier]. */
    private class ExceptionHostnameVerifier(private val onRefused: ((String, String) -> Unit)?) : HostnameVerifier {
        override fun verify(hostname: String, session: SSLSession): Boolean {
            val host = hostname.lowercase()
            val fingerprint = leafFingerprint(session)
            val trusted = fingerprint == null || fingerprint !in platformRefused || CertExceptions.isAccepted(host, fingerprint)
            val ok = trusted && (OkHostnameVerifier.verify(hostname, session) ||
                    (fingerprint != null && CertExceptions.isAccepted(host, fingerprint)))
            if (!ok && fingerprint != null) {
                Logger.info("XDM", "Certificate $fingerprint refused for $host")
                onRefused?.invoke(host, fingerprint)
            }
            return ok
        }
    }
}
