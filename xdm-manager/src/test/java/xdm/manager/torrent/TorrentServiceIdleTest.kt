package xdm.manager.torrent

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/** The session closes once idle unless kept open, and opens again for the next torrent. */
class TorrentServiceIdleTest {
    private val dir: File = Files.createTempDirectory("xdm-torrent-idle").toFile()

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun `an idle session closes, saves its state, and a later start opens it again`() {
        val engine = FakeTorrentEngine()
        val service = TorrentService(dir.absolutePath, { engine }, { "" }, idleMs = 300)
        service.start()
        waitFor("closed") { engine.closed }
        assertFalse(service.started, "no session")
        assertTrue(File(dir, TorrentService.STATE_FILE).isFile, "state saved for a warm start")
        engine.closed = false
        service.start()
        assertEquals(2, engine.opened.get(), "opened again")
        service.shutdown(1000)
    }

    @Test
    fun `a session with a torrent in it or kept open stays`() {
        val engine = FakeTorrentEngine()
        var keep = true
        val service = TorrentService(dir.absolutePath, { engine }, { "" }, keepOpen = { keep }, idleMs = 200)
        service.start()
        Thread.sleep(700)
        assertTrue(service.started, "kept open")
        keep = false
        service.attach("a".repeat(40), 1L, object : TorrentListener {
            override fun onEvent(event: TorrentEvent) {}
            override fun onAddFailed(error: Exception) {}
        })
        Thread.sleep(700)
        assertTrue(service.started, "a torrent is in it")
        service.detach("a".repeat(40), object : TorrentListener {
            override fun onEvent(event: TorrentEvent) {}
            override fun onAddFailed(error: Exception) {}
        })
        assertFalse(engine.closed, "not closed yet")
        service.shutdown(1000)
    }
}
