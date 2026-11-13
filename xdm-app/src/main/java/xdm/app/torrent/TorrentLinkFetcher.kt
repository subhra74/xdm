package xdm.app.torrent

import xdm.app.AppContext
import xdm.app.InsecureTrust
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import javax.swing.SwingUtilities

/**
 * A .torrent link the browser handed over: XDM fetches the small file itself (with the browser's cookie and
 * headers, so links behind a sign-in work) and opens Add torrent with it instead of the new-download dialog.
 */
object TorrentLinkFetcher {
    /** libtorrent's own limit for a .torrent file it loads. */
    private const val MAX_BYTES = 10 * 1024 * 1024

    /** Whether [info] is a .torrent link that should open Add torrent (torrents available). */
    fun wants(info: HttpDownloadTaskInfo): Boolean =
        AppContext.downloader.torrents.available &&
            (TorrentLaunch.isTorrentFile(info.fileName, null) ||
                TorrentLaunch.isTorrentFile(runCatching { java.net.URI(info.url).path }.getOrNull(), null))

    /**
     * Fetches [info] off the EDT into the app temp folder; [done] gets the saved file, or null when it could not
     * be fetched or is not a torrent, on the EDT.
     */
    fun fetch(info: HttpDownloadTaskInfo, done: (File?) -> Unit) {
        Thread({
            val file = runCatching { download(info) }
                .onFailure { Logger.error("Torrent", "Fetching the torrent link ${info.url} failed", it) }
                .getOrNull()
            SwingUtilities.invokeLater { done(file) }
        }, "torrent-link").apply { isDaemon = true }.start()
    }

    private fun download(info: HttpDownloadTaskInfo): File? {
        val bytes = fetchTorrent(info.url, info.headers, info.cookie) ?: return null
        val name = info.fileName.ifBlank { "link" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .let { if (it.endsWith(".torrent", ignoreCase = true)) it else "$it.torrent" }
        val file = File(AppContext.tempDir, "link-${info.id}-$name")
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }

    /**
     * Downloads [url] and returns it when it is a torrent (parses as one, at most 10 MB); null on any HTTP
     * error or when it is something else (an HTML sign-in page, say). Blocking: call it off the EDT.
     */
    fun fetchTorrent(url: String, headers: xdm.core.network.http.HeaderMap? = null, cookie: String? = null): ByteArray? {
        val c = AppContext.config
        val client = HttpClientImpl(
            1, c.toProxy(), InsecureTrust.ifEnabled(c.ignoreCertErrors), c.readTimeoutSeconds, c.proxyUser, c.proxyPass,
            auth = AppContext.httpAuth, serverAuth = true,
        )
        try {
            val response = client.getResponse(url, headers, cookie, null).getOrThrow()
            val bytes = response.use {
                if (it.statusCode !in 200..299) {
                    Logger.info("Torrent", "Torrent link got HTTP ${it.statusCode}: $url")
                    return null
                }
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                it.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BYTES) return null
                    }
                }
                out.toByteArray()
            }
            return bytes.takeIf { AppContext.downloader.torrents.parse(it) != null }
        } finally {
            client.close()
        }
    }
}
