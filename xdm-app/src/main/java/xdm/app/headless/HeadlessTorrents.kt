package xdm.app.headless

import xdm.app.utils.TorrentLibrary
import xdm.core.downloaders.TaskInfoDB
import xdm.core.downloaders.TorrentDownloadTaskInfo
import xdm.core.util.Logger
import xdm.manager.torrent.JniTorrentEngine
import xdm.manager.torrent.TorrentEngine
import xdm.manager.torrent.TorrentEvent
import xdm.manager.torrent.TorrentEventType
import xdm.manager.torrent.TorrentFlags
import xdm.manager.torrent.TorrentListener
import xdm.manager.torrent.TorrentProxy
import xdm.manager.torrent.TorrentService
import xdm.manager.torrent.TorrentSettings
import xdm.manager.torrent.TorrentTracker
import xdm.manager.torrent.TorrentTrackers
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A command-line argument that is a torrent ([TorrentArgs.sourceOf]). */
sealed interface TorrentSource {
    val text: String

    data class Magnet(override val text: String) : TorrentSource
    /** A .torrent file on disk. */
    data class LocalFile(override val text: String) : TorrentSource
    /** A .torrent file to fetch over HTTP(S) first. */
    data class Link(override val text: String) : TorrentSource
    /** `--check-trackers` only: an announce URL checked on its own. */
    data class Tracker(override val text: String) : TorrentSource
}

object TorrentArgs {
    /**
     * What [arg] is as a torrent, or null when it is not one (then it is an HTTP/HLS/DASH URL):
     * a magnet link; a `.torrent` path or `file:` URI; an http(s) URL whose path ends in `.torrent`;
     * with `--type torrent` any path or http(s) URL. With [checkTrackers], any other http(s) or udp URL is
     * a tracker to check.
     */
    fun sourceOf(arg: String, type: StreamType, checkTrackers: Boolean): TorrentSource? {
        val a = arg.trim()
        val lower = a.lowercase(Locale.ROOT)
        if (lower.startsWith("magnet:?")) return TorrentSource.Magnet(a)
        if (lower.startsWith("file:")) return runCatching { File(URI(a)).path }.getOrNull()?.let { TorrentSource.LocalFile(it) }
        val http = lower.startsWith("http://") || lower.startsWith("https://")
        val udp = lower.startsWith("udp://")
        if (http) {
            val path = runCatching { URI(a).path }.getOrNull().orEmpty().lowercase(Locale.ROOT)
            return when {
                path.endsWith(".torrent") || (type == StreamType.Torrent && !checkTrackers) -> TorrentSource.Link(a)
                checkTrackers -> TorrentSource.Tracker(a)
                else -> null
            }
        }
        if (udp) return if (checkTrackers) TorrentSource.Tracker(a) else null
        return if (lower.endsWith(".torrent") || type == StreamType.Torrent) TorrentSource.LocalFile(a) else null
    }

    /** The `dn=` of a magnet link, decoded; null when it has none. */
    fun magnetName(magnet: String): String? = magnet.substringAfter('?', "").split('&')
        .firstOrNull { it.startsWith("dn=", ignoreCase = true) }
        ?.let { runCatching { URLDecoder.decode(it.substring(3), Charsets.UTF_8) }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

    /**
     * A magnet link for checking bare tracker URLs: a random info-hash, so no real torrent is asked for. TLS (and
     * a UDP connect) happen before the tracker looks at the hash, so an unknown one still tests the connection.
     */
    fun checkMagnet(trackers: List<String>, random: SecureRandom = SecureRandom()): String {
        val hash = ByteArray(20).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        return "magnet:?xt=urn:btih:$hash&dn=xdm-tracker-check" +
            trackers.joinToString("") { "&tr=" + URLEncoder.encode(it, Charsets.UTF_8) }
    }
}

/** How one tracker answered a `--check-trackers` run. */
enum class TrackerVerdict(val label: String) {
    /** The announce worked. */
    OK("OK"),
    /** TLS failed: the certificate was not trusted, expired, for another host, or the handshake broke. */
    CERTIFICATE("CERTIFICATE"),
    /** The connection worked, but the tracker refused the request or answered with an error. */
    REACHED("REACHED"),
    /** No connection: name lookup, refused, unreachable, timed out. */
    FAILED("FAILED"),
    /** No answer yet. */
    PENDING("NO ANSWER"),
}

object TrackerCheck {
    const val EXIT_CERTIFICATE = 3
    const val EXIT_UNREACHED = 4

