package xdm.app.torrent

/**
 * How XDM introduces itself to peers and trackers (Settings → BitTorrent → Client identity): a peer id prefix and
 * a user agent. Only clients built on libtorrent are offered, so the handshake and extensions match what is
 * reported; libtorrent's own identity is the default. The versions are fixed here; update them with releases.
 */
data class TorrentClient(val id: String, val label: String, val peerIdPrefix: String?, val userAgent: String?)

object TorrentClients {
    const val DEFAULT = "libtorrent"

    fun all(xdmVersion: String): List<TorrentClient> = listOf(
        TorrentClient(DEFAULT, "libtorrent", null, null),
        TorrentClient("xdm", "XDM $xdmVersion", prefix("XD", xdmVersion), "XDM/$xdmVersion"),
        TorrentClient("qbittorrent", "qBittorrent 5.1.3", prefix("qB", "5.1.3"), "qBittorrent/5.1.3"),
        TorrentClient("deluge", "Deluge 2.2.0", prefix("DE", "2.2.0"), "Deluge/2.2.0 libtorrent/2.0.11"),
    )

    /** The client with [id]; unknown ids (a removed entry, say) fall back to libtorrent. */
    fun byId(id: String?, xdmVersion: String): TorrentClient = all(xdmVersion).let { list -> list.firstOrNull { it.id == id } ?: list[0] }

    /**
     * libtorrent's peer id prefix: `-`, the two-letter client code, one character per version number (0-9, then
     * A-Z for 10-35) for major, minor and revision, a 0 tag, `-`. "XD", "9.0.9" gives "-XD9090-".
     */
    fun prefix(code: String, version: String): String {
        val parts = version.split('.', '-', '+').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        fun c(i: Int): Char = parts.getOrElse(i) { 0 }.coerceIn(0, 35).let { if (it < 10) '0' + it else 'A' + (it - 10) }
        return "-$code${c(0)}${c(1)}${c(2)}0-"
    }
}
