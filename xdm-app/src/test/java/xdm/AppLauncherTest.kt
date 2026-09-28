package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.utils.AppLauncher
import java.io.File
import java.nio.file.Files

/**
 * The launch command that goes into the login entry and the `xdm-app://` handler. Quoting is the
 * part that has to be right: these strings are handed to Explorer and to the desktop's exec
 * parser, and a Windows install path has spaces in it far more often than not.
 */
class AppLauncherTest {

    @Test
    fun tokensWithSpacesAreQuoted() {
        assertEquals(
            """"C:\Program Files\XDM\xdm-app.exe" --minimized""",
            AppLauncher.commandLine(listOf("""C:\Program Files\XDM\xdm-app.exe""", "--minimized"))
        )
    }

    @Test
    fun tokensWithoutSpacesAreLeftAlone() {
        assertEquals(
            "/opt/xdm-app/bin/xdm-app --minimized",
            AppLauncher.commandLine(listOf("/opt/xdm-app/bin/xdm-app", "--minimized"))
        )
    }

    /**
     * A packaged run starts from whichever launcher the user clicked - usually
     * "Xtreme Download Manager.exe" - but what gets written into the registry / login entry has to
     * be the stable `xdm-app` name beside it.
     */
    @Test
    fun theStableLauncherIsPreferredOverTheOneThatStartedUs() {
        withAppPath("Xtreme Download Manager.exe", siblings = listOf("xdm-app.exe")) { dir ->
            assertEquals(File(dir, "xdm-app.exe").absolutePath, AppLauncher.command()?.single())
        }
        withAppPath("Xtreme Download Manager", siblings = listOf("xdm-app")) { dir ->
            assertEquals(File(dir, "xdm-app").absolutePath, AppLauncher.command()?.single())
        }
    }

    /** An older install, or an image built before the extra launcher existed. */
    @Test
    fun theStartingLauncherIsUsedWhenThereIsNoSibling() {
        withAppPath("Xtreme Download Manager.exe", siblings = emptyList()) { dir ->
            assertEquals(
                File(dir, "Xtreme Download Manager.exe").absolutePath,
                AppLauncher.command()?.single()
            )
        }
    }

    @Test
    fun startingFromTheStableLauncherKeepsIt() {
        withAppPath("xdm-app.exe", siblings = listOf("Xtreme Download Manager.exe")) { dir ->
            assertEquals(File(dir, "xdm-app.exe").absolutePath, AppLauncher.command()?.single())
        }
    }

    private fun withAppPath(started: String, siblings: List<String>, check: (File) -> Unit) {
        val dir = Files.createTempDirectory("xdm-launcher-test").toFile()
        val previous = System.getProperty(APP_PATH)
        try {
            (siblings + started).forEach { File(dir, it).writeText("") }
            System.setProperty(APP_PATH, File(dir, started).absolutePath)
            check(dir)
        } finally {
            if (previous == null) System.clearProperty(APP_PATH) else System.setProperty(APP_PATH, previous)
            dir.deleteRecursively()
        }
    }

    /**
     * There is no launcher to point at when the classes come from a directory (Surefire, an IDE),
     * so resolution returns null rather than inventing something - callers skip registration and
     * log. What must never happen is a throw on the startup path.
     */
    @Test
    fun resolutionNeverThrowsAndPointsAtSomethingRealWhenItSucceeds() {
        val command = AppLauncher.command()
        if (command != null) {
            assertTrue(command.isNotEmpty(), "resolved an empty command")
            assertTrue(File(command[0]).exists(), "resolved a launcher that does not exist: ${command[0]}")
        }
    }

    private companion object {
        const val APP_PATH = "jpackage.app-path"
    }
}
