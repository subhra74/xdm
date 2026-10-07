package xdm.app

import xdm.app.ui.components.MessageBox
import xdm.core.network.http.AuthScope
import xdm.core.network.http.BasicCredentials
import xdm.core.network.http.CredentialPrompt
import xdm.core.util.Logger
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * Process-wide authenticator, installed once from [IAppConfig.applyAuthConfig].
 *
 * HTTP 401 / 407 never reach it: the download clients answer those through [AppContext.httpAuth].
 * A SOCKS proxy cannot be authenticated through OkHttp, though: SOCKS5 authentication happens in the
 * JDK socket layer, which asks the default [Authenticator] instead (`SocksSocketImpl` calls it with
 * protocol `SOCKS5` and, note, requestor type SERVER rather than PROXY). So when the request is for
 * the proxy configured in Settings, answer it from the same [AppContext.httpAuth] entry the HTTP
 * proxy path uses; anything else prompts.
 *
 * The JDK never says whether a SOCKS proxy accepted what it was given, so each new pair is checked
 * first ([SocksProbe]); a rejected one prompts again until the proxy takes one or the user cancels.
 * Cancel holds until the proxy settings change. The config is read on each call, so credentials
 * edited in Settings apply without a restart.
 *
 * The JDK serializes calls on the installed authenticator, so the fields here are only ever touched
 * by one thread at a time.
 */
class DefaultAuthenticator : Authenticator() {
    private var lastConfigured: BasicCredentials? = null
    private val proxyCancelled = AtomicBoolean(false)

    /** The pair the SOCKS proxy accepted (or would not judge); handed out without probing again. */
    private var verified: BasicCredentials? = null

    override fun getPasswordAuthentication(): PasswordAuthentication? {
        val config = runCatching { AppContext.config }.getOrNull()
        if (config != null && config.useProxy && isConfiguredProxy(config)) return proxyAuthentication(config)
        val scope = AuthScope(requestorType == RequestorType.PROXY, requestingHost ?: "", requestingPort, requestingPrompt)
        return SwingCredentialPrompt.ask(scope, rejected = false)?.let { PasswordAuthentication(it.user, it.password.toCharArray()) }
    }

    private fun proxyAuthentication(config: IAppConfig): PasswordAuthentication? {
        val scope = AuthScope(proxy = true, host = config.proxyHost, port = config.proxyPort, realm = null)
        val configured = config.proxyUser.takeIf { it.isNotEmpty() }?.let { BasicCredentials(it, config.proxyPass) }
        if (configured != lastConfigured) {
            lastConfigured = configured
            proxyCancelled.set(false)
            verified = null
        }
        val auth = AppContext.httpAuth
        var next = auth.onChallenge(scope, null, proxyCancelled, configured) ?: return null
        if (config.socksProxy) {
            while (next != verified && SocksProbe.rejects(scope.host, scope.port, next.user, next.password)) {
                Logger.info("XDM", "Proxy ${scope.host}:${scope.port} rejected the credentials for ${next.user}")
                next = auth.onChallenge(scope, next, proxyCancelled, configured) ?: return null
            }
            verified = next
        }
        Logger.info("XDM", "Authenticating to proxy ${scope.host}:${scope.port} as ${next.user}")
        return PasswordAuthentication(next.user, next.password.toCharArray())
    }

    /**
     * True when the challenge comes from the proxy in Settings: either the JVM says so outright, or
     * the host and port match. `requestingHost` is null for an address-only request, so fall back to
     * the resolved address' literal address and host name.
     */
    private fun isConfiguredProxy(config: IAppConfig): Boolean {
        if (requestingPort != config.proxyPort) return false
        if (requestorType == RequestorType.PROXY) return true
        val host = config.proxyHost
        if (host.isEmpty()) return false
        return requestingHost.equals(host, ignoreCase = true) ||
                requestingSite?.hostAddress.equals(host, ignoreCase = true) ||
                requestingSite?.hostName.equals(host, ignoreCase = true)
    }
}

/**
 * Asks for credentials with a modal dialog on the EDT; the caller is a download thread, which waits.
 * What the user types is kept in memory for the run only (see [xdm.core.network.http.HttpAuth]).
 */
