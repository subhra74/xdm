package xdm.app.utils.linux

import java.io.File

/**
 * The XDG user folders from `user-dirs.dirs`, as xdg-user-dirs writes them
 * (`XDG_DOWNLOAD_DIR="$HOME/Téléchargements"`): localized names, or folders the user moved.
 * Read by the Downloads folder lookup and the file chooser's places bar.
 */
internal object XdgUserDirs {

    /** `$XDG_CONFIG_HOME`, by default `~/.config`. The XDG spec ignores a relative value. */
    fun configHome(home: File, env: (String) -> String?): File =
        env("XDG_CONFIG_HOME")?.takeIf { File(it).isAbsolute }?.let(::File) ?: File(home, ".config")

    /** `user-dirs.dirs` in [configHome]. */
    fun file(home: File, env: (String) -> String?): File = File(configHome(home, env), "user-dirs.dirs")

    /**
     * Folder per key (`DOWNLOAD`, `MUSIC`, …) from the file's [text]. Only `$HOME/…` and absolute paths are
     * valid there; a folder set to home itself means "none" and is left out. A key set twice takes the
     * last line, as when the shell sources the file.
     */
    fun parse(text: String, home: File): Map<String, File> = text.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("XDG_") && it.contains('=') }
        .associate { line ->
            val key = line.substringBefore('=').removePrefix("XDG_").removeSuffix("_DIR")
            val value = line.substringAfter('=').trim().removeSurrounding("\"").replace("\\\"", "\"")
            key to value
        }
        .mapNotNull { (key, value) ->
            val file = when {
                value.startsWith("\$HOME/") -> File(home, value.removePrefix("\$HOME/"))
                value.startsWith("/") -> File(value)
                else -> return@mapNotNull null // `$HOME` itself, or not a valid form
            }
            if (file.path == home.path) null else key to file
        }
        .toMap()
}
