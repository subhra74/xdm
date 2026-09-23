package xdm.core.network.http

/**
 * The contract between the HTTP client and whatever installed the JVM's default
 * [java.net.Authenticator].
 *
 * When an HTTP proxy rejects the credentials from the config, the client has no way to tell the
 * user: it is in xdm-core and knows nothing about the UI. So it asks the default authenticator for
 * a fresh pair, passing [REJECTED_PROMPT] as the prompt. The app's authenticator recognises that
 * marker as "the saved credentials were refused, ask the user" rather than "supply the saved
 * credentials"; anything without a default authenticator installed simply gets null and fails as
 * before.
 */
object ProxyAuth {
    /** Prompt passed to [java.net.Authenticator] when a proxy has refused the stored credentials. */
    const val REJECTED_PROMPT = "xdm:proxy-credentials-rejected"
}
