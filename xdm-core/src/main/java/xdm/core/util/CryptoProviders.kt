// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.util

import java.net.Socket
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * A security provider the app hands over for TLS and digests only, instead of installing it globally.
 *
 * The app sets Conscrypt here on every platform it has a native library for (BoringSSL: AES-GCM and SHA
 * with the CPU's instructions, picked at run time). It stays off the global provider list, so Cipher and
 * Mac keep coming from the JDK; a native image's JCE would also reject a provider created at run time.
 * Left null, everything uses the defaults.
 *
 * Set it before the first HTTP client or digest is created; the shared TLS contexts are built once.
 */
object CryptoProviders {
    @Volatile
    var preferred: Provider? = null

    /**
     * [base] (the JDK's trust manager) as the TLS context from [preferred] needs it. Conscrypt reports a
     * TLS 1.3 handshake's auth type as "GENERIC", which the JDK's end-entity check rejects ("Unknown
     * authType"); the JDK's own TLS 1.3 client passes "UNKNOWN" there, so that is what it gets. Validation
     * stays entirely the JDK's. Without [preferred], or for a trust manager that isn't extended, [base].
     */
    fun trustManager(base: X509TrustManager): X509TrustManager {
        if (preferred == null || base !is X509ExtendedTrustManager) return base
        return GenericAuthTrustManager(base)
    }

    /** `SSLContext.getInstance("TLS")` from [preferred] when set. */
    fun tlsContext(): SSLContext = preferred?.let { SSLContext.getInstance("TLS", it) } ?: SSLContext.getInstance("TLS")

    /** [algorithm] from [preferred] when it has one, else from the global providers. */
    fun messageDigest(algorithm: String): MessageDigest = preferred?.let {
        try {
            MessageDigest.getInstance(algorithm, it)
        } catch (_: NoSuchAlgorithmException) {
            null
        }
    } ?: MessageDigest.getInstance(algorithm)

    /**
     * Named (not anonymous) so the native image can register it: Conscrypt probes the trust manager's class
     * for Android's getNetworkSecurityPolicy() by reflection.
     */
    private class GenericAuthTrustManager(private val base: X509ExtendedTrustManager) : X509ExtendedTrustManager() {
        private fun auth(type: String) = if (type == "GENERIC") "UNKNOWN" else type

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            base.checkServerTrusted(chain, auth(authType))

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
            base.checkServerTrusted(chain, auth(authType), socket)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
            base.checkServerTrusted(chain, auth(authType), engine)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            base.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
            base.checkClientTrusted(chain, authType, socket)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
            base.checkClientTrusted(chain, authType, engine)

        override fun getAcceptedIssuers(): Array<X509Certificate> = base.acceptedIssuers
    }
}
