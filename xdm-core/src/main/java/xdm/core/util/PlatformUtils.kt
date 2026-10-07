package xdm.core.util

import java.io.File
import java.util.*

object PlatformUtils {
    fun getExecutableFromSystemPath(executable: String): String? {
        val path: String? = System.getenv("PATH")
        if (!path.isNullOrEmpty()) {
            for (p in path.split(File.pathSeparator)) {
                val f = File(p, executable)
                if (f.exists()) {
                    return f.absolutePath
                }
            }
        }
        return null
    }

    val isWindows: Boolean
        get() = System.getProperty("os.name").lowercase(Locale.getDefault()).contains("windows")

    /**
     * The jar (or classes directory) the app runs from, taken from `java.class.path` - which the jpackage
     * launcher sets to the installed jar - rather than from a class's CodeSource. For classes loaded from an
     * AppCDS archive the JDK builds that URL from the jar path recorded at dump time: it names the build
     * machine's copy, or is null where that path doesn't exist (APPCDS.md).
     */
    val appClassPathRoot: File?
        get() {
            System.getProperty("java.class.path")
                ?.split(File.pathSeparatorChar)
                ?.firstOrNull { it.isNotBlank() }
                ?.let { return File(it).absoluteFile }
            return runCatching {
                File(PlatformUtils::class.java.protectionDomain.codeSource.location.toURI())
            }.getOrNull()
        }

    val baseDirectory: File?
        get() = appClassPathRoot?.parentFile
}
