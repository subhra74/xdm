package xdm.integration

/**
 * Finds videos played as separate video and audio tracks among the links of a batch (see
 * docs/site-video-detection.md), so the batch dialog can offer each as one merged download.
 *
 * The bulk picker lists what the page fetched, so a Facebook/Instagram video shows up as dozens of
 * byte-range chunks of each track, and a bilibili one as its separate track files. Here chunks
 * collapse into whole files ([SplitTrackUrl.trackKey]) and files group by video ([SplitTrackUrl.assetKey]);
 * ads are dropped. Every other link is left alone.
 */
object BatchVideoGrouper {

    class Grouping(
        /** Indexes of the links that are not split tracks, in their original order. */
        val plain: List<Int>,
        /** The videos, in the order their first link appeared. */
        val videos: List<SplitVideo>,
    )

    fun group(urls: List<String>, cookieGroups: List<Int>): Grouping {
        val plain = ArrayList<Int>()
        val videos = LinkedHashMap<String, SplitVideo>()
        for ((i, url) in urls.withIndex()) {
            val track = MetaCdnRule.match(url) ?: BilibiliCdnRule.match(url)
            if (track == null) {
                plain.add(i)
                continue
            }
            if (track.ad) continue
            val video = videos.getOrPut(track.assetKey) { SplitVideo(track.assetKey, cookieGroups.getOrElse(i) { -1 }) }
            video.add(track)
        }
        return Grouping(plain, videos.values.toList())
    }
}

/** One track of a [SplitVideo]: a whole file, its probe once read. Guarded by its video. */
class SplitTrack(val url: SplitTrackUrl) {
    enum class State { IDLE, PROBING, DONE, FAILED }

    var state = State.IDLE
    var probe: SplitTrackDetector.Probe? = null

    /** What the file holds, once probed; until then what its URL says. */
    val audio: Boolean get() = probe?.info?.isAudio ?: url.audio
}

/**
 * A video among a batch's links, with every track of it found so far. Which tracks to probe and which
 * pair to download follow the same rules as [SplitTrackDetector]. Thread-safe: probes finish on other
 * threads while the dialog reads it.
 */
class SplitVideo(val assetKey: String, val cookieGroup: Int) {
    private val tracks = LinkedHashMap<String, SplitTrack>()

    @Synchronized
    fun add(url: SplitTrackUrl): Boolean {
        if (tracks.containsKey(url.trackKey)) return false
        tracks[url.trackKey] = SplitTrack(url)
        return true
    }

    /** Adds a track the detector already probed; true if it was new here or newly probed. */
    @Synchronized
    fun addKnown(url: SplitTrackUrl, probe: SplitTrackDetector.Probe?): Boolean {
        val track = tracks[url.trackKey]
        if (track == null) {
            tracks[url.trackKey] = SplitTrack(url).also { t -> probe?.let { t.probe = it; t.state = SplitTrack.State.DONE } }
            return true
        }
        if (probe != null && track.state != SplitTrack.State.DONE) {
            track.probe = probe
            track.state = SplitTrack.State.DONE
            return true
        }
        return false
    }

    @get:Synchronized
    val trackKeys: Set<String> get() = tracks.keys.toSet()

    /** Each track's whole-file URL, in the order they were found. */
    @Synchronized
    fun trackUrls(): List<String> = tracks.values.map { it.url.url }

    /**
     * The tracks to probe now, marked as probing: of each kind, the top-ranked one not tried yet when the
     * URLs rank them (and only if it beats what is already probed), or every unranked one.
     */
    @Synchronized
    fun nextProbes(): List<SplitTrack> {
        val start = ArrayList<SplitTrack>()
        for (audio in listOf(false, true)) {
            val kind = tracks.values.filter { it.url.audio == audio }
            val idle = kind.filter { it.state == SplitTrack.State.IDLE }
            if (idle.isEmpty()) continue
            val ranked = idle.filter { it.url.rank != null }
            if (ranked.isEmpty()) {
                start.addAll(idle)
                continue
            }
            if (kind.any { it.state == SplitTrack.State.PROBING }) continue
            val best = ranked.maxBy { it.url.rank!! }
            val done = kind.filter { it.state == SplitTrack.State.DONE }.mapNotNull { it.url.rank }.maxOrNull()
            if (done == null || best.url.rank!! > done) start.add(best)
        }
        start.forEach { it.state = SplitTrack.State.PROBING }
        return start
    }

    @Synchronized
    fun probed(track: SplitTrack, probe: SplitTrackDetector.Probe?) {
        track.probe = probe
        track.state = if (probe != null) SplitTrack.State.DONE else SplitTrack.State.FAILED
    }

    @get:Synchronized
    val probing: Boolean get() = tracks.values.any { it.state == SplitTrack.State.PROBING }

    /** The best video and the best audio probed so far; either may be null. */
    @Synchronized
    fun pick(): Pick {
        val done = tracks.values.filter { it.state == SplitTrack.State.DONE }
        val video = done.filter { it.probe!!.info.isVideo }.maxWithOrNull(
            compareBy<SplitTrack>(
                { it.probe!!.info.height },
                { SplitTrackDetector.codecRank(it.probe!!.info.codec) },
                { it.probe!!.bitrate },
            )
        )
        val audio = done.filter { it.probe!!.info.isAudio }.maxByOrNull { it.probe!!.bitrate }
        return Pick(
            video?.let { SplitTrackDetector.Track(it.url.url, it.probe!!) },
            audio?.let { SplitTrackDetector.Track(it.url.url, it.probe!!) },
        )
    }

    data class Pick(val video: SplitTrackDetector.Track?, val audio: SplitTrackDetector.Track?) {
        val complete: Boolean get() = video != null && audio != null
        val size: Long get() = (video?.probe?.totalSize ?: 0) + (audio?.probe?.totalSize ?: 0)
    }
}
