package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xdm.app.headless.CliOptions
import xdm.app.headless.CliParser
import xdm.app.headless.CliUsageException
import xdm.app.headless.HeadlessCli
import xdm.app.headless.ParsedArgs
import xdm.app.headless.StreamType
import xdm.app.headless.TorrentArgs
import xdm.app.headless.TorrentSource
import xdm.app.headless.TrackerCheck
import xdm.app.headless.TrackerVerdict
import xdm.manager.torrent.NativeTorrent
import xdm.manager.torrent.PieceMap
import xdm.manager.torrent.TorrentEngine
import xdm.manager.torrent.TorrentEngineException
import xdm.manager.torrent.TorrentEvent
import xdm.manager.torrent.TorrentEventType
import xdm.manager.torrent.TorrentFile
import xdm.manager.torrent.TorrentFlags
import xdm.manager.torrent.TorrentInfo
import xdm.manager.torrent.TorrentPeer
import xdm.manager.torrent.TorrentState
import xdm.manager.torrent.TorrentStatus
import xdm.manager.torrent.TorrentTracker
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Torrents on the headless command line: arguments, the download through the app's torrent task, --check-trackers. */
class HeadlessTorrentTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"
    private val magnet = "magnet:?xt=urn:btih:$hash&dn=Big%20Pack"

    private fun parse(vararg args: String): CliOptions =
        (CliParser.parse(arrayOf("--headless", *args)) as ParsedArgs.Run).options

    @AfterEach
    fun reset() {
        HeadlessCli.torrentEngineFactory = null
    }

    @Test
    fun recognisesTorrentArguments() {
        val auto = StreamType.Auto
        assertTrue(TorrentArgs.sourceOf(magnet, auto, false) is TorrentSource.Magnet, "magnet")
        assertTrue(TorrentArgs.sourceOf("/x/a.torrent", auto, false) is TorrentSource.LocalFile, ".torrent path")
        assertEquals("/x/a b.torrent", TorrentArgs.sourceOf("file:///x/a%20b.torrent", auto, false)?.text, "file: URI")
        assertTrue(TorrentArgs.sourceOf("https://h/f.torrent?k=1", auto, false) is TorrentSource.Link, ".torrent link")
        assertNull(TorrentArgs.sourceOf("https://h/f.zip", auto, false), "plain file stays HTTP")
        assertTrue(TorrentArgs.sourceOf("https://h/get?id=4", StreamType.Torrent, false) is TorrentSource.Link, "--type torrent")
        assertTrue(TorrentArgs.sourceOf("https://t/announce", auto, true) is TorrentSource.Tracker, "tracker when checking")
        assertTrue(TorrentArgs.sourceOf("udp://t:6969/announce", auto, true) is TorrentSource.Tracker, "udp tracker")
        assertNull(TorrentArgs.sourceOf("udp://t:6969/announce", auto, false), "udp is no download")
        assertEquals("Big Pack", TorrentArgs.magnetName(magnet), "dn decoded")
        val m = TorrentArgs.checkMagnet(listOf("https://t/announce?x=1"))
        assertTrue(Regex("^magnet:\\?xt=urn:btih:[0-9a-f]{40}&").containsMatchIn(m), "random v1 hash: $m")
        assertTrue("tr=https%3A%2F%2Ft%2Fannounce%3Fx%3D1" in m, "tracker encoded: $m")
    }

    @Test
    fun parsesTorrentOptions() {
        val o = parse("-o", "d", "--tracker", "udp://a:1/announce", "--tracker=https://b/announce", magnet, "x.torrent")
        assertEquals(listOf(magnet, "x.torrent"), o.urls, "magnet and .torrent accepted")
        assertEquals(listOf("udp://a:1/announce", "https://b/announce"), o.trackers, "repeatable --tracker")
        assertEquals(StreamType.Torrent, parse("-o", "d", "-t", "torrent", "https://h/get").type, "--type torrent")
        val c = parse("--check-trackers", "--timeout", "5", "https://t/announce")
        assertTrue(c.checkTrackers, "check mode")
        assertEquals(5, c.timeoutSeconds, "timeout")
        assertEquals(30, parse("--check-trackers", "--tracker", "https://t/a").timeoutSeconds, "default timeout, --tracker alone")
        assertThrows(CliUsageException::class.java, { parse("-o", "d", "--tracker", "ftp://x", magnet) }, "bad tracker")
        assertThrows(CliUsageException::class.java, { parse("-o", "d", "udp://t:1/announce") }, "udp only for checks")
        assertThrows(CliUsageException::class.java, { parse(magnet) }, "a download still needs -o")
    }

    private fun tracker(url: String, status: Int, message: String = "") =
        TorrentTracker(url, 0, 0, status, -1, -1, -1, 0, 0, message)

    @Test
    fun classifiesTrackerAnswers() {
        assertEquals(TrackerVerdict.OK, TrackerCheck.verdict(tracker("u", TorrentTracker.WORKING)), "working")
        assertEquals(TrackerVerdict.CERTIFICATE,
            TrackerCheck.verdict(tracker("u", TorrentTracker.ERROR, "certificate verify failed (SSL routines)")), "TLS")
        assertEquals(TrackerVerdict.CERTIFICATE,
            TrackerCheck.verdict(tracker("u", TorrentTracker.ERROR, "unable to get local issuer certificate")), "no root")
        assertEquals(TrackerVerdict.FAILED, TrackerCheck.verdict(tracker("u", TorrentTracker.ERROR, "Host not found (authoritative)")), "DNS")
        assertEquals(TrackerVerdict.FAILED, TrackerCheck.verdict(tracker("u", TorrentTracker.ERROR, "timed out")), "timeout")
        assertEquals(TrackerVerdict.REACHED, TrackerCheck.verdict(tracker("u", TorrentTracker.ERROR, "unregistered torrent")), "tracker said no")
        assertEquals(TrackerVerdict.PENDING, TrackerCheck.verdict(tracker("u", TorrentTracker.UPDATING)), "no answer yet")
        val skipped = tracker("u", TorrentTracker.ERROR, "skipping tracker announce (unreachable)")
        assertEquals(TrackerVerdict.PENDING, TrackerCheck.verdict(skipped), "a skipped interface says nothing")
        assertEquals(TrackerVerdict.CERTIFICATE, TrackerCheck.verdict(skipped, "certificate verify failed (SSL routines)"),
            "another interface's error wins")
        assertEquals(TrackerVerdict.OK, TrackerCheck.verdict(tracker("u", TorrentTracker.WORKING), "timed out"), "working wins")
                assertEquals(0, TrackerCheck.exitCode(listOf(TrackerVerdict.OK, TrackerVerdict.FAILED)), "one reached")
        assertEquals(TrackerCheck.EXIT_CERTIFICATE, TrackerCheck.exitCode(listOf(TrackerVerdict.OK, TrackerVerdict.CERTIFICATE)), "TLS wins")
        assertEquals(TrackerCheck.EXIT_UNREACHED, TrackerCheck.exitCode(listOf(TrackerVerdict.FAILED, TrackerVerdict.PENDING)), "none reached")
    }

    @Test
    fun readsTrackerErrorsFromTheLog() {
        assertEquals("https://expired.badssl.com/announce" to "certificate verify failed (SSL routines)",
            TrackerCheck.parseTrackerLog(
                "8bb86d (https://expired.badssl.com/announce)[192.168.29.120:23486-v1] certificate verify failed (SSL routines) \"\" (1)"
            ), "TLS error, empty failure reason")
        assertEquals("udp://t:1/announce" to "tracker error: unregistered torrent",
            TrackerCheck.parseTrackerLog("8bb86d (udp://t:1/announce)[10.0.0.2:6881-v1] tracker error \"unregistered torrent\" (2)"),
            "the tracker's failure reason is kept")
        assertNull(TrackerCheck.parseTrackerLog(
            "8bb86d (https://t/announce)[[fe80::1%lo0]:23486-v1] skipping tracker announce (unreachable) \"\" (1)"
        ), "skipped interface")
        assertNull(TrackerCheck.parseTrackerLog("Session started"), "other log lines")
    }

    private fun capture(block: () -> Int): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(err, true))
        val code = try { block() } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        return Triple(code, out.toString(), err.toString())
    }

    @Test
    fun downloadsAMagnetThroughTheTorrentTask(@TempDir dir: File) {
        val engine = FinishingEngine()
        HeadlessCli.torrentEngineFactory = { engine }
        val target = File(dir, "out")
        val (code, out, err) = capture { HeadlessCli.run(arrayOf("--headless", "-o", target.path, "--tracker", "https://t/announce", magnet)) }
        assertEquals(HeadlessCli.EXIT_OK, code, "exit code; stderr: $err")
        assertEquals(File(target, "pack").path, out.trim(), "the torrent's folder is printed")
        val added = engine.magnets.single()
        assertTrue(added.first.startsWith(magnet) && "tr=https%3A%2F%2Ft%2Fannounce" in added.first, "extra tracker added: ${added.first}")
        assertEquals(target.path, added.second, "saved into -o")
        assertTrue(engine.opened.get().contains("validate_https_trackers=true"), "the app's tracker TLS check is on")
        assertTrue(engine.removed.contains(hash), "removed when finished (no seeding)")
        assertTrue(engine.closed, "session closed at the end")
    }

    @Test
    fun insecureTurnsTheTrackerCheckOff(@TempDir dir: File) {
        val engine = FinishingEngine()
        HeadlessCli.torrentEngineFactory = { engine }
        capture { HeadlessCli.run(arrayOf("--headless", "-k", "-o", dir.path, magnet)) }
        assertTrue(engine.opened.get().contains("validate_https_trackers=false"), "-k")
    }

    @Test
    fun withoutTheLibraryATorrentFails(@TempDir dir: File) {
        HeadlessCli.torrentEngineFactory = { null }
        val (code, _, err) = capture { HeadlessCli.run(arrayOf("--headless", "-o", dir.path, magnet)) }
        assertEquals(HeadlessCli.EXIT_FAILED, code, "exit code")
        assertTrue("Torrent support is not available" in err, "message: $err")
    }

    @Test
    fun checksTrackersWithoutDownloading(@TempDir dir: File) {
        val engine = FinishingEngine(finish = false)
        engine.trackerAnswers = listOf(
            tracker("https://good/announce", TorrentTracker.WORKING),
            tracker("https://expired.badssl.com/announce", TorrentTracker.ERROR, "certificate has expired"),
        )
        HeadlessCli.torrentEngineFactory = { engine }
        val (code, out, err) = capture {
            HeadlessCli.run(arrayOf("--headless", "--check-trackers", "--timeout", "5", "https://good/announce", "https://expired.badssl.com/announce"))
        }
        assertEquals(TrackerCheck.EXIT_CERTIFICATE, code, "a certificate failure; stderr: $err")
        val lines = out.trim().lines()
        assertTrue(lines[0].startsWith("OK") && lines[0].contains("https://good/announce"), "good: $out")
        assertTrue(lines[1].startsWith("CERTIFICATE") && lines[1].contains("certificate has expired"), "bad: $out")
        val (uri, _, flags) = engine.added.single()
        assertTrue("tr=https%3A%2F%2Fgood%2Fannounce" in uri, "one random-hash torrent for both: $uri")
        assertEquals(TorrentFlags.UPLOAD_MODE, flags and TorrentFlags.UPLOAD_MODE, "nothing written")
        val settings = engine.opened.get()
        assertTrue("enable_dht=false" in settings && "announce_to_all_trackers=true" in settings, "trackers only: $settings")
    }

    /**
     * A session whose torrents finish right away (STATUS, FINISHED, then FLUSHED, RESUME_DATA and REMOVED as
     * libtorrent answers flush, resume and remove), or with [finish] false just sit there reporting [trackerAnswers].
     */
    private class FinishingEngine(private val finish: Boolean = true) : TorrentEngine {
        val opened = java.util.concurrent.atomic.AtomicReference("")
        val magnets = CopyOnWriteArrayList<Pair<String, String?>>()
        val added = CopyOnWriteArrayList<Triple<String, String?, Int>>()
        val removed = CopyOnWriteArrayList<String>()
        val inSession: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile var closed = false
        @Volatile var trackerAnswers: List<TorrentTracker> = emptyList()
        private val events = LinkedBlockingQueue<TorrentEvent>()

        override fun open(settings: String, state: ByteArray?) { opened.set(settings) }
        override fun applySettings(settings: String) {}
        override fun state(): ByteArray? = null
        override fun listenPort() = 6881

        override fun addTorrent(torrent: ByteArray?, resume: ByteArray?, savePath: String?, priorities: ByteArray?, flags: Int): String =
            throw TorrentEngineException(NativeTorrent.E_INVALID, "not used")

        override fun addMagnet(uri: String, resume: ByteArray?, savePath: String?, flags: Int): String {
            val key = magnetKey(uri) ?: throw TorrentEngineException(NativeTorrent.E_INVALID, "bad magnet")
            inSession += key
            magnets += uri to savePath
            added += Triple(uri, savePath, flags)
            if (finish) {
                events += TorrentEvent(TorrentEventType.STATUS, key, status = status(key))
                events += TorrentEvent(TorrentEventType.FINISHED, key)
            }
            return key
        }

        private fun status(key: String) = TorrentStatus(
            key = key, state = TorrentState.FINISHED, flags = TorrentStatus.SF_HAS_METADATA or TorrentStatus.SF_FINISHED,
            totalWanted = 100, totalWantedDone = 100, totalDone = 100, allTimeDownload = 100, allTimeUpload = 0,
            downloadRate = 1000, uploadRate = 0, peers = 3, seeds = 2, complete = -1, incomplete = -1, connections = 3,
            progressPpm = 1_000_000, addedTime = 0, completedTime = 0, activeSeconds = 0, seedingSeconds = 0,
            availabilityMilli = -1, numPieces = 10, piecesDone = 10, name = "pack", savePath = "", error = "",
        )

        override fun pause(key: String) { events += TorrentEvent(TorrentEventType.PAUSED, key) }
        override fun resume(key: String) {}
        override fun remove(key: String, deleteFiles: Boolean) {
            inSession -= key
            removed += key
            events += TorrentEvent(TorrentEventType.REMOVED, key)
        }
        override fun recheck(key: String) {}
        override fun reannounce(key: String) {}
        override fun setFilePriorities(key: String, priorities: ByteArray) {}
        override fun setFlags(key: String, set: Int, clear: Int) {}
        override fun setLimits(key: String, downloadBps: Int, uploadBps: Int, maxConnections: Int) {}
        override fun moveStorage(key: String, path: String) {}
        override fun addTracker(key: String, url: String, tier: Int) {}
        override fun requestResume(key: String, flags: Int) {
            events += TorrentEvent(TorrentEventType.RESUME_DATA, key, data = "resume".toByteArray())
        }
        override fun flush(key: String) { events += TorrentEvent(TorrentEventType.FLUSHED, key) }
        override fun requestResumeAll(flags: Int): Int = 0
        override fun addPeer(key: String, ip: String, port: Int) {}
        override fun postUpdates() {}
        override fun postSessionStats() {}

        override fun poll(timeoutMs: Int): List<TorrentEvent> {
            val first = events.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return emptyList()
            return arrayListOf(first).also { events.drainTo(it) }
        }

        override fun info(key: String): TorrentInfo? = if (key in inSession)
            TorrentInfo(key, "", "pack", 100, 16384, 10, 0, "", "", 0, listOf(TorrentFile(100, 0, 4, 0, "pack/a.bin")))
        else null

        override fun metadata(key: String): ByteArray? = null
        override fun pieces(key: String): PieceMap? = null
        override fun peers(key: String) = emptyList<TorrentPeer>()
        override fun trackers(key: String) = if (key in inSession) trackerAnswers else emptyList()
        override fun close() { closed = true }
        override fun parseTorrent(torrent: ByteArray): TorrentInfo? = null
        override fun magnetKey(uri: String): String? = uri.substringAfter("btih:", "").take(40).takeIf { it.length == 40 }
    }
}
