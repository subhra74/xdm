@file:Suppress("JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE")

package xdm.core

import com.sun.security.ntlm.Server

/**
 * The server side of NTLM, from the JDK (`com.sun.security.ntlm`, opened to the test JVM by the
 * surefire `argLine`), knowing the [accounts] (user name, case-insensitive, to password).
 *
 * Kept top-level and out of the test classes on purpose: Surefire inspects test classes and their
 * nested classes in the Maven JVM, which has no `--add-exports`, and linking a nested subclass of
 * [Server] there fails.
 */
class JdkNtlmServer(domain: String, private val accounts: Map<String, String> = mapOf("alice" to "right")) {
    private val server = object : Server(null, domain) {
        override fun getPassword(domain: String?, user: String): CharArray? =
            accounts.entries.firstOrNull { it.key.equals(user, ignoreCase = true) }?.value?.toCharArray()
    }

    @Synchronized
    fun type2(type1: ByteArray, nonce: ByteArray): ByteArray = server.type2(type1, nonce)

    /** `DOMAIN\user` when [type3] answers [nonce] correctly, else null. */
    @Synchronized
    fun verify(type3: ByteArray, nonce: ByteArray): String? =
        // verify() returns [user, workstation, domain], or throws on a wrong password.
        runCatching { server.verify(type3, nonce) }.getOrNull()?.let { "${it[2]}\\${it[0]}" }
}
