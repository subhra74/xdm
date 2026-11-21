// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.downloaders.web.streaming.downloader.hls

import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import xdm.core.util.readLongString
import xdm.core.util.writeLongString
import java.io.File
import java.io.IOException

/**
 * Media playlist text of an HLS download as the browser received it, in a `<id>.playlists` file next to
 * `<id>.state`, by playlist URL.
 *
 * Some sites sign a playlist link for one request: the page's player used it, so XDM's own fetch when the
 * download starts gets a 403. The text is written once when the download is added and read when its
 * segment list is set up (the first start, or a start over after the state was lost). It is kept until the
 * download finishes, so Restart of a stopped download has it too, and deleted with the download's metadata.
 */
object HlsPlaylistStore {
    /** Playlists larger than this are not stored (the browser side sends at most 1 MB of text). */
    private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024

    private fun fileName(id: Long) = "$id.playlists"

    fun save(id: Long, configDir: String, playlists: Map<String, ByteArray>): Result<Unit> =
        AtomicIO.writeTransacted(fileName(id), configDir) { w ->
            w.writeInt(playlists.size)
            for ((url, text) in playlists) {
                w.writeLongString(url)
                w.writeInt(text.size)
                w.write(text)
            }
        }

    /** Text by playlist URL; empty if the file is missing or unreadable. */
    fun load(id: Long, configDir: String): Map<String, ByteArray> {
        if (!AtomicIO.exists(fileName(id), configDir)) return emptyMap()
        return AtomicIO.readTransacted(fileName(id), configDir) { r ->
            val playlists = HashMap<String, ByteArray>()
            repeat(r.readInt()) {
                val url = r.readLongString()
                val size = r.readInt()
                if (size < 0 || size > MAX_PLAYLIST_BYTES) throw IOException("Invalid playlist size: $size")
                playlists[url] = ByteArray(size).also { r.readFully(it) }
            }
            playlists as Map<String, ByteArray>
        }.onFailure { Logger.error("XDM", "Unable to load saved HLS playlists for $id", it) }
            .getOrDefault(emptyMap())
    }

    fun delete(id: Long, configDir: String) {
        val name = fileName(id)
        listOf(name, "$name.bak1", "$name.bak2").forEach { File(configDir, it).delete() }
    }
}
