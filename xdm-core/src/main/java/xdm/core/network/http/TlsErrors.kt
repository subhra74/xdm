// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.network.http

import java.security.cert.CertificateException
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * True when [error] (or one of its causes) means the server's TLS certificate or hostname could not
 * be verified. Retrying won't help with these; the user has to trust the server explicitly.
 *
 * Only handshake/verification failures count. Other `SSLException`s raised mid-transfer (e.g. a
 * connection reset during a TLS record) are transient network errors and stay retryable.
 */
fun isTlsVerificationError(error: Throwable?): Boolean {
    var t = error
    var depth = 0
    while (t != null && depth++ < 10) {
        if (t is SSLHandshakeException || t is SSLPeerUnverifiedException || t is CertificateException) return true
        t = t.cause
    }
    return false
}

/**
 * The platform refused the server's certificate and the user has not accepted it ([CertExceptions]).
 * Carries what accepting it would take: the leaf certificate's SHA-256 [fingerprint] and, when the
 * TLS stack said which server it was talking to, its [host].
 */
class UntrustedCertificateException(
    val host: String?,
    val fingerprint: String,
    cause: CertificateException,
) : CertificateException("Certificate $fingerprint not trusted" + (host?.let { " for $it" } ?: "") + ": ${cause.message}", cause)

/** The [UntrustedCertificateException] in [error]'s cause chain, or null when there is none. */
fun untrustedCertificate(error: Throwable?): UntrustedCertificateException? {
    var t = error
    var depth = 0
    while (t != null && depth++ < 10) {
        if (t is UntrustedCertificateException) return t
        t = t.cause
    }
    return null
}

/**
 * A client's own certificate checks, in place of the shared validating context
 * ([xdm.core.network.http.impl.SharedTls]) and the certificate exceptions. Only the desktop app
 * provides one, when the user turned certificate checks off.
 */
interface CertTrust {
    val socketFactory: SSLSocketFactory
    val trustManager: X509TrustManager
    val hostnameVerifier: HostnameVerifier
}
