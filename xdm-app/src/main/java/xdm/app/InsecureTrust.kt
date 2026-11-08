package xdm.app

import xdm.core.network.http.CertTrust
import xdm.core.util.CryptoProviders
import xdm.core.util.Logger
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * No certificate or hostname checks at all: the desktop's "Ignore certificate errors" setting and the
 * headless `--insecure` flag, both explicit and off by default. A TLS context of its own, built on
 * first use: it can't share the validating one ([xdm.core.network.http.impl.SharedTls]), since a
 * session this context accepted would be resumed there without a check.
 *
 * Desktop only. The Android app never has a way to skip checks; there the user trusts one certificate
 * for one host instead ([xdm.core.network.http.CertExceptions]).
 */
object InsecureTrust : CertTrust {
    override val trustManager: X509TrustManager = TrustAllManager()

    override val socketFactory: SSLSocketFactory by lazy {
        val context = CryptoProviders.tlsContext()
        context.init(null, arrayOf<TrustManager>(trustManager), null)
        Logger.info("XDM", "Trust-all TLS context: ${context.provider.name}")
        context.socketFactory
    }

    override val hostnameVerifier = HostnameVerifier { _, _ -> true }

    /** This, when the user turned certificate checks off; null for the normal checks. */
    fun ifEnabled(ignoreCertErrors: Boolean): CertTrust? = if (ignoreCertErrors) this else null

    /** Named so the native image can register it, like [CryptoProviders]' trust manager. */
    private class TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