    /** Lower-case parts of TLS errors (OpenSSL and asio's wording). */
    private val tlsWords = listOf(
        "certificate", "ssl", "tls", "handshake", "verify failed", "self-signed", "self signed", "issuer",
        "expired", "wrong version number", "unknown ca", "hostname mismatch",
    )

    /** Lower-case parts of connection errors (asio's and Windows' wording), where the tracker was never reached. */
    private val networkWords = listOf(
        "timed out", "timeout", "refused", "unreachable", "host not found", "no such host", "name resolution",
        "not known", "getaddrinfo", "connection reset", "connection aborted", "network is down", "no route",
        "operation aborted", "end of file", "eof", "ssrf", "blocked", "invalid tracker url", "unsupported url protocol",
    )

    fun isTlsError(message: String): Boolean = message.lowercase(Locale.ROOT).let { m -> tlsWords.any { it in m } }

    fun isNetworkError(message: String): Boolean = message.lowercase(Locale.ROOT).let { m -> networkWords.any { it in m } }

    /**
     * libtorrent announces once per local interface and skips the ones that can't reach the tracker (link-local,
     * VPN tunnels) with this error. It says nothing about the tracker: the real answer comes from another interface.
     */
    fun isSkipped(message: String): Boolean = message.contains("skipping tracker announce", ignoreCase = true)

    private fun classify(message: String): TrackerVerdict = when {
        isTlsError(message) -> TrackerVerdict.CERTIFICATE
        message.isBlank() || isNetworkError(message) -> TrackerVerdict.FAILED
        else -> TrackerVerdict.REACHED
    }

    /**
     * [t] as the tracker list has it, and [endpointError], the latest error one interface reported for it
     * ([parseTrackerLog]): the list keeps only the first interface's error, often a skipped one.
     */
    fun verdict(t: TorrentTracker, endpointError: String? = null): TrackerVerdict = when {
        t.status == TorrentTracker.WORKING -> TrackerVerdict.OK
        endpointError != null -> classify(endpointError)
        t.status == TorrentTracker.ERROR && !isSkipped(t.message) -> classify(t.message)
        else -> TrackerVerdict.PENDING
    }

    private val trackerLog = Regex("""\((\S+)\)\[[^\]]*] (.*?)(?: "(.*)")?(?: \(\d+\))?$""")

    /**
     * The tracker URL and error of a tracker error log line (libtorrent's tracker alert:
     * `<hash> (<url>)[<local endpoint>] <error> "<tracker's failure reason>" (<times in a row>)`); null for other
     * lines and for skipped interfaces ([isSkipped]).
     */
    fun parseTrackerLog(line: String): Pair<String, String>? {
        val m = trackerLog.find(line) ?: return null
        val url = m.groupValues[1]
        if (!url.contains("://")) return null
        val error = m.groupValues[2].trim()
        val reason = m.groupValues[3].trim()
        val message = if (reason.isNotEmpty()) "$error: $reason" else error
        if (message.isEmpty() || isSkipped(message)) return null
        return url to message
    }

