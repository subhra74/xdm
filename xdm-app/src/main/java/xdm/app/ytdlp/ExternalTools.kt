package xdm.app.ytdlp

import xdm.app.AppContext
import xdm.app.OS
import xdm.app.utils.AppDirs
import xdm.app.utils.detectOS
import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import xdm.core.util.TaggedFile
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The programs Video download runs. Merging needs none: the built-in muxer does it, or the ffmpeg set
 * in Settings ([xdm.app.media.FFmpegMuxer]). None is bundled: XDM downloads them on request, or the user points to their own. */
enum class ExternalTool(val id: String, val versionArg: String) {
    YT_DLP("yt-dlp", "--version"),

    /** The JavaScript runtime yt-dlp uses for some sites. */
    DENO("deno", "--version");

    fun exeName(os: OS): String = if (os == OS.Windows) "$id.exe" else id
}

/** Where a tool was found. Only [MANAGED] copies are updated by XDM. */
enum class ToolOrigin {
    /** Picked with "Use my own…". */
    CUSTOM,

    /** In XDM's `tools` folder, put there by [ToolInstaller]. */
    MANAGED,

    /** On PATH or in a usual install folder. */
    SYSTEM,
}

data class FoundTool(val tool: ExternalTool, val path: String, val origin: ToolOrigin)

/**
 * Finds yt-dlp and deno: the custom path from the Video download dialog, then XDM's `tools`
 * folder (`<data dir>/tools`), then the system. The install folder is never used: it is often not
 * writable (Program Files).
 */
object ExternalTools {
    val toolsDir: File get() = File(AppDirs.root, "tools")

    fun customPath(tool: ExternalTool): String = when (tool) {
        ExternalTool.YT_DLP -> AppContext.config.ytDlpPath
        ExternalTool.DENO -> AppContext.config.denoPath
    }

    fun setCustomPath(tool: ExternalTool, path: String) {
        val config = AppContext.config
        when (tool) {
            ExternalTool.YT_DLP -> config.ytDlpPath = path
            ExternalTool.DENO -> config.denoPath = path
        }
        config.save()
    }

    fun find(tool: ExternalTool): FoundTool? = lookup(
        tool, customPath(tool), toolsDir, detectOS(), System.getenv("PATH"),
        System.getProperty("user.home") ?: "", System::getenv,
    ) { it.isFile && it.canExecute() }

    fun managedFile(tool: ExternalTool, os: OS = detectOS()): File = File(toolsDir, tool.exeName(os))

