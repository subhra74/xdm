package xdm.app.torrent

import java.io.File
import java.net.URI

/**
 * What the operating system hands XDM when the user opens a magnet link or a .torrent file with it: a launch
 * argument (Windows, Linux; also the arguments a second launch passes to the running instance), or an Apple
 * event on macOS. Recognised here, so every route opens the same Add torrent dialog.
 */
object TorrentLaunch {
    /**
     * The first argument that is a magnet link or a .torrent file that exists, as the Add torrent field takes
     * it (a link, or a local path); null when there is none. `file://` URIs (some Linux file managers pass
     * them for `%U`) become paths.
     */
    fun sourceIn(args: Iterable<String>, isFile: (String) -> Boolean = { File(it).isFile }): String? {
        for (raw in args) {
            val a = raw.trim()
            if (a.startsWith("magnet:?", ignoreCase = true)) return a
            val path = if (a.startsWith("file:", ignoreCase = true)) {
                runCatching { File(URI(a)).path }.getOrNull() ?: continue
            } else a
            if (path.endsWith(".torrent", ignoreCase = true) && isFile(path)) return path
        }
        return null
    }

    /** Whether a captured download is a .torrent file: by its name, else by the server's content type. */
    fun isTorrentFile(fileName: String?, contentType: String?): Boolean =
        fileName?.substringBefore('?')?.endsWith(".torrent", ignoreCase = true) == true ||
            contentType?.substringBefore(';')?.trim().equals("application/x-bittorrent", ignoreCase = true)
}

/** What Add torrent offers for a torrent already in the list (one session holds an info-hash once). */
enum class DuplicateAction {
    /** Running, seeding or waiting in the queue: open its progress window. */
    SHOW,
    /** Paused or failed: resume that entry (libtorrent re-checks what is on disk). */
    RESUME,
    /** Finished with its files on disk: seed it. */
    SEED,
    /** Finished but its files are gone: drop the old entry and add this one. */
    DOWNLOAD_AGAIN;

    companion object {
        fun of(status: xdm.manager.RecordStatus, seeding: Boolean, filesPresent: Boolean): DuplicateAction = when {
            seeding -> SHOW
            status == xdm.manager.RecordStatus.FINISHED -> if (filesPresent) SEED else DOWNLOAD_AGAIN
            status == xdm.manager.RecordStatus.PAUSED || status == xdm.manager.RecordStatus.ERROR -> RESUME
            else -> SHOW
        }
    }
}
