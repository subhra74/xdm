package xdm.core.network.http

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** A user name and password for HTTP Basic authentication, to a server or to the proxy. */
data class BasicCredentials(val user: String, val password: String)

/** Who is asking: the proxy, or one protection space (host, port, realm) of a server. */
data class AuthScope(val proxy: Boolean, val host: String, val port: Int, val realm: String?)

/** Asks the user for credentials; implemented by the app. Null means the user cancelled. */
fun interface CredentialPrompt {
    /** [rejected] is true when the last credentials sent to [scope] were refused. */
    fun ask(scope: AuthScope, rejected: Boolean): BasicCredentials?
}

/**
 * Answers 401 / 407 challenges for every download client, so the user is asked once per scope, not
 * once per segment: every connection of a download hits the same challenge at about the same time.
 *
 * Calls for one scope are serialized. A caller whose request went out with older credentials than
 * the ones now held gets those without a prompt; only a refusal of the current credentials asks the
 * user again, as often as the server keeps answering 401 / 407 and the user keeps entering a pair.
 * Cancel ends it for the download whose [cancelled] flag it sets. Credentials live in memory only.
 */
class HttpAuth(private val prompt: CredentialPrompt) {
    private class Entry {
        var configured: BasicCredentials? = null
        var current: BasicCredentials? = null
    }

    private val entries = ConcurrentHashMap<AuthScope, Entry>()

    /**
     * Credentials to retry [scope] with after it refused [sent] (null when the request carried
     * none), or null to give up. [configured] is the pair from Settings, for the proxy: a change to
     * it replaces whatever the user typed before.
     */
    fun onChallenge(
        scope: AuthScope,
        sent: BasicCredentials?,
        cancelled: AtomicBoolean,
        configured: BasicCredentials? = null,
    ): BasicCredentials? {
        val entry = entries.computeIfAbsent(scope) { Entry() }
        synchronized(entry) {
            if (configured != entry.configured) {
                entry.configured = configured
                entry.current = configured
            }
            entry.current?.let { if (it != sent) return it }
            if (cancelled.get()) return null
            val answer = prompt.ask(scope, rejected = sent != null)
            if (answer == null) {
                cancelled.set(true)
                return null
            }
            entry.current = answer
            return answer
        }
    }
}
