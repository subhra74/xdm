package xdm.app.torrent

import xdm.manager.torrent.FilePriority
import xdm.manager.torrent.PieceMap
import xdm.manager.torrent.TorrentInfo
import xdm.manager.torrent.TorrentPeer

/**
 * The torrent window's Files tab: the same rows as the add dialog ([TorrentSelection]), with each file's
 * priority and progress. Priorities are kept here as the user sets them (libtorrent applies them a moment
 * later); progress comes from the latest live file list, or is unknown when the torrent is not running.
 */
class TorrentFiles(val info: TorrentInfo, saved: ByteArray) {
    val rows: List<TorrentSelection.Row> = TorrentSelection(info).rows

    private val priorities = ByteArray(info.files.size) {
        if (saved.isEmpty()) FilePriority.NORMAL else saved.getOrElse(it) { FilePriority.NORMAL }
    }

    /** Bytes done per file; null when unknown (not running). */
    private var done: LongArray? = null

    /** Takes progress from a live file list of the same torrent. */
    fun updateProgress(live: TorrentInfo?) {
        done = live?.files?.takeIf { it.size == info.files.size }?.map { it.done }?.toLongArray()
    }

    /** Every wanted file is complete (a finished torrent that is not running). */
    fun markComplete() {
        done = LongArray(info.files.size) { if (priorities[it] != FilePriority.SKIP) info.files[it].size else 0 }
    }

    /** A row's priority; null for a folder whose files differ. */
    fun priority(row: TorrentSelection.Row): Byte? =
        row.files.map { priorities[it] }.distinct().singleOrNull()

    fun setPriority(row: TorrentSelection.Row, p: Byte) = row.files.forEach { priorities[it] = p }

    /** 0–100, or null when unknown. A skipped file counts what it has. */
    fun progress(row: TorrentSelection.Row): Int? {
        val d = done ?: return null
        val size = row.files.sumOf { info.files[it].size }
        if (size <= 0) return 100
        return (row.files.sumOf { d[it] } * 100 / size).toInt()
    }

    /** The priorities to save: libtorrent's file order, padding normal; empty when all are normal. */
    fun priorities(): ByteArray {
        val p = ByteArray(info.files.size) { if (info.files[it].isPad) FilePriority.NORMAL else priorities[it] }
        return if (p.all { it == FilePriority.NORMAL }) ByteArray(0) else p
    }

    companion object {
        /** The four levels the Files tab offers, lowest first. */
        val LEVELS = listOf(FilePriority.SKIP, FilePriority.LOW, FilePriority.NORMAL, FilePriority.HIGH)
    }
}

object PieceBar {
    /**
     * How much of each of [width] columns is downloaded (0–1), [pieces] spread evenly over the width: with more
     * pieces than columns a column averages its pieces, with fewer each piece spans several columns.
     */
    fun columns(pieces: PieceMap, width: Int): FloatArray {
        val n = pieces.numPieces
        if (n <= 0 || width <= 0) return FloatArray(maxOf(width, 0))
        return FloatArray(width) { x ->
            val from = (x.toLong() * n / width).toInt()
            val to = maxOf(from + 1, ((x + 1).toLong() * n / width).toInt())
            (from until to).count { pieces.has(it) }.toFloat() / (to - from)
        }
    }
}

/** Short texts for the Peers tab. */
object PeerText {
    /** Letters for the peer's state, as most clients show them: E encrypted, U uTP, I incoming, S seed. */
    fun flags(p: TorrentPeer): String = buildString {
        if (p.flags and TorrentPeer.SEED != 0) append('S')
        if (p.flags and TorrentPeer.INCOMING != 0) append('I')
        if (p.flags and TorrentPeer.ENCRYPTED != 0) append('E')
        if (p.flags and TorrentPeer.UTP != 0) append('U')
    }

    /** Where the peer was found: tracker, DHT, PeX, LSD, saved. */
    fun sources(p: TorrentPeer): String = listOfNotNull(
        "Tracker".takeIf { p.sources and TorrentPeer.SRC_TRACKER != 0 },
        "DHT".takeIf { p.sources and TorrentPeer.SRC_DHT != 0 },
        "PeX".takeIf { p.sources and TorrentPeer.SRC_PEX != 0 },
        "LSD".takeIf { p.sources and TorrentPeer.SRC_LSD != 0 },
        "Saved".takeIf { p.sources and TorrentPeer.SRC_RESUME != 0 },
    ).joinToString(", ")

    /** How many connected peers each discovery way gave: DHT, PeX, LSD. */
    fun countBySource(peers: List<TorrentPeer>, source: Int) = peers.count { it.sources and source != 0 }
}
