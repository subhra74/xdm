package xdm.app.utils

import xdm.core.util.Logger
import xdm.manager.torrent.NativeTorrent
import java.io.File

/**
 * Finds and loads the native torrent library (`xdmtorrent`). Looked for in order:
 * 1. `-Dxdm.torrent.library=<file>`: the packaged app sets it to the copy next to the launcher;
 * 2. a developer build: `~/.cache/xdm-torrent/out/<platform>/bin/`, then fetched binaries in
 *    `~/.cache/xdm-torrent/bin/<platform>/` (build-unix.sh / build-windows.ps1 of the separate libtorrent-builds repo, or packaging/torrent/fetch-binaries).
 * Missing, unloadable or of another C API version: torrents are not offered (logged, never fatal).
 */
object TorrentLibrary {
    const val PROPERTY = "xdm.torrent.library"

    /** `windows-x64`, `macos-arm64`, `linux-x64`, ... as the build scripts name their output folders. */
    val platform: String by lazy {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val o = when {
            os.startsWith("windows") -> "windows"
            os.contains("mac") || os.contains("darwin") -> "macos"
            else -> "linux"
        }
        val a = if (arch == "aarch64" || arch == "arm64") "arm64" else "x64"
        "$o-$a"
    }

    val fileName: String
        get() = when {
            platform.startsWith("windows") -> "xdmtorrent.dll"
            platform.startsWith("macos") -> "libxdmtorrent.dylib"
            else -> "libxdmtorrent.so"
        }

    fun candidates(): List<File> {
        val home = System.getProperty("user.home")
        return listOfNotNull(
            System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File),
            File(home, ".cache/xdm-torrent/out/$platform/bin/$fileName"),
            File(home, ".cache/xdm-torrent/bin/$platform/$fileName"),
        )
    }

    /** Loads the first library found; false when there is none or it does not fit this build. */
    fun load(): Boolean {
        val file = candidates().firstOrNull { it.isFile } ?: run {
            Logger.info("Torrent", "No torrent library for $platform; torrents are not offered")
            return false
        }
        try {
            System.load(file.absolutePath)
        } catch (e: Throwable) {
            Logger.error("Torrent", "Unable to load ${file.absolutePath}", e)
            return false
        }
        if (!NativeTorrent.abiMatches()) {
            val found = runCatching { NativeTorrent.version() }.getOrElse { "unknown" }
            Logger.error("Torrent", "${file.absolutePath} is $found, expected C API ${NativeTorrent.ABI}; torrents disabled")
            return false
        }
        Logger.info("Torrent", "Loaded ${file.absolutePath}: ${NativeTorrent.version()}")
        return true
    }
}
