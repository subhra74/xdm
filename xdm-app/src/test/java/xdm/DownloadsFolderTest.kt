package xdm

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.app.OS
import xdm.app.utils.DownloadsFolder
import xdm.app.utils.linux.XdgUserDirs
import xdm.app.utils.win.Win32KnownFolders
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.JAVA_BYTE

/** Where the first-run default download folder comes from on each OS. */
class DownloadsFolderTest {

    private val home = File("/home/a")
    private val noEnv: (String) -> String? = { null }
    private val unused: () -> String? = { error("known folder asked off Windows") }

    @Test
    fun windows_usesKnownFolder() {
        assertEquals(
            File("D:\\Downloads"),
            DownloadsFolder.resolve(OS.Windows, home, noEnv, knownFolder = { "D:\\Downloads" }),
            "a moved Downloads folder is followed",
        )
    }

    @Test
    fun windows_fallsBackWhenUnknown() {
        assertEquals(File(home, "Downloads"), DownloadsFolder.resolve(OS.Windows, home, noEnv, { null }), "call failed")
        assertEquals(File(home, "Downloads"), DownloadsFolder.resolve(OS.Windows, home, noEnv, { " " }), "blank path")
    }

    @Test
    fun linux_usesXdgDownloadDir() {
        val read = { file: File ->
            assertEquals(File(home, ".config/user-dirs.dirs"), file, "default config home")
            "XDG_DESKTOP_DIR=\"\$HOME/Bureau\"\nXDG_DOWNLOAD_DIR=\"\$HOME/Téléchargements\"\n"
        }
        assertEquals(
            File(home, "Téléchargements"),
            DownloadsFolder.resolve(OS.Linux, home, noEnv, unused, read),
            "localized name",
        )
    }

    @Test
    fun linux_readsFromXdgConfigHome() {
        val env = { name: String -> if (name == "XDG_CONFIG_HOME") "/cfg" else null }
        val read = { file: File -> if (file == File("/cfg/user-dirs.dirs")) "XDG_DOWNLOAD_DIR=\"/data/dl\"" else null }
        assertEquals(File("/data/dl"), DownloadsFolder.resolve(OS.Linux, home, env, unused, read), "absolute path")
    }

    @Test
    fun linux_fallsBack() {
        val fallback = File(home, "Downloads")
        assertEquals(fallback, DownloadsFolder.resolve(OS.Linux, home, noEnv, unused) { null }, "no user-dirs.dirs")
        assertEquals(
            fallback,
            DownloadsFolder.resolve(OS.Linux, home, noEnv, unused) { "XDG_MUSIC_DIR=\"\$HOME/Music\"" },
            "no XDG_DOWNLOAD_DIR",
        )
        assertEquals(
            fallback,
            DownloadsFolder.resolve(OS.Linux, home, noEnv, unused) { "XDG_DOWNLOAD_DIR=\"\$HOME\"" },
            "set to home means none",
        )
        assertEquals(
            fallback,
            DownloadsFolder.resolve(OS.Linux, home, noEnv, unused) { "XDG_DOWNLOAD_DIR=\"dl\"" },
            "relative path is not valid",
        )
    }

    @Test
    fun mac_isHomeDownloads() {
        assertEquals(File(home, "Downloads"), DownloadsFolder.resolve(OS.MacOS, home, noEnv, unused) { null }, "fixed")
    }

    @Test
    fun xdgConfigHome_ignoresRelative() {
        assertEquals(File(home, ".config"), XdgUserDirs.configHome(home) { "cfg" }, "relative ignored")
        assertEquals(File(home, ".config"), XdgUserDirs.configHome(home) { "" }, "blank ignored")
        assertEquals(File("/cfg"), XdgUserDirs.configHome(home) { "/cfg" }, "absolute used")
    }

    @Test
    fun xdgUserDirs_lastLineWins() {
        val text = "XDG_DOWNLOAD_DIR=\"\$HOME/Old\"\nXDG_DOWNLOAD_DIR=\"\$HOME/New\""
        assertEquals(mapOf("DOWNLOAD" to File(home, "New")), XdgUserDirs.parse(text, home), "as the shell sources it")
    }

    @Test
    fun guid_struct() {
        val bytes = Arena.ofConfined().use { arena ->
            with(Win32KnownFolders) { arena.guid("374DE290-123F-4565-9164-39C4925E467B") }.toArray(JAVA_BYTE)
        }
        val expected = intArrayOf(
            0x90, 0xE2, 0x4D, 0x37, 0x3F, 0x12, 0x65, 0x45, 0x91, 0x64, 0x39, 0xC4, 0x92, 0x5E, 0x46, 0x7B,
        ).map { it.toByte() }.toByteArray()
        assertArrayEquals(expected, bytes, "FOLDERID_Downloads as Windows lays it out")
    }
}
