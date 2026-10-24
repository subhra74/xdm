package xdm.core.network.http

import okhttp3.HttpUrl

/**
 * Which hosts a request's browser-captured credentials may reach, measured from the origin they were
 * captured for. Applied to every hop, redirects included, so a link (or a manifest) cannot hand the
 * user's session to a host of its choosing:
 *
 * - `Cookie` goes only to the same site (registrable domain), as a browser's cookie for
 *   `example.com` reaches `cdn.example.com`; never to another site.
 * - `Authorization` and other credential-looking headers ([isCredentialHeader]) go only to the same
 *   host and port.
 * - Nothing goes over plain http when the origin was https.
 */
object CredentialPolicy {
    private val credentialNameParts = listOf("auth", "token", "api-key", "apikey", "session", "csrf", "xsrf", "secret")

    /**
     * `Authorization`, and custom headers whose name says they carry a secret (`X-Auth-Token`,
     * `X-Api-Key`, `X-CSRF-Token`). `Proxy-*` belongs to the proxy hop and is never captured.
     */
    fun isCredentialHeader(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.startsWith("proxy-")) return false
        return credentialNameParts.any { it in lower }
    }

    fun allowsCookie(origin: HttpUrl, target: HttpUrl): Boolean =
        !isDowngrade(origin, target) && site(origin).equals(site(target), ignoreCase = true)

    fun allowsCredentialHeader(origin: HttpUrl, target: HttpUrl): Boolean =
        !isDowngrade(origin, target) && origin.host.equals(target.host, ignoreCase = true) && origin.port == target.port

    private fun isDowngrade(origin: HttpUrl, target: HttpUrl) = origin.isHttps && !target.isHttps

    /** The registrable domain (`example.co.uk` for `a.b.example.co.uk`); the host itself for an IP. */
    private fun site(url: HttpUrl): String = url.topPrivateDomain() ?: url.host
}
