package xdm.integration

import xdm.core.media.Mp4InitProbe
import xdm.core.util.Logger

/**
 * Videos that sites play as two separate single-file tracks (one video-only, one audio-only fMP4),
 * found from the CDN requests the browser makes and nothing else: Facebook/Instagram ([MetaCdnRule])
 * and bilibili.com ([BilibiliCdnRule]). No manifest is ever seen, so this pairs the tracks itself:
 *
 *  1. A request whose URL a rule recognises is claimed, whatever range of the file it was for. Every
 *     later request for the same file is dropped on the spot (a player sends dozens per file).
 *  2. Each new file is probed once: its head is fetched and read ([Mp4InitProbe]) for track type,
 *     codec, resolution, duration, size and the `sidx` the download is cut by.
 *  3. Per asset (both tracks of one video), the best video and the best audio are offered as one DASH
 *     capture. The asset keeps its capture id, so a better track found later replaces the entry.
 *
 * All network work goes through [env], which also decides where it runs; state is guarded by `this`.
 * See docs/site-video-detection.md.
 */
class SplitTrackDetector(private val env: Env) {

    interface Env {
        /** Runs [task] off the caller's thread (the browser-integration server must not wait on probes). */
        fun execute(task: () -> Unit)

        /** Fetches and reads the head of [url]; null if it is not a readable fMP4 or the request failed. */
        fun probe(url: String, msg: ExtensionMessage): Probe?

        /** Publishes (or replaces, same [Offer.id]) a capture. */
        fun offer(offer: Offer)
    }

    data class Probe(
        val info: Mp4InitProbe.Info,
        val totalSize: Long,
        /**
         * The file's head up to its first fragment (`ftyp`, `moov`, `sidx`), kept so a preview of the
         * track needs no second read of it. Null when the probe read no `sidx`, or the head is large.
         */
        val head: ByteArray? = null,
    ) {
        val bitrate: Long
            get() = if (info.durationSeconds > 0) (totalSize * 8 / info.durationSeconds).toLong() else 0
    }

    data class Track(val url: String, val probe: Probe)

    data class Offer(val id: Long, val video: Track, val audio: Track, val msg: ExtensionMessage)

    private enum class Status { PENDING, DONE, FAILED, SKIPPED }

    private class Candidate(val url: SplitTrackUrl, val msg: ExtensionMessage) {
        var status = Status.PENDING
        var probe: Probe? = null
    }

    private class Asset(val id: Long) {
        val candidates = LinkedHashMap<String, Candidate>()
        lateinit var lastMsg: ExtensionMessage
        var offered: Pair<String, String>? = null
    }

