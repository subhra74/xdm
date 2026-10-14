package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.app.OS
import xdm.app.utils.AppDirs
import java.io.File

/** The per-OS state folder, and the overrides the recording scripts and XDG rely on. */
class AppDirsTest {

    private val home = File("/home/u").path

    private fun resolve(os: OS, override: String? = null, vararg env: Pair<String, String>) =
        AppDirs.resolve(os, override, home, mapOf(*env)::get)

    @Test
    fun windowsUsesLocalAppData() {
        assertEquals(
            File("""C:\Users\u\AppData\Local""", "xdm-app"),
            resolve(OS.Windows, null, "LOCALAPPDATA" to """C:\Users\u\AppData\Local""", "APPDATA" to "roaming"),
        )
    }

    @Test
    fun windowsFallsBackToAppDataThenHome() {
        assertEquals(File("roaming", "xdm-app"), resolve(OS.Windows, null, "APPDATA" to "roaming"))
        assertEquals(File(home, "xdm-app"), resolve(OS.Windows))
    }

    @Test
    fun macUsesApplicationSupport() {
        assertEquals(File(home, "Library/Application Support/xdm-app"), resolve(OS.MacOS))
    }

    @Test
    fun linuxUsesXdgDataHome() {
        assertEquals(File(home, ".local/share/xdm-app"), resolve(OS.Linux))
        assertEquals(File("/data/u", "xdm-app"), resolve(OS.Linux, null, "XDG_DATA_HOME" to "/data/u"))
        assertEquals(
            File(home, ".local/share/xdm-app"), resolve(OS.Linux, null, "XDG_DATA_HOME" to "rel/dir"),
            "a relative XDG_DATA_HOME is invalid and ignored"
        )
    }

    @Test
    fun propertyOverridesEveryOs() {
        OS.entries.forEach {
            assertEquals(File("/tmp/rec/xdm-app").absoluteFile, resolve(it, "/tmp/rec/xdm-app", "LOCALAPPDATA" to "x"), "$it")
        }
        assertEquals(File(home, ".local/share/xdm-app"), resolve(OS.Linux, " "), "a blank override is ignored")
    }
}
