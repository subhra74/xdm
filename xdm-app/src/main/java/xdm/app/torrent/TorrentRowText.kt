package xdm.app.torrent

import xdm.app.I8N
import xdm.core.downloaders.DownloadStatusInfo.TorrentProgress
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.TorrentPhase
import xdm.core.util.FormatHelper.formatDateShort
import xdm.core.util.FormatHelper.formatSize
import xdm.core.util.FormatHelper.toLongEta
import xdm.manager.DbRecord
import xdm.manager.RecordStatus
import java.util.Locale

/**
 * The download row's texts for a torrent that is running or seeding; null where the row's usual text applies
 * (paused, failed, finished and not seeding, or no torrent figures yet).
 *
 * ```
 * 1.2 GB / 4.0 GB   ↓3.1 MB/s ↑210 KB/s   12 peers   5m left        Downloading 30%
 * 9 Nov   ·   4.0 GB   ·   ↑200 KB/s   ·   ratio 0.40               Seeding
 * ```
 * While it runs the date is left out; it comes back once the torrent is finished.
 */
object TorrentRowText {
    private const val GAP = "   "
    private const val DOT = "   ·   "

    /** Whether the row is a finished torrent seeding right now. */
    fun isSeeding(ent: DbRecord) =
        ent.downloadType == DownloadType.Torrent && ent.status == RecordStatus.FINISHED && ent.seeding

    private fun running(ent: DbRecord): TorrentProgress? =
        ent.torrent?.takeIf { ent.downloadType == DownloadType.Torrent && ent.status == RecordStatus.DOWNLOADING }

    fun infoLine(ent: DbRecord, text: (String) -> String = I8N::text): String? {
        if (isSeeding(ent)) {
            val t = ent.torrent
            val parts = mutableListOf(formatDateShort(ent.date), formatSize(ent.size.toDouble()))
            if (t != null) {
                parts += "↑" + speed(t.uploadSpeed)
                parts += String.format(Locale.ROOT, text("TORRENT_RATIO"), t.ratio)
            }
            return parts.joinToString(DOT)
        }
        val t = running(ent) ?: return null
        val parts = mutableListOf<String>()
        val total = if (t.wanted > 0) t.wanted else ent.size
        if (t.phase != TorrentPhase.Metadata) {
            parts += if (total > 0) "${formatSize(ent.downloaded.toDouble())} / ${formatSize(total.toDouble())}"
            else formatSize(ent.downloaded.toDouble())
        }
        parts += "↓" + speed(ent.speed.toLong()) + " ↑" + speed(t.uploadSpeed)
        parts += String.format(text("TORRENT_PEERS"), t.peers)
        if (ent.eta > 0 && t.phase == TorrentPhase.Downloading) parts += toLongEta(ent.eta) + " left"
        return parts.joinToString(GAP)
    }

    /** The status text right of the name. */
    fun progressText(ent: DbRecord, text: (String) -> String = I8N::text): String? {
        if (isSeeding(ent)) return text("TORRENT_SEEDING")
        val t = running(ent) ?: return null
        return when (t.phase) {
            TorrentPhase.Metadata -> text("TORRENT_PHASE_METADATA")
            TorrentPhase.Checking -> String.format(text("TORRENT_PHASE_CHECKING"), t.checkPermille / 10)
            else -> null
        }
    }

    private fun speed(bytesPerSecond: Long) = formatSize(bytesPerSecond.coerceAtLeast(0).toDouble()) + "/s"
}