    /** 0 when no tracker had a TLS failure and at least one was reached, else [EXIT_CERTIFICATE] or [EXIT_UNREACHED]. */
    fun exitCode(verdicts: Collection<TrackerVerdict>): Int = when {
        TrackerVerdict.CERTIFICATE in verdicts -> EXIT_CERTIFICATE
        verdicts.none { it == TrackerVerdict.OK || it == TrackerVerdict.REACHED } -> EXIT_UNREACHED
        else -> 0
    }
}

/**
 * The BitTorrent side of a headless run: one session (the same [TorrentService] and settings the app uses, with
 * its state in the run's scratch folder), started when the first torrent comes up and shut down at the end.
 */
class HeadlessTorrents(
    private val folder: File,
    private val o: CliOptions,
    engineFactory: () -> TorrentEngine? = defaultEngineFactory,
    private val check: Boolean = o.checkTrackers,
) {
    init {
        folder.mkdirs()
    }

    val service = TorrentService(
        folder.path, engineFactory, ::settings,
        // One run: never close for being idle between two torrents.
        keepOpen = { true },
    )
    val taskInfoDB = TaskInfoDB(folder.path)

    val available: Boolean get() = service.available

    private fun settings(listenPort: Int?): String {
        val proxy = o.proxy?.let { CliParser.parseProxy(it) }?.let { (socks, host, port) ->
            TorrentProxy(if (socks) TorrentProxy.Type.SOCKS5 else TorrentProxy.Type.HTTP, host, port, o.proxyUser, o.proxyPassword)
        }
        val extra = LinkedHashMap<String, String>()
        if (o.insecure) extra["validate_https_trackers"] = "false"
        if (check) {
            // Trackers only: no other way to find peers, every tracker announced to (not just the first per tier).
            for (k in listOf("enable_dht", "enable_lsd", "enable_upnp", "enable_natpmp")) extra[k] = "false"
            extra["announce_to_all_trackers"] = "true"
        }
        return TorrentSettings.session(listenPort ?: TorrentSettings.randomPort(), o.speedLimit * 1024, 0, proxy, extra)
    }

    /** --tracker URLs, each in a tier of its own (all are announced to, like the app's extra trackers). */
    private val extraTiers: List<List<String>> get() = o.trackers.map { listOf(it) }

    /**
     * The task for downloading [source] into [target], with its .torrent (if any) saved for the task. [fetch]
     * downloads a [TorrentSource.Link]. Throws [CliUsageException] with the message to show.
     */
    fun prepare(id: Long, source: TorrentSource, target: File, fetch: (String) -> ByteArray?): TorrentDownloadTaskInfo {
        if (!available) throw CliUsageException("Torrent support is not available: the xdmtorrent library was not found")
        var bytes: ByteArray? = when (source) {
            is TorrentSource.LocalFile -> readTorrentFile(source.text)
            is TorrentSource.Link -> fetch(source.text) ?: throw CliUsageException("Not a torrent file: ${source.text}")
            else -> null
        }
        var magnet = (source as? TorrentSource.Magnet)?.text
        val name: String
        val key: String
        if (bytes != null) {
            val info = service.parse(bytes) ?: throw CliUsageException("Not a valid torrent file: ${source.text}")
            key = info.key
            name = info.name.ifBlank { key }
            if (extraTiers.isNotEmpty() && !info.isPrivate) bytes = TorrentTrackers.addToTorrent(bytes, extraTiers) ?: bytes
            if (!service.saveTorrent(id, bytes)) throw CliUsageException("Cannot save the torrent in ${folder.path}")
        } else {
            val m = magnet ?: throw CliUsageException("Not a torrent: ${source.text}")
            key = service.magnetKey(m) ?: throw CliUsageException("Not a valid magnet link: $m")
            name = TorrentArgs.magnetName(m) ?: key
            if (extraTiers.isNotEmpty()) magnet = TorrentTrackers.addToMagnet(m, extraTiers)
        }
        val task = TorrentDownloadTaskInfo(id = id, key = key, name = name, folder = target.path, magnet = magnet, categoryId = null)
        if (!taskInfoDB.saveTorrentTask(task)) throw CliUsageException("Cannot save the torrent in ${folder.path}")
        return task
    }

    private fun readTorrentFile(path: String): ByteArray {
        val f = File(path)
        if (!f.isFile) throw CliUsageException("No such torrent file: $path")
        if (f.length() > MAX_TORRENT_BYTES) throw CliUsageException("Too large for a torrent file: $path")
        return f.readBytes()
    }

    /** Tracker errors of [key] not reported yet, as `url: message`, for --verbose. */
    private val reported = HashSet<String>()

    fun newTrackerErrors(key: String): List<String> {
        val trackers = service.details(key)?.trackers ?: return emptyList()
        return trackers.filter { it.status == TorrentTracker.ERROR && it.message.isNotBlank() && !TrackerCheck.isSkipped(it.message) }
            .map { "${it.url}: ${it.message}" }
            .filter { synchronized(reported) { reported.add(it) } }
    }

    /**
     * `--check-trackers`: adds each torrent (and one random-hash torrent for the bare tracker URLs and --tracker)
     * in upload mode, so nothing is written, waits until every tracker answered or [timeoutMs] passed, prints one
     * line per tracker to [out] and returns [TrackerCheck.exitCode] of them.
     */
    fun checkTrackers(
        sources: List<TorrentSource>, timeoutMs: Long, fetch: (String) -> ByteArray?,
        out: (String) -> Unit, err: (String) -> Unit,
    ): Int {
        if (!available) {
            err("Torrent support is not available: the xdmtorrent library was not found")
            return TrackerCheck.EXIT_UNREACHED
        }
        val e = service.start()
        val save = File(folder, "check").apply { mkdirs() }.path
        val keys = ArrayList<Pair<String, String>>() // key to what it came from
        // Each interface's tracker errors, from the log events: url -> latest real error.
        val endpointErrors = ConcurrentHashMap<String, String>()
        val listener = object : TorrentListener {
            override fun onEvent(event: TorrentEvent) {
                if (event.type != TorrentEventType.LOG) return
                TrackerCheck.parseTrackerLog(event.message)?.let { (url, message) -> endpointErrors[url] = message }
            }
        }
        val bare = sources.filterIsInstance<TorrentSource.Tracker>().map { it.text } + o.trackers
        if (bare.isNotEmpty()) {
            val key = e.addMagnet(TorrentArgs.checkMagnet(bare), null, save, TorrentFlags.UPLOAD_MODE)
            service.attach(key, null, listener)
            keys += key to "trackers"
        }
        for (s in sources) {
            try {
                val key = when (s) {
                    is TorrentSource.Tracker -> continue
                    is TorrentSource.Magnet -> e.addMagnet(s.text, null, save, TorrentFlags.UPLOAD_MODE)
                    is TorrentSource.LocalFile -> e.addTorrent(readTorrentFile(s.text), null, save, null, TorrentFlags.UPLOAD_MODE)
                    is TorrentSource.Link -> e.addTorrent(
                        fetch(s.text) ?: throw CliUsageException("Not a torrent file: ${s.text}"), null, save, null, TorrentFlags.UPLOAD_MODE,
                    )
                }
                service.attach(key, null, listener)
                keys += key to s.text
            } catch (ex: Exception) {
                err("Cannot check ${s.text}: ${ex.message}")
            }
        }
        val verdicts = LinkedHashMap<String, Pair<TrackerVerdict, TorrentTracker>>() // url -> latest
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            for ((key, _) in keys) {
                for (t in runCatching { e.trackers(key) }.getOrDefault(emptyList())) {
                    val endpointError = endpointErrors[t.url]
                    val v = TrackerCheck.verdict(t, endpointError)
                    // A final answer is kept; libtorrent may retry a failed tracker and show it pending again.
                    val old = verdicts[t.url]
                    val shown = if (endpointError != null && v != TrackerVerdict.OK) t.copy(message = endpointError) else t
                    if (old == null || old.first == TrackerVerdict.PENDING || v == TrackerVerdict.OK) verdicts[t.url] = v to shown
                }
            }
            val done = verdicts.isNotEmpty() && verdicts.values.none { it.first == TrackerVerdict.PENDING }
            if (done || System.currentTimeMillis() >= deadline) break
            Thread.sleep(250)
        }
        for ((key, _) in keys) {
            service.detach(key, listener)
            runCatching { e.remove(key, false) }
        }
        if (verdicts.isEmpty()) {
            err("No trackers to check")
            return TrackerCheck.EXIT_UNREACHED
        }
        val width = TrackerVerdict.entries.maxOf { it.label.length }
        for ((url, vt) in verdicts) {
            val (v, t) = vt
            val detail = when {
                t.message.isBlank() || TrackerCheck.isSkipped(t.message) -> ""
                v != TrackerVerdict.OK || o.verbose -> "  (${t.message})"
                else -> ""
            }
            out(v.label.padEnd(width) + "  " + url + detail)
        }
        return TrackerCheck.exitCode(verdicts.values.map { it.first })
    }

    /** Ends the session: resume data and state go to the scratch folder, which the run deletes. */
    fun shutdown() {
        runCatching { service.shutdown(5_000) }.onFailure { Logger.error("Torrent", "Closing the session", it) }
    }

    companion object {
        /** The native library, found as the app finds it (`-Dxdm.torrent.library` in the packaged app). */
        val defaultEngineFactory: () -> TorrentEngine? = { if (TorrentLibrary.load()) JniTorrentEngine() else null }

        /** libtorrent's own limit for a .torrent file it loads. */
        const val MAX_TORRENT_BYTES = 10 * 1024 * 1024
    }
}
