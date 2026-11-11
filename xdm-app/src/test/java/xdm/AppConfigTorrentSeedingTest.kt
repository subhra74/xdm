package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import xdm.app.utils.TorrentLibrary
import java.io.File
import java.nio.file.Files

/** The torrent seeding settings (off by default, ratio 1.0, no time limit) round-trip; the loader fails softly. */
class AppConfigTorrentSeedingTest {
    private lateinit var dir: File

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-config").toFile()
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty(TorrentLibrary.PROPERTY)
        dir.deleteRecursively()
    }

    private fun load() = AppConfig(dir.absolutePath).apply { load() }

    @Test
    fun defaults() {
        val c = AppConfig(dir.absolutePath)
        assertFalse(c.torrentSeedAfterFinish, "no seeding after finish")
        assertEquals(1.0f, c.torrentSeedRatioLimit, "ratio 1.0")
        assertEquals(0, c.torrentSeedTimeLimitMinutes, "no time limit")
        assertTrue(c.torrentProgressMore && c.progressMore, "progress windows open with their tabs / segment table")
        assertTrue(c.torrentPortMapping && c.torrentDht && c.torrentLsd, "peer discovery and port mapping on")
        assertEquals(listOf(0, 0, 0, 0), listOf(c.torrentPort, c.torrentEncryption, c.torrentDownloadLimit, c.torrentUploadLimit), "random port, prefer encryption, no limits")
    }

    @Test
    fun roundTrip() {
        AppConfig(dir.absolutePath).apply {
            torrentSeedAfterFinish = true
            torrentSeedRatioLimit = 2.25f
            torrentSeedTimeLimitMinutes = 90
            torrentProgressMore = false
            progressMore = false
            torrentPort = 6881
            torrentPortMapping = false
            torrentDht = false
            torrentLsd = false
            torrentEncryption = 1
            torrentDownloadLimit = 500
            torrentUploadLimit = 50
        }.save()
        val c = load()
        assertTrue(c.torrentSeedAfterFinish, "seed after finish")
        assertEquals(2.25f, c.torrentSeedRatioLimit, "ratio kept to the permille")
        assertEquals(90, c.torrentSeedTimeLimitMinutes, "minutes")
        assertFalse(c.torrentProgressMore || c.progressMore, "Less remembered")
        assertEquals(listOf(6881, 1, 500, 50), listOf(c.torrentPort, c.torrentEncryption, c.torrentDownloadLimit, c.torrentUploadLimit), "numbers")
        assertFalse(c.torrentPortMapping || c.torrentDht || c.torrentLsd, "switches off")
    }

    @Test
    fun loaderFailsSoftly() {
        val bogus = File(dir, TorrentLibrary.fileName).apply { writeText("not a library") }
        System.setProperty(TorrentLibrary.PROPERTY, bogus.absolutePath)
        assertEquals(bogus, TorrentLibrary.candidates().first(), "the property comes first")
        assertFalse(TorrentLibrary.load(), "an unloadable file disables torrents instead of throwing")
    }
}
