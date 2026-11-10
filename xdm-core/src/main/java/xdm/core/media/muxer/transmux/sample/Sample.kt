// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.sample

/**
 * One coded sample (a video access unit or an audio frame): a view of row [index] of a [SampleTable].
 *
 * Sample *bytes* are streamed straight into the output file's mdat (or the MKV spool); the table keeps
 * only the record. [fileOffset]/[size] point into the mdat, and [pts]/[dts] are in the track
 * timescale (90 kHz for video, sample-rate for audio). Reads and writes go through to the table, so a
 * view stays valid while the table is open; hot loops use the table's indexed accessors instead.
 */
class Sample internal constructor(private val table: SampleTable, val index: Int) {
    val fileOffset: Long get() = table.fileOffset(index)
    val size: Int get() = table.sampleSize(index)

    var pts: Long
        get() = table.pts(index)
        set(v) = table.setPts(index, v)

    var dts: Long
        get() = table.dts(index)
        set(v) = table.setDts(index, v)

    val isKeyframe: Boolean get() = table.isKeyframe(index)

    /** Duration in track timescale; 0 until the MP4 writer derives durations (see [SampleTable.deriveDurations]). */
    val durationTicks: Long get() = table.duration(index)

    /** 1-based index into the track's sample entries (see [Track.additionalSampleEntries]). */
    val sampleDescriptionIndex: Int get() = table.descriptionIndex(index)
}
