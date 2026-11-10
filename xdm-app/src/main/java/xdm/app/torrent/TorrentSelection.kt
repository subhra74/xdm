package xdm.app.torrent

import xdm.manager.torrent.FilePriority
import xdm.manager.torrent.TorrentInfo
import java.net.URLDecoder

/**
 * What the add-torrent field holds: a magnet link (a bare info-hash becomes one), an http(s) link to a .torrent
 * file, or a .torrent file path.
 */
sealed class TorrentSource {
    data class Magnet(val uri: String) : TorrentSource()
    data class TorrentFile(val path: String) : TorrentSource()
    /** An http(s) link to a .torrent file, downloaded when chosen. */
    data class Link(val url: String) : TorrentSource()

    companion object {
        private val HEX40 = Regex("^[0-9a-fA-F]{40}$")
        private val BASE32 = Regex("^[A-Za-z2-7]{32}$")

        /** Null when [text] is neither (blank, or a path that is not a file). [isFile] checks a path. */
        fun of(text: String, isFile: (String) -> Boolean): TorrentSource? {
            val s = text.trim()
            if (s.isEmpty()) return null
            if (s.startsWith("magnet:?", ignoreCase = true)) return Magnet(s)
            if (HEX40.matches(s) || BASE32.matches(s)) return Magnet("magnet:?xt=urn:btih:$s")
            if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) return Link(s)
            val path = s.removeSurrounding("\"")
            return if (isFile(path)) TorrentFile(path) else null
        }

        /** The magnet's display name (`dn`), or null. */
        fun displayName(magnet: String): String? =
            magnet.substringAfter('?', "").split('&')
                .firstOrNull { it.startsWith("dn=", ignoreCase = true) }
                ?.substring(3)
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                ?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * The file list of the add-torrent dialog: folders and files as indented rows (folders first, then files,
 * by name), padding files left out, and which files are wanted. Indexes into [TorrentInfo.files] are
 * libtorrent's own file order, which is what [priorities] is in.
 */
class TorrentSelection(val info: TorrentInfo) {
    class Row(
        val name: String,
        val depth: Int,
        val isFolder: Boolean,
        val size: Long,
        /** The files this row stands for: itself, or every file under the folder. */
        val files: IntArray,
    )

    private val wanted = BooleanArray(info.files.size) { !info.files[it].isPad }

    val rows: List<Row> = buildRows()

    /** Files the user can pick (not padding). */
    val fileCount: Int = info.files.count { !it.isPad }

    fun isSelected(row: Row): Boolean = row.files.isNotEmpty() && row.files.all { wanted[it] }

    /** Some but not all files of a folder are wanted. */
    fun isPartial(row: Row): Boolean = row.isFolder && row.files.any { wanted[it] } && !isSelected(row)

    fun set(row: Row, on: Boolean) = row.files.forEach { wanted[it] = on }

    fun setAll(on: Boolean) = info.files.indices.forEach { if (!info.files[it].isPad) wanted[it] = on }

    val selectedCount: Int get() = info.files.indices.count { wanted[it] }

    val selectedSize: Long get() = info.files.indices.filter { wanted[it] }.sumOf { info.files[it].size }

    /** One priority per file (padding files normal), or empty when every file is wanted (the default). */
    fun priorities(): ByteArray {
        if (info.files.indices.all { wanted[it] || info.files[it].isPad }) return ByteArray(0)
        return ByteArray(info.files.size) {
            if (wanted[it] || info.files[it].isPad) FilePriority.NORMAL else FilePriority.SKIP
        }
    }

    private class Node(val name: String) {
        val folders = LinkedHashMap<String, Node>()
        val files = ArrayList<Int>()
    }

    private fun buildRows(): List<Row> {
        val root = Node("")
        info.files.forEachIndexed { i, f ->
            if (f.isPad) return@forEachIndexed
            // A multi-file torrent's paths start with its own folder: the dialog shows that as the name.
            val parts = f.path.split('/').let { if (info.singleFile || it.size < 2) it else it.drop(1) }
            var node = root
            for (dir in parts.dropLast(1)) node = node.folders.getOrPut(dir) { Node(dir) }
            node.files.add(i)
        }
        val out = ArrayList<Row>()
        fun under(node: Node): IntArray =
            (node.files + node.folders.values.flatMap { under(it).toList() }).toIntArray()
        fun walk(node: Node, depth: Int) {
            for (dir in node.folders.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })) {
                val files = under(dir)
                out.add(Row(dir.name, depth, true, files.sumOf { info.files[it].size }, files))
                walk(dir, depth + 1)
            }
            for (i in node.files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { info.files[it].path })) {
                out.add(Row(info.files[i].path.substringAfterLast('/'), depth, false, info.files[i].size, intArrayOf(i)))
            }
        }
        walk(root, 0)
        return out
    }
}