    /** The lookup itself, with the environment passed in so tests can fake it. */
    internal fun lookup(
        tool: ExternalTool, custom: String, toolsDir: File, os: OS, path: String?, home: String,
        env: (String) -> String?, usable: (File) -> Boolean,
    ): FoundTool? {
        custom.trim().takeIf { it.isNotEmpty() }?.let {
            // A custom path that no longer works is reported as missing, not silently replaced.
            return if (usable(File(it))) FoundTool(tool, File(it).absolutePath, ToolOrigin.CUSTOM) else null
        }
        val exe = tool.exeName(os)
        File(toolsDir, exe).takeIf(usable)?.let { return FoundTool(tool, it.absolutePath, ToolOrigin.MANAGED) }
        val dirs = ArrayList<File>()
        path?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }?.forEach { dirs.add(File(it)) }
        when (os) {
            OS.MacOS -> {
                dirs.add(File("/opt/homebrew/bin")); dirs.add(File("/usr/local/bin"))
            }

            OS.Linux -> {
                dirs.add(File(home, ".local/bin")); dirs.add(File("/usr/local/bin")); dirs.add(File("/usr/bin"))
            }

            OS.Windows -> env("LOCALAPPDATA")?.let { dirs.add(File(it, "Microsoft\\WinGet\\Links")) }
        }
        if (tool == ExternalTool.DENO) dirs.add(File(home, ".deno/bin"))
        for (dir in dirs) {
            val f = File(dir, exe)
            if (usable(f)) return FoundTool(tool, f.absolutePath, ToolOrigin.SYSTEM)
        }
        return null
    }

    /** What the last full lookup found, kept in `tools/known`, so later lookups do not start the tools again. */
    private data class Known(
        val tool: ExternalTool, val custom: String, val path: String, val origin: ToolOrigin,
        val size: Long, val modified: Long, val version: String,
    )

    private const val KNOWN_FILE = "known"
    private const val F_KNOWN = 1
    private const val K_ID = 1
    private const val K_CUSTOM = 2
    private const val K_PATH = 3
    private const val K_ORIGIN = 4
    private const val K_SIZE = 5
    private const val K_MODIFIED = 6
    private const val K_VERSION = 7
    private val knownLock = Any()

    /**
     * The tool and its version. The path and version recorded by an earlier lookup are used while the
     * custom path is the same and the file is still there, the same size and time; otherwise the tool
     * is looked up and started once for its version, and the result recorded. yt-dlp's macOS build
     * unpacks itself on every start and can take over ten seconds to answer `--version`.
     */
    fun check(tool: ExternalTool): Pair<FoundTool?, String?> {
        val custom = customPath(tool).trim()
        val known = synchronized(knownLock) { readKnown() }[tool]
        if (known != null && known.custom == custom) {
            val file = File(known.path)
            if (file.isFile && file.canExecute() && file.length() == known.size && file.lastModified() == known.modified) {
                return FoundTool(tool, known.path, known.origin) to known.version
            }
        }
        val found = find(tool) ?: return null to null
        val version = version(found) ?: return found to null
        val file = File(found.path)
        synchronized(knownLock) {
            writeKnown(readKnown() + (tool to Known(tool, custom, found.path, found.origin, file.length(), file.lastModified(), version)))
        }
        return found to version
    }

    /** Forgets what was recorded for [tool], so the next [check] looks it up again (after an install). */
    fun forget(tool: ExternalTool) = synchronized(knownLock) {
        val known = readKnown()
        if (tool in known) writeKnown(known - tool)
    }

    private fun readKnown(): Map<ExternalTool, Known> {
        if (!AtomicIO.exists(KNOWN_FILE, toolsDir.path)) return emptyMap()
        return AtomicIO.readTransacted(KNOWN_FILE, toolsDir.path) { input ->
            val r = TaggedFile.read(input)
            val result = LinkedHashMap<ExternalTool, Known>()
            while (true) {
                when (r.next()) {
                    -1 -> break
                    F_KNOWN -> {
                        val t = r.message()
                        var id = ""; var custom = ""; var path = ""; var origin = ""; var size = -1L; var modified = -1L; var version = ""
                        while (true) {
                            when (t.next()) {
                                -1 -> break
                                K_ID -> id = t.string()
                                K_CUSTOM -> custom = t.string()
                                K_PATH -> path = t.string()
                                K_ORIGIN -> origin = t.string()
                                K_SIZE -> size = t.long()
                                K_MODIFIED -> modified = t.long()
                                K_VERSION -> version = t.string()
                                else -> t.skip()
                            }
                        }
                        val tool = ExternalTool.entries.firstOrNull { it.id == id }
                        val o = ToolOrigin.entries.firstOrNull { it.name == origin }
                        if (tool != null && o != null && path.isNotEmpty()) result[tool] = Known(tool, custom, path, o, size, modified, version)
                    }

                    else -> r.skip()
                }
            }
            result as Map<ExternalTool, Known>
        }.onFailure { Logger.error("ytdlp", "Could not read $KNOWN_FILE", it) }.getOrDefault(emptyMap())
    }

    private fun writeKnown(known: Map<ExternalTool, Known>) {
        toolsDir.mkdirs()
        AtomicIO.writeTransacted(KNOWN_FILE, toolsDir.path) { out ->
            TaggedFile.write(out) {
                known.values.forEach { k ->
                    message(F_KNOWN) {
                        string(K_ID, k.tool.id)
                        string(K_CUSTOM, k.custom)
                        string(K_PATH, k.path)
                        string(K_ORIGIN, k.origin.name)
                        long(K_SIZE, k.size)
                        long(K_MODIFIED, k.modified)
                        string(K_VERSION, k.version)
                    }
                }
            }
        }.onFailure { Logger.error("ytdlp", "Could not write $KNOWN_FILE", it) }
    }

    /** The tool's version as it reports it, or null when it does not start. Blocking, up to 20 s. */
    fun version(found: FoundTool): String? = runCatching {
        val process = ProcessBuilder(found.path, found.tool.versionArg)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { r -> r.readText() }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        parseVersion(found.tool, output)
    }.onFailure { Logger.info("ytdlp", "${found.path} did not start: $it") }.getOrNull()

    /** yt-dlp: `2026.10.26`; deno: `deno 2.9.8 (stable…)`. */
    internal fun parseVersion(tool: ExternalTool, output: String): String? {
        val first = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val words = first.split(Regex("\\s+"))
        return when (tool) {
            ExternalTool.YT_DLP -> words[0]
            ExternalTool.DENO -> words.getOrNull(1)
        }
    }
}
