package xdm.app.utils

import xdm.manager.DownloadCategory
import java.nio.file.Path

/**
 * Category folders that sit inside the old default download folder, moved to the same place inside
 * the new one, keyed by category id. Built-in categories start as `<default folder>/<name>`, so without
 * this a new default folder would leave them all pointing at the old one. A folder outside the old
 * default is the user's own choice and is left out, as is any path that does not parse.
 */
fun categoryFolderMoves(categories: List<DownloadCategory>, oldBase: String, newBase: String): Map<String, String> {
    val old = parse(oldBase) ?: return emptyMap()
    val new = parse(newBase) ?: return emptyMap()
    if (old == new) return emptyMap()
    return categories.mapNotNull { category ->
        movedFolder(category.folder, old, new)?.let { category.id to it }
    }.toMap()
}

private fun movedFolder(folder: String, old: Path, new: Path): String? {
    val path = parse(folder) ?: return null
    if (!path.startsWith(old)) return null
    // The new folder is inside the old one and this category already sits inside the new folder:
    // it was set there on purpose, and moving it would nest it a second time.
    if (new.startsWith(old) && path.startsWith(new)) return null
    return new.resolve(old.relativize(path)).toString()
}

/** Absolute and normalized; `Path` compares case-insensitively on Windows. Null for blank or bad input. */
private fun parse(path: String): Path? =
    path.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
