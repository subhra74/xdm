package xdm.app.utils

import java.io.File
import java.util.Properties
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import javax.accessibility.AccessibilityProvider

/**
 * Keeps a stale assistive-technology entry from stopping the app. When the AWT toolkit starts it loads
 * every name in `javax.accessibility.assistive_technologies`, or, when that property is unset, in
 * `assistive_technologies` of `~/.accessibility.properties` (or `$JAVA_HOME/conf/accessibility.properties`
 * when the user's file is missing or empty), and throws `AWTError` for any name it can't find. The
 * bundled runtime is trimmed, so a name written for a full JDK (the ATK wrapper on Linux, or anything
 * else not linked in) would abort the launch. [sanitize] drops the names no installed
 * [AccessibilityProvider] has (no reflection) and leaves the rest to AWT.
 */
object AssistiveTech {
    const val PROPERTY = "javax.accessibility.assistive_technologies"

    /**
     * Must run before anything starts the AWT toolkit. Sets [PROPERTY] to the names kept when any
     * configured name is dropped, and returns the names dropped (empty when nothing changed).
     */
    fun sanitize(): List<String> {
        val configured = configuredNames(
            System.getProperty(PROPERTY),
            File(System.getProperty("user.home"), ".accessibility.properties"),
            File(System.getProperty("java.home"), "conf${File.separator}accessibility.properties"),
        ) ?: return emptyList()
        val names = parse(configured)
        if (names.isEmpty()) return emptyList()
        val (kept, dropped) = split(names, providerNames())
        if (dropped.isNotEmpty()) System.setProperty(PROPERTY, kept.joinToString(","))
        return dropped
    }

    /**
     * Kept and dropped names: a name is kept only when an installed provider has it. AWT would also
     * try a name as a class (pre-JDK 9 assistive technologies), but in the bundled runtime, which has no
     * classpath or `lib/ext`, such a class could only come from XDM's own jar, so none is real.
     */
    internal fun split(names: List<String>, providers: Set<String>): Pair<List<String>, List<String>> =
        names.partition { it in providers }

    /** The name list AWT would read: the system property first, then the user's file, then the runtime's. */
    internal fun configuredNames(property: String?, userFile: File, runtimeFile: File): String? {
        if (property != null) return property
        val props = load(userFile).takeIf { it.isNotEmpty() } ?: load(runtimeFile)
        return props.getProperty("assistive_technologies")
    }

    /** Comma-separated names as AWT splits them: trimmed, blanks and repeats dropped. */
    internal fun parse(names: String): List<String> =
        names.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun load(file: File): Properties = Properties().apply {
        try {
            if (file.isFile) file.inputStream().use { load(it) }
        } catch (_: Exception) {
            // AWT ignores an unreadable file too
        }
    }

    /** Names of the [AccessibilityProvider]s in this runtime (the Java Access Bridge on Windows). */
    private fun providerNames(): Set<String> {
        val names = HashSet<String>()
        try {
            val it = ServiceLoader.load(AccessibilityProvider::class.java, ClassLoader.getSystemClassLoader()).iterator()
            while (true) {
                try {
                    if (!it.hasNext()) break
                    names += it.next().name
                } catch (_: ServiceConfigurationError) {
                    // a broken provider: AWT can't use it either
                }
            }
        } catch (_: ServiceConfigurationError) {
        }
        return names
    }
}