object SwingCredentialPrompt : CredentialPrompt {
    override fun ask(scope: AuthScope, rejected: Boolean): BasicCredentials? {
        val message = message(scope, rejected)
        var input: xdm.app.ui.components.AuthInput? = null
        val show = Runnable { input = MessageBox.showAuth("XDM", message) }
        if (SwingUtilities.isEventDispatchThread()) show.run() else SwingUtilities.invokeAndWait(show)
        val answer = input ?: return null
        return BasicCredentials(answer.userName, answer.password)
    }

    private fun message(scope: AuthScope, rejected: Boolean): String {
        val who = if (scope.proxy) "The proxy ${scope.host}:${scope.port}" else
            scope.host + (scope.realm?.takeIf { it.isNotBlank() }?.let { " (\"$it\")" } ?: "")
        return if (rejected) "$who did not accept the user name and password. Enter them again."
        else "$who needs a user name and password."
    }
}

/**
 * Runs the SOCKS5 username/password exchange (RFC 1928 greeting, RFC 1929 sub-negotiation) against
 * the proxy and stops before sending a CONNECT, purely to find out whether the credentials are
 * accepted. Nothing else answers that question: the JDK asks for credentials but never reports back
 * what the proxy made of them.
 */
private object SocksProbe {
    private const val TIMEOUT_MS = 8000
    private const val VERSION = 0x05
    private const val USER_PASS_METHOD = 0x02
    private const val NO_AUTH_METHOD = 0x00

    /**
     * True only when the proxy ran the exchange and rejected the credentials. Anything else —
     * unreachable proxy, a non-SOCKS5 or SOCKS4 endpoint, a proxy that wants no authentication or
     * refuses username/password altogether — returns false, so the credentials are used as before
     * and a real download reports the real failure. Prompting there would only offer the user a
     * password the proxy was never going to accept.
     */
    fun rejects(host: String, port: Int, user: String, password: String): Boolean {
        if (host.isEmpty() || user.length > 255 || password.length > 255) return false
        return runCatching {
            // NO_PROXY: connect to the proxy itself, never through a proxy selector.
            Socket(Proxy.NO_PROXY).use { socket ->
                socket.soTimeout = TIMEOUT_MS
                socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                val out = socket.getOutputStream()
                val input = socket.getInputStream()

                out.write(byteArrayOf(VERSION.toByte(), 1, USER_PASS_METHOD.toByte()))
                out.flush()
                val greeting = input.readExactly(2) ?: return@use false
                if (greeting[0].toInt() and 0xFF != VERSION) return@use false
                when (greeting[1].toInt() and 0xFF) {
                    USER_PASS_METHOD -> {}
                    NO_AUTH_METHOD -> return@use false // credentials are not needed here
                    // A proxy that refuses username/password auth outright, or wants a method we do
                    // not implement, cannot be helped by a different password: say nothing and let
                    // the download report the connection failure.
                    else -> return@use false
                }

                val userBytes = user.toByteArray(Charsets.UTF_8)
                val passBytes = password.toByteArray(Charsets.UTF_8)
                if (userBytes.size > 255 || passBytes.size > 255) return@use false
                val request = ByteArray(3 + userBytes.size + passBytes.size)
                request[0] = 0x01 // sub-negotiation version, not the SOCKS version
                request[1] = userBytes.size.toByte()
                userBytes.copyInto(request, 2)
                request[2 + userBytes.size] = passBytes.size.toByte()
                passBytes.copyInto(request, 3 + userBytes.size)
                out.write(request)
                out.flush()

                val reply = input.readExactly(2) ?: return@use false
                reply[1].toInt() and 0xFF != 0x00
            }
        }.getOrElse {
            Logger.info("XDM", "Could not check proxy credentials against $host:$port: $it")
            false
        }
    }

    /** Reads exactly [count] bytes, or null if the proxy closed or stalled first. */
    private fun java.io.InputStream.readExactly(count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(buffer, read, count - read)
            if (n < 0) return null
            read += n
        }
        return buffer
    }
}
