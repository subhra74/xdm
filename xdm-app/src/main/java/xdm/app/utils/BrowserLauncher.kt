package xdm.app.utils

import xdm.app.OS
import xdm.app.utils.win.Win32Shell
import xdm.core.util.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/** Browsers XDM ships an extension for, with where each one lives on every platform. */
enum class Browser(
    val displayName: String,
    /** macOS bundle identifiers, stable channel first. */
    val macBundleIds: List<String>,
    /** Executable name Windows registers under `App Paths`. */
    val windowsExe: String,
    /** Install folders relative to `Program Files` / `%LOCALAPPDATA%`, for when `App Paths` is missing. */
    val windowsDir: String,
    /** Linux command names, looked up on `PATH` and the usual bin folders. */
    val linuxCommands: List<String>,
    /** Absolute Linux paths used by vendor packages that do not link into `/usr/bin`. */
    val linuxPaths: List<String>,
    val flatpakId: String,
) {
    CHROME(
        "Chrome",
        listOf("com.google.Chrome", "com.google.Chrome.beta", "com.google.Chrome.dev", "com.google.Chrome.canary"),
        "chrome.exe", """Google\Chrome\Application""",
        listOf("google-chrome-stable", "google-chrome", "google-chrome-beta", "google-chrome-unstable"),
        listOf("/opt/google/chrome/google-chrome"),
        "com.google.Chrome",
    ),
    FIREFOX(
        "Firefox",
        listOf("org.mozilla.firefox", "org.mozilla.firefoxdeveloperedition", "org.mozilla.nightly"),
        "firefox.exe", """Mozilla Firefox""",
        listOf("firefox", "firefox-esr", "firefox-developer-edition", "firefox-nightly"),
        listOf("/opt/firefox/firefox"),
        "org.mozilla.firefox",
    ),
    EDGE(
        "Edge",
        listOf("com.microsoft.edgemac", "com.microsoft.edgemac.Beta", "com.microsoft.edgemac.Dev"),
        "msedge.exe", """Microsoft\Edge\Application""",
        listOf("microsoft-edge-stable", "microsoft-edge", "microsoft-edge-beta", "microsoft-edge-dev"),
        listOf("/opt/microsoft/msedge/microsoft-edge"),
        "com.microsoft.Edge",
    ),
    BRAVE(
        "Brave",
        listOf("com.brave.Browser", "com.brave.Browser.beta", "com.brave.Browser.nightly"),
        "brave.exe", """BraveSoftware\Brave-Browser\Application""",
        listOf("brave-browser-stable", "brave-browser", "brave"),
        listOf("/opt/brave.com/brave/brave-browser", "/opt/brave-bin/brave"),
        "com.brave.Browser",
    ),
}

/**
 * Opens a URL in one specific browser (not the default one), so each browser tile lands on its own
 * extension store. Every platform asks the OS where the browser is first and only then guesses paths:
 * - macOS: LaunchServices by bundle id (`open -b`), which finds the app wherever it is installed.
 * - Windows: `ShellExecuteExW` on the bare exe name, resolved through `App Paths`.
 * - Linux: `PATH` and the usual bin folders, vendor `/opt` installs, then Flatpak exports.
 *
 * Blocking (it may wait on `open`), so call it off the EDT.
 */
object BrowserLauncher {

    /** Returns false when [browser] does not seem to be installed or could not be started. */
    fun launch(browser: Browser, url: String): Boolean = when (detectOS()) {
        OS.MacOS -> launchMac(browser, url)
        OS.Windows -> launchWindows(browser, url)
        else -> launchLinux(browser, url)
    }

    private fun launchMac(browser: Browser, url: String): Boolean =
        // `open -b` exits non-zero when no app has that bundle id, without opening anything.
        browser.macBundleIds.any { id -> runAndWait(listOf("/usr/bin/open", "-b", id, url)) }

    private fun launchWindows(browser: Browser, url: String): Boolean {
        val arg = "\"$url\""
        if (Win32Shell.execute(browser.windowsExe, arg)) return true

        val roots = listOf("ProgramW6432", "ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")
            .mapNotNull { System.getenv(it) }
            .distinct()
        roots.map { File(File(it, browser.windowsDir), browser.windowsExe) }
            .firstOrNull { it.isFile }
            ?.let { if (start(listOf(it.path, url))) return true }

        // Edge ships with Windows 10/11 and always owns this scheme, even without App Paths.
        return browser == Browser.EDGE && Win32Shell.execute("microsoft-edge:$url")
    }

    private fun launchLinux(browser: Browser, url: String): Boolean {
        val home = System.getProperty("user.home")
        // A desktop-launched JVM often has a minimal PATH, so the usual folders are added explicitly.
        val binDirs = (System.getenv("PATH")?.split(File.pathSeparator).orEmpty() +
                listOf("/usr/bin", "/usr/local/bin", "/snap/bin", "$home/.local/bin"))
            .filter { it.isNotEmpty() }
            .distinct()

        val candidates = sequence {
            for (cmd in browser.linuxCommands) for (dir in binDirs) yield(File(dir, cmd))
            browser.linuxPaths.forEach { yield(File(it)) }
            yield(File("/var/lib/flatpak/exports/bin", browser.flatpakId))
            yield(File("$home/.local/share/flatpak/exports/bin", browser.flatpakId))
        }
        val exe = candidates.firstOrNull { it.isFile && it.canExecute() } ?: return false
        return start(listOf(exe.path, url))
    }

    /** Starts a browser and leaves it running; its output is discarded. */
    private fun start(command: List<String>): Boolean = runCatching {
        Logger.info("Launching browser: ${command.first()}")
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        true
    }.getOrElse {
        Logger.error("Failed to launch ${command.first()}", it)
        false
    }

    /** Runs a short-lived helper (`open`) and reports whether it exited cleanly. */
    private fun runAndWait(command: List<String>): Boolean = runCatching {
        val p = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroy()
            return false
        }
        p.exitValue() == 0
    }.getOrElse {
        Logger.error("Failed to run ${command.joinToString(" ")}", it)
        false
    }
}
