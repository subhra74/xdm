package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xdm.app.torrent.TorrentRowText
import xdm.core.downloaders.DownloadStatusInfo.TorrentProgress
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.TorrentPhase
import xdm.manager.DbRecord
import xdm.manager.RecordStatus

/** The download row's texts for running and seeding torrents. */
class TorrentRowTextTest {
    private val labels = mapOf(
        "TORRENT_PEERS" to "Peers: %d", "TORRENT_RATIO" to "ratio %.2f", "TORRENT_SEEDING" to "Seeding",
        "TORRENT_PHASE_METADATA" to "Getting metadata", "TORRENT_PHASE_CHECKING" to "Checking %d%%",
    )
    private val text: (String) -> String = { labels.getValue(it) }

    private fun record(status: RecordStatus, type: DownloadType = DownloadType.Torrent) = DbRecord(
        id = 1, size = 4L shl 30, downloaded = 1L shl 30, progress = 25, date = 0, fileName = "ubuntu.iso",
        eta = 300, speed = 3f * (1 shl 20), selected = false, status = status, downloadType = type,
    )

    private fun progress(phase: TorrentPhase, seeding: Boolean = false) = TorrentProgress(
        phase = phase, wanted = 4L shl 30, uploadSpeed = 200L shl 10, uploaded = 0, ratio = 0.4f, peers = 12,
        seeds = 3, checkPermille = 400, seeding = seeding,
    )

    @Test
    fun `a downloading torrent shows sizes, both speeds, peers and time left, without the date`() {
        val r = record(RecordStatus.DOWNLOADING).apply { torrent = progress(TorrentPhase.Downloading) }
        val line = TorrentRowText.infoLine(r, text)!!
        assertEquals(emptyList<String>(), listOf("1024.0 MB / 4.0 GB", "↓", "↑", "Peers: 12", "left").filterNot { it in line },
            "missing parts of: $line")
        assertNull(TorrentRowText.progressText(r, text), "the usual Downloading N%")
    }

    @Test
    fun `metadata and checking phases have their own status text`() {
        val meta = record(RecordStatus.DOWNLOADING).apply { torrent = progress(TorrentPhase.Metadata) }
        assertEquals("Getting metadata", TorrentRowText.progressText(meta, text), "metadata")
        assertEquals(false, TorrentRowText.infoLine(meta, text)!!.contains("/ "), "no sizes before the metadata")
        val check = record(RecordStatus.DOWNLOADING).apply { torrent = progress(TorrentPhase.Checking) }
        assertEquals("Checking 40%", TorrentRowText.progressText(check, text), "per mille to percent")
    }

    @Test
    fun `a seeding torrent shows upload speed and ratio, and a finished one the usual row`() {
        val seeding = record(RecordStatus.FINISHED).apply {
            seeding = true
            torrent = progress(TorrentPhase.Seeding, seeding = true)
        }
        assertEquals("Seeding", TorrentRowText.progressText(seeding, text), "pill text")
        val line = TorrentRowText.infoLine(seeding, text)!!
        assertEquals(true, line.endsWith("ratio 0.40") && "↑" in line, "upload and ratio: $line")
        val done = record(RecordStatus.FINISHED)
        assertNull(TorrentRowText.infoLine(done, text), "finished, not seeding")
        assertNull(TorrentRowText.progressText(done, text), "finished, not seeding")
    }

    @Test
    fun `other downloads and stopped torrents keep the usual texts`() {
        val http = record(RecordStatus.DOWNLOADING, DownloadType.Http).apply { torrent = progress(TorrentPhase.Downloading) }
        assertNull(TorrentRowText.infoLine(http, text), "not a torrent")
        val paused = record(RecordStatus.PAUSED).apply { torrent = progress(TorrentPhase.Downloading) }
        assertNull(TorrentRowText.infoLine(paused, text), "paused")
    }
}