    /** Tab id -> asset key -> asset, both in insertion order so the oldest can be dropped. */
    private val tabs = object : LinkedHashMap<String, LinkedHashMap<String, Asset>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkedHashMap<String, Asset>>) =
            size > MAX_TABS
    }

    /**
     * Claims [msg] if its URL is a split track. Returns false for anything else, which then goes
     * through the usual media handling.
     */
    fun onMediaMessage(msg: ExtensionMessage, newId: () -> Long): Boolean {
        val url = msg.url ?: return false
        val track = MetaCdnRule.match(url) ?: BilibiliCdnRule.match(url) ?: return false
        if (track.ad) return true
        val tabId = msg.tabId ?: "-1"
        var probe: Candidate? = null
        synchronized(this) {
            val assets = tabs.getOrPut(tabId) { LinkedHashMap() }
            val asset = assets.getOrPut(track.assetKey) {
                if (assets.size >= MAX_ASSETS_PER_TAB) assets.remove(assets.keys.first())
                Asset(newId())
            }
            asset.lastMsg = msg
            if (asset.candidates.containsKey(track.trackKey)) return true
            val candidate = Candidate(track, msg)
            asset.candidates[track.trackKey] = candidate
            if (worthProbing(asset, candidate)) probe = candidate else candidate.status = Status.SKIPPED
        }
        probe?.let { c -> env.execute { runProbe(tabId, track.assetKey, c) } }
        return true
    }

    /** Forgets a tab's captures: it reloaded or navigated. */
    @Synchronized
    fun onTabNavigated(tabId: String) {
        tabs.remove(tabId)
    }

    /** The head kept by the probe of the track at [url], in any tab; null when there is none. */
    @Synchronized
    fun headOf(url: String): ByteArray? {
        for (assets in tabs.values) {
            for (asset in assets.values) {
                for (c in asset.candidates.values) {
                    if (c.status == Status.DONE && c.url.url == url) c.probe?.head?.let { return it }
                }
            }
        }
        return null
    }

    /** A track the detector has seen, with its probe when it has one. */
    data class KnownTrack(val url: SplitTrackUrl, val probe: Probe?)

    /**
     * Every track seen, in any tab, of the videos that any of [trackKeys] belongs to (keyed by asset).
     * Lets the batch dialog pair tracks without probing them again, and add tracks the page's own list
     * missed (an audio track it did not list, a better quality). Never touches the network.
     */
    @Synchronized
    fun lookup(trackKeys: Set<String>): Map<String, List<KnownTrack>> {
        val found = LinkedHashMap<String, LinkedHashMap<String, KnownTrack>>()
        for (assets in tabs.values) {
            for ((assetKey, asset) in assets) {
                if (asset.candidates.keys.none { it in trackKeys }) continue
                val known = found.getOrPut(assetKey) { LinkedHashMap() }
                for ((key, c) in asset.candidates) {
                    val probe = c.probe.takeIf { c.status == Status.DONE }
                    if (known[key]?.probe == null) known[key] = KnownTrack(c.url, probe)
                }
            }
        }
        return found.mapValues { it.value.values.toList() }
    }

    /**
     * A track whose URL ranks it is probed only if it beats every ranked track of its kind already
     * probed or being probed: Instagram lists its whole quality ladder, and only the top is wanted.
     */
    private fun worthProbing(asset: Asset, candidate: Candidate): Boolean {
        val rank = candidate.url.rank ?: return true
        val best = asset.candidates.values
            .filter { it !== candidate && it.url.audio == candidate.url.audio && it.status != Status.FAILED && it.status != Status.SKIPPED }
            .mapNotNull { it.url.rank }
            .maxOrNull() ?: return true
        return rank > best
    }

    private fun runProbe(tabId: String, assetKey: String, candidate: Candidate) {
        val result = try {
            env.probe(candidate.url.url, candidate.msg)
        } catch (e: Exception) {
            Logger.error("XDM", "Probe failed: ${candidate.url.url}", e)
            null
        }
        val offer = synchronized(this) {
            candidate.probe = result
            candidate.status = if (result != null) Status.DONE else Status.FAILED
            val asset = tabs[tabId]?.get(assetKey) ?: return
            if (result == null) promoteSkipped(asset, candidate)
            nextOffer(asset)
        }
        offer?.let { env.offer(it) }
    }

    /** A probe failed: the best skipped track of the same kind gets its turn. */
    private fun promoteSkipped(asset: Asset, failed: Candidate) {
        val next = asset.candidates.values
            .filter { it.status == Status.SKIPPED && it.url.audio == failed.url.audio }
            .maxByOrNull { it.url.rank ?: 0 } ?: return
        next.status = Status.PENDING
        env.execute { runProbe(next.msg.tabId ?: "-1", next.url.assetKey, next) }
    }

    /** The capture to publish for [asset] now, or null if it has no pair yet or the pair is unchanged. */
    private fun nextOffer(asset: Asset): Offer? {
        val done = asset.candidates.values.filter { it.status == Status.DONE }
        val video = done.filter { it.probe!!.info.isVideo }.maxWithOrNull(VIDEO_ORDER) ?: return null
        val audio = done.filter { it.probe!!.info.isAudio }.maxByOrNull { it.probe!!.bitrate } ?: return null
        val pair = video.url.trackKey to audio.url.trackKey
        if (pair == asset.offered) return null
        asset.offered = pair
        return Offer(asset.id, Track(video.url.url, video.probe!!), Track(audio.url.url, audio.probe!!), asset.lastMsg)
    }

    companion object {
        private const val MAX_TABS = 64
        private const val MAX_ASSETS_PER_TAB = 100

        /** Players that open H.264 outnumber those that open HEVC, VP9 and AV1, in that order. */
        fun codecRank(codec: String?): Int = when (codec) {
            "avc1", "avc3" -> 4
            "hvc1", "hev1" -> 3
            "vp09" -> 2
            "av01" -> 1
            else -> 0
        }

        /** Taller first; at the same height the more widely playable codec, then the higher bitrate. */
        private val VIDEO_ORDER: Comparator<Candidate> = compareBy<Candidate>(
            { it.probe!!.info.height },
            { codecRank(it.probe!!.info.codec) },
            { it.probe!!.bitrate },
        )
    }
}
