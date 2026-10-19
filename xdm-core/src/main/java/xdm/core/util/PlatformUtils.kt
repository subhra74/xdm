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
     * The folder holding the app's own files. The bundles' launcher passes it as `-Dxdm.app.dir` (their app
     * classes live in the runtime image, so there is no jar to take it from); a plain `java -jar` or an IDE
     * run uses the jar's (or classes directory's) folder from `java.class.path`.
     */
    val baseDirectory: File?
        get() {
            System.getProperty("xdm.app.dir")?.takeIf { it.isNotBlank() }?.let { return File(it).absoluteFile }
            return System.getProperty("java.class.path")
                ?.split(File.pathSeparatorChar)
                ?.firstOrNull { it.isNotBlank() }
                ?.let { File(it).absoluteFile.parentFile }
        }
}
