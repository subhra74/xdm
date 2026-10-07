package xdm.core.network.http

import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

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
