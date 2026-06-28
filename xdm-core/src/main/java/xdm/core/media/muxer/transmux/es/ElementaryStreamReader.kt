package xdm.core.media.muxer.transmux.es

import xdm.core.media.muxer.transmux.sample.Track

/**
 * Consumes the reassembled payload of one PES packet for a single elementary stream and turns it
 * into one or more [xdm.core.media.muxer.transmux.sample.Sample]s appended to [track].
 *
 * Implementations never decode: they parse just enough structure (NAL boundaries, ADTS/AC-3
 * frame headers, parameter sets) to (a) split the stream into samples, (b) recover timestamps
 * and keyframe flags, and (c) build the codec decoder config. Sample bytes are written out
 * through [SampleSink] so they land directly in the output mdat.
 */
interface ElementaryStreamReader {
    val track: Track

    /**
     * @param data    backing buffer holding the PES payload
     * @param offset  start of the payload within [data]
     * @param length  payload length in bytes
     * @param pts     presentation timestamp in 90 kHz ticks (or -1 if absent)
     * @param dts     decode timestamp in 90 kHz ticks (falls back to pts if absent)
     */
    fun consume(data: ByteArray, offset: Int, length: Int, pts: Long, dts: Long)

    /** Flush any sample still buffered at end of stream. */
    fun finish()
}

/** Sink that stages sample bytes into the output mdat and returns the file offset they landed at. */
interface SampleSink {
    /** Appends [length] bytes from [data] at [offset] to the mdat, returning the start file offset. */
    fun writeSampleData(data: ByteArray, offset: Int, length: Int): Long
}
