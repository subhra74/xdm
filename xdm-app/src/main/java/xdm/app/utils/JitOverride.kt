package xdm.app.utils

import xdm.app.OS
import xdm.core.util.Logger
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The "full JIT" setting: a per-user copy of the launcher's `.cfg` without the compiler directive.
 *
 * Packaged builds run C1 for all code and C2 only for the JDK's crypto hot methods, through
 * `-XX:CompilerDirectivesFile` (PACKAGING.md). That keeps TLS on the AES/GHASH/ChaCha20 intrinsics
 * without paying C2's memory for everything else. Full JIT drops the directive so all hot code can
 * reach C2 (faster video muxing, more memory).
 *
 * The installed `app/<launcher>.cfg` is read-only (Program Files, a signed .app, a deb/rpm), but a
 * jpackage launcher looks for its `.cfg` in a per-user folder first and uses the first one it finds
 * (see PACKAGING.md §7). The folder depends on the package name the build writes to `app/.package`:
 *
 *  - Windows: `%LOCALAPPDATA%\<pkg>\` (the launcher also checks `%APPDATA%\<pkg>\` after it)
 *  - macOS:   `~/Library/Application Support/<pkg>/`
 *  - Linux:   `~/.local/<pkg>/` - only honoured when the launcher belongs to an installed deb/rpm, so
 *             the build writes `.package` for those and not for the tar.gz
 *
 * The copy replaces the installed file as a whole (the launcher never merges), so it is always
 * generated from it: the same lines minus the directive and its code-cache/compiler-count limits, plus
 * `-Dxdm.jit=full` so the running app can tell which file the launcher used. [sync] regenerates it
 * on every start, so a copy made by an older version is refreshed one start after an upgrade.
 *
 * A build without the directive (older packaging) always runs full tiered; there is nothing to override.
 */
object JitOverride {

    private const val MODE_PROPERTY = "xdm.jit"
    private const val MODE_FULL = "full"
    private const val JAVA_OPTIONS = "java-options="
    private const val DIRECTIVE_OPTION = "-XX:CompilerDirectivesFile="
    private val DROPPED_OPTIONS = listOf(DIRECTIVE_OPTION, "-XX:CICompilerCount=", "-XX:ReservedCodeCacheSize=")

    private class Layout(val appDir: File, val launcherCfg: File, val userDir: File?)

    private val layout: Layout? by lazy { runCatching { resolveLayout() }.getOrNull() }

    /** True when the launcher that started XDM uses the directive and a per-user override is honoured. */
    val isConfigurable: Boolean
        get() = layout?.let { it.userDir != null && isRestricted(it.launcherCfg) } == true

    /** True for a packaged build whose installed launcher already runs full tiered. */
    val isAlwaysFull: Boolean
        get() = layout?.let { !isRestricted(it.launcherCfg) } == true

    /** True when this JVM was started with full tiered compilation. */
    val isRunningFull: Boolean
        get() = System.getProperty(MODE_PROPERTY) == MODE_FULL || isAlwaysFull

    /** True when the override exists for the launcher that started XDM (applies from the next start). */
    fun isEnabled(): Boolean {
        val l = layout ?: return false
        val userDir = l.userDir ?: return false
        return File(userDir, l.launcherCfg.name).isFile
    }

    /**
     * Writes or removes the override for every launcher in the install. Returns whether the state
     * on disk now matches [enabled].
     */
    fun setEnabled(enabled: Boolean): Boolean {
        val l = layout ?: return !enabled
        val userDir = l.userDir ?: return !enabled
        if (enabled && !isConfigurable) return false
        installedCfgs(l.appDir).forEach { installed ->
            val override = File(userDir, installed.name)
            runCatching {
                if (enabled) write(override, generate(installed)) else Files.deleteIfExists(override.toPath())
            }.onFailure { Logger.error("JIT", "Could not update $override", it) }
        }
        return isEnabled() == enabled
    }

    /**
     * Run once at start: refreshes existing overrides from the installed files, and removes them
     * when the install no longer uses the directive (nothing left to override).
     */
    fun sync() {
        val l = layout ?: return
        val userDir = l.userDir ?: return
        val keep = isRestricted(l.launcherCfg)
        installedCfgs(l.appDir).forEach { installed ->
            val override = File(userDir, installed.name)
            if (!override.isFile) return@forEach
            runCatching {
                if (!keep) {
                    Files.deleteIfExists(override.toPath())
                    Logger.info("JIT", "Removed $override: the installed launcher has no compiler directive")
                } else {
                    val expected = generate(installed)
                    if (override.readText() != expected) {
                        write(override, expected)
                        Logger.info("JIT", "Refreshed $override; used from the next start")
                    }
                }
            }.onFailure { Logger.error("JIT", "Could not refresh $override", it) }
        }
        Logger.info("JIT", if (isRunningFull) "Running full tiered (C1+C2)" else "Running C1, C2 for crypto only")
    }

    private fun resolveLayout(): Layout? {
        val appPath = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() } ?: return null
        val launcher = File(appPath)
        val cfgName = launcher.name.removeSuffix(".exe").removeSuffix(".EXE") + ".cfg"
        val binDir = launcher.parentFile ?: return null
        // Windows: <root>\app, Linux: <root>/lib/app, macOS: Contents/app (launcher in Contents/MacOS).
        val appDir = listOfNotNull(
            File(binDir, "app"),
            binDir.parentFile?.let { File(it, "lib/app") },
            binDir.parentFile?.let { File(it, "app") },
        ).firstOrNull { File(it, cfgName).isFile } ?: return null

        val pkg = File(appDir, ".package").takeIf { it.isFile }
            ?.useLines { it.firstOrNull() }?.trim()?.takeIf { it.isNotEmpty() }
        val home = System.getProperty("user.home")
        val userDir = pkg?.let {
            when (detectOS()) {
                OS.Windows -> (System.getenv("LOCALAPPDATA") ?: System.getenv("APPDATA"))?.let { base -> File(base, pkg) }
                OS.MacOS -> File(home, "Library/Application Support/$pkg")
                OS.Linux -> File(home, ".local/$pkg")
            }
        }
        return Layout(appDir, File(appDir, cfgName), userDir)
    }

    private fun installedCfgs(appDir: File): List<File> =
        appDir.listFiles { f -> f.isFile && f.name.endsWith(".cfg") }?.toList().orEmpty()

    private fun isRestricted(cfg: File): Boolean =
        runCatching { cfg.readLines().any { it.trim().startsWith(JAVA_OPTIONS + DIRECTIVE_OPTION) } }.getOrDefault(false)

    /** The installed file with the directive options dropped and the full-mode marker added. */
    private fun generate(installed: File): String {
        val text = installed.readText()
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val out = mutableListOf("; Written by XDM (full JIT setting). Delete this file to restore the default.")
        for (line in text.lines()) {
            val option = line.trim().takeIf { it.startsWith(JAVA_OPTIONS) }?.removePrefix(JAVA_OPTIONS)
            if (option != null && DROPPED_OPTIONS.any { option.startsWith(it) }) continue
            out += line
            if (line.trim() == "[JavaOptions]") out += "$JAVA_OPTIONS-D$MODE_PROPERTY=$MODE_FULL"
        }
        return out.joinToString(eol)
    }

    /**
     * Temp file and rename, not AtomicIO: the launcher parses this file, so it must hold exactly
     * the generated text (AtomicIO appends a footer and keeps `.bak` copies).
     */
    private fun write(target: File, content: String) {
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
