package xdm.core.util

import java.io.File
import java.net.URISyntaxException
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

    val baseDirectory: File?
        get() {
            try {
                val f =
                    File(
                        PlatformUtils::class.java
                            .protectionDomain
                            .codeSource
                            .location
                            .toURI()
                            .path
                    )
                return f.parentFile
            } catch (e: URISyntaxException) {
                // No action
            }
            return null
        }
}
