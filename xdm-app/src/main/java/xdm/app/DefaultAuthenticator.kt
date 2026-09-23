package xdm.app

import xdm.app.ui.components.MessageBox
import xdm.core.network.http.ProxyAuth
import xdm.core.util.Logger
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.Socket

/**
 * Process-wide authenticator, installed once from [IAppConfig.applyAuthConfig].
 *
 * A SOCKS proxy cannot be authenticated through OkHttp: `proxyAuthenticator` only answers an HTTP
 * proxy's 407, while SOCKS5 authentication happens in the JDK socket layer, which asks the default
 * [Authenticator] instead (`SocksSocketImpl` calls it with protocol `SOCKS5` and, note, requestor
 * type SERVER rather than PROXY). So when the request is for the proxy configured in Settings,
 * answer it from the config; everything else still prompts the user.
 *
 * Stored credentials are checked before they are handed out, so a wrong user name or password
 * prompts instead of failing every download silently. For SOCKS that means probing the proxy
 * ([SocksProbe]); for an HTTP proxy the client in xdm-core has already had them refused with a 407
 * and says so with [ProxyAuth.REJECTED_PROMPT]. What the user types is kept for the rest of the
 * run, and written back to the config if they tick "Remember me". The config is read on each call,
 * so credentials edited in Settings apply without a restart.
 *
 * The JDK serializes calls on the installed authenticator, so the probe, the prompt and [state] are
 * only ever touched by one thread at a time.
 */
class DefaultAuthenticator : Authenticator() {
    /** Identifies one set of proxy credentials; a change to any part starts over. */
    private data class ProxyKey(val host: String, val port: Int, val user: String, val password: String)

    private sealed interface State {
        /** The proxy accepted these credentials, or would not say; hand them out. */
        object Accepted : State

        /** The proxy rejected the configured credentials; [entered] is what the user typed since. */
        data class Rejected(val entered: PasswordAuthentication?) : State
    }

    private var stateKey: ProxyKey? = null
    private var state: State? = null

    override fun getPasswordAuthentication(): PasswordAuthentication? {
        proxyAuthentication()?.let { return it }
        return prompt(requestorType == RequestorType.PROXY)
    }

    /**
     * Credentials for the configured proxy, or null when this request is not that proxy (then the
     * caller prompts as before).
     */
    private fun proxyAuthentication(): PasswordAuthentication? {
        val config = runCatching { AppContext.config }.getOrNull() ?: return null
        if (!config.useProxy || config.proxyUser.isEmpty()) return null
        if (!isConfiguredProxy(config)) return null

        val key = ProxyKey(config.proxyHost, config.proxyPort, config.proxyUser, config.proxyPass)
        // The HTTP client only asks after a 407, so its request is itself proof of a rejection.
        val refused = requestingPrompt == ProxyAuth.REJECTED_PROMPT
        if (key != stateKey) {
            stateKey = key
            state = if (refused || (config.socksProxy && SocksProbe.rejects(key.host, key.port, key.user, key.password))) {
                Logger.info("XDM", "Proxy ${key.host}:${key.port} rejected the saved credentials")
                State.Rejected(null)
            } else {
                State.Accepted
            }
        } else if (refused && state is State.Accepted) {
            Logger.info("XDM", "Proxy ${key.host}:${key.port} rejected the saved credentials")
            state = State.Rejected(null)
        }

        when (val current = state) {
            is State.Accepted -> {
                Logger.info("XDM", "Authenticating to proxy ${key.host}:${key.port} as ${key.user}")
                return PasswordAuthentication(key.user, key.password.toCharArray())
            }

            is State.Rejected -> {
                // Ask once, then reuse the answer: the JDK asks again for every new socket, and a
                // dialog per download chunk would be unusable.
                current.entered?.let { return it }
                val entered = prompt(isProxy = true, message = rejectedMessage(key)) ?: return null
                state = State.Rejected(entered)
                return entered
            }

            null -> return null
        }
    }

    private fun rejectedMessage(key: ProxyKey) =
        "The proxy ${key.host}:${key.port} did not accept the credentials saved in Settings."

    private fun prompt(isProxy: Boolean, message: String = "$requestingSite  $requestingPrompt"): PasswordAuthentication? {
        val auth = MessageBox.showAuth("XDM", message, isProxy) ?: return null
        if (isProxy && auth.remember) rememberProxyCredentials(auth.userName, auth.password)
        return PasswordAuthentication(auth.userName, auth.password.toCharArray())
    }

    /** Writes credentials the user asked to keep back to the config, so the next run uses them. */
    private fun rememberProxyCredentials(user: String, password: String) {
        val config = runCatching { AppContext.config }.getOrNull() ?: return
        if (!config.useProxy) return
        runCatching {
            config.proxyUser = user
            config.proxyPass = password
            config.save()
            // Clients built before the edit still send the old pair and will be refused again, so
            // keep handing the new one out under the new key instead of prompting a second time.
            stateKey = ProxyKey(config.proxyHost, config.proxyPort, user, password)
            state = State.Rejected(PasswordAuthentication(user, password.toCharArray()))
            Logger.info("XDM", "Saved proxy credentials for ${config.proxyHost}:${config.proxyPort}")
        }.onFailure { Logger.error("XDM", "Could not save proxy credentials", it) }
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
