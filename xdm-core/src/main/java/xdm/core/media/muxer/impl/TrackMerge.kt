// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.impl

import xdm.core.media.muxer.transmux.mp4.SampleEntries
import xdm.core.media.muxer.transmux.sample.Track
import xdm.core.util.Logger
import java.util.IdentityHashMap

/**
 * Joins the tracks of several demuxers that read one segment list in turns (a TS ad inside an fMP4
 * stream, or the reverse) into one track per stream. Tracks are matched by kind and order: the n-th
 * video (audio) track with samples of each demuxer goes into the n-th video (audio) output track.
 * Samples are taken in the order they were read ([Run]s, segment by segment), rescaled to the output
 * track's timescale; a config other than the first output track's becomes another sample entry.
 */
internal object TrackMerge {

    /** Samples [from] until [to] of [track], read as one stretch of segments. */
    class Run(val track: Track, val from: Int, val to: Int)

    /** [groups] are each demuxer's tracks, in its own order; [runs] are in reading order. */
    fun merge(runs: List<Run>, groups: List<List<Track>>): List<Track> {
        val slotOf = IdentityHashMap<Track, String>()
        for (group in groups) {
            var video = 0; var audio = 0
            for (t in group) {
                if (t.samples.isEmpty()) continue
                slotOf[t] = if (t.codec.isVideo) "v${video++}" else "a${audio++}"
            }
        }
        val out = LinkedHashMap<String, Track>()
        val sourceOf = IdentityHashMap<Track, Track>()
        val entryMaps = IdentityHashMap<Track, IntArray>()
        for (run in runs) {
            val slot = slotOf[run.track] ?: continue
            val target = out.getOrPut(slot) {
                Track(run.track.codec).also { it.copyConfigFrom(run.track); sourceOf[it] = run.track }
            }
            val map = entryMaps.getOrPut(run.track) { entryMap(target, sourceOf[target]!!, run.track) }
            val s = run.track.samples
            val from = run.track.timescale
            val to = target.timescale
            for (i in run.from until run.to) {
                val d = s.descriptionIndex(i)
                target.samples.add(
                    s.fileOffset(i), s.sampleSize(i), rescale(s.pts(i), from, to), rescale(s.dts(i), from, to),
                    s.isKeyframe(i), map.getOrElse(d - 1) { 1 }
                )
            }
        }
        return out.values.toList()
    }

    /** Output stsd index for each of [track]'s 1-based entries. */
    private fun entryMap(target: Track, source: Track, track: Track): IntArray {
        if (track === source) return IntArray(1 + track.additionalSampleEntries.size) { it + 1 }
        val entries = listOf(track.sampleEntryBox ?: SampleEntries.build(track)) + track.additionalSampleEntries
        // The output track becomes a list of explicit entries: its own config frozen as the first.
        if (target.sampleEntryBox == null && target.matroskaCodecId == null) target.sampleEntryBox = SampleEntries.build(target)
        if (target.sampleEntryBox == null || entries[0] == null) {
            Logger.info("XDM", "Mixed containers: ${track.codec} config can't be added as another sample entry, the first one is kept")
            return IntArray(entries.size) { 1 }
        }
        return IntArray(entries.size) { i -> entries[i]?.let { target.descriptionIndexFor(it) } ?: 1 }
    }

    private fun rescale(v: Long, from: Int, to: Int): Long =
        if (from == to || from <= 0) v else v * to / from
}
