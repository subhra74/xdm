// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.media.preview

import xdm.core.media.Mp4InitProbe
import java.io.IOException

/**
 * Fetches the keyframes of an MP4 for a preview, one range read at a time - never more than one in
 * flight, and never the whole file:
 * - progressive: the head of the file, `moov` (one more read if it does not fit in the head or sits
 *   after `mdat`), then each keyframe in file order;
 * - fragmented: the head (`moov` and the `sidx` after it), then for each chosen fragment one read of
 *   its start - `moof` and the keyframe right after it, sized from the `sidx` - and one more only
 *   when the keyframe runs past that read.
 *
 * A [head] already read from offset 0 (a split track's capture probe keeps one) saves the head
 * read, so one frame of a fragmented file takes a single request.
 *
 * [read] returns the bytes at `[offset, offset + length)`, fewer at the end of the file and none
 * past it; it throws on anything else (an HTTP error, a server that ignores the range).
 */
class Mp4PreviewReader(
    private val headBytes: Int = HEAD_BYTES,
    head: ByteArray? = null,
    private val read: (offset: Long, length: Int) -> ByteArray,
) {

    class Frame(val timeUs: Long, val data: ByteArray)

    class Preview(
        val codec: String,
        val description: ByteArray?,
        val width: Int,
        val height: Int,
        val durationUs: Long,
        val frames: List<Frame>,
    )

    /** Why there is no preview, in words fit for a log line or a tooltip. */
    class UnavailableException(message: String) : IOException(message)

    /** The last bytes read while looking for a box, so the box after it, or a keyframe in it, costs no read. */
    private var buffer = head ?: ByteArray(0)
    private var bufferAt = 0L

    /**
     * Loads up to [count] keyframes. [stop] is asked before every read; once it says yes, the frames
     * read so far are returned (or, with none yet, [UnavailableException] is thrown).
     */
    fun load(count: Int, stop: () -> Boolean = { false }): Preview {
        val (moovAt, moov) = findBox("moov", 0, headBytes, MAX_MOOV_BYTES, stop)
        val track = Mp4KeyframeLocator.videoTrack(moov) ?: throw UnavailableException("no video track WebCodecs can decode")
        val frames = ArrayList<Frame>(count)
        val durationUs: Long
        if (!track.fragmented) {
            val plan = Mp4KeyframeLocator.plan(moov, track, count) ?: throw UnavailableException("unusable sample tables")
            durationUs = plan.durationUs
            for (keyframe in plan.keyframes) {
                if (stop()) break
                readFrame(keyframe)?.let { frames.add(it) }
            }
        } else {
            val (sidxAt, sidxBox) = findBox("sidx", moovAt + moov.size, headBytes, MAX_SIDX_BYTES, stop)
            val sidx = Mp4InitProbe.parseSidxBox(sidxBox, sidxAt) ?: throw UnavailableException("unusable sidx")
            durationUs = (sidx.durationSeconds * 1_000_000).toLong()
            for (fragment in Mp4KeyframeLocator.fragments(sidx, count)) {
                if (stop()) break
                val keyframe = try {
                    // The whole fragment when it is small, so moof and keyframe come in one read.
                    val length = minOf(fragment.size, MAX_FRAGMENT_READ_BYTES).toInt().coerceAtLeast(FRAGMENT_HEAD_BYTES)
                    val (moofAt, moof) = findBox("moof", fragment.offset, length, MAX_MOOF_BYTES, stop)
                    Mp4KeyframeLocator.firstSample(moof, moofAt, track, fragment.timeUs)
                } catch (e: UnavailableException) {
                    // This fragment is not what the sidx says; the others may still be.
                    if (stop()) break else null
                } ?: continue
                readFrame(keyframe)?.let { frames.add(it) }
            }
        }
        if (frames.isEmpty()) throw UnavailableException(if (stop()) "timed out" else "no keyframe could be read")
        return Preview(track.codec, track.description, track.width, track.height, durationUs, frames)
    }

    /** The keyframe's bytes; null when it is too large to fetch for a preview. */
    private fun readFrame(keyframe: Mp4KeyframeLocator.Keyframe): Frame? {
        if (keyframe.size > MAX_FRAME_BYTES) return null
        val data = bytesAt(keyframe.offset, keyframe.size)
        if (data.size != keyframe.size) throw UnavailableException("file ended inside a keyframe")
        return Frame(keyframe.timeUs, data)
    }

    /** `[offset, offset + length)` from the buffer when it holds all of it, else read. */
    private fun bytesAt(offset: Long, length: Int): ByteArray {
        val from = offset - bufferAt
        if (from >= 0 && from + length <= buffer.size) return buffer.copyOfRange(from.toInt(), (from + length).toInt())
        return read(offset, length)
    }

    /**
     * Finds the top-level [target] box at or after [from], hopping over boxes by their headers, and
     * returns its file offset and whole bytes. Each hop reads [readLength] bytes unless the buffer
     * already holds the next box header.
     */
    private fun findBox(target: String, from: Long, readLength: Int, maxSize: Long, stop: () -> Boolean): Pair<Long, ByteArray> {
        var at = from
        repeat(MAX_HOPS) {
            val inBuffer = at - bufferAt
            val bytes = if (inBuffer >= 0 && inBuffer + 8 <= buffer.size) {
                buffer.copyOfRange(inBuffer.toInt(), buffer.size)
            } else {
                if (stop()) throw UnavailableException("timed out")
                read(at, readLength).also { buffer = it; bufferAt = at }
            }
            if (bytes.size < 8) throw UnavailableException("no $target in the file")
            when (val walk = Mp4KeyframeLocator.walk(bytes, at, target)) {
                is Mp4KeyframeLocator.Walk.Found -> {
                    if (walk.size > maxSize) throw UnavailableException("$target too large (${walk.size} bytes)")
                    val size = walk.size.toInt()
                    val box = bytesAt(walk.offset, size)
                    if (box.size != size) throw UnavailableException("file ended inside $target")
                    return walk.offset to box
                }
                is Mp4KeyframeLocator.Walk.Next -> {
                    if (walk.offset <= at) throw UnavailableException("no $target in the file")
                    at = walk.offset
                }
                is Mp4KeyframeLocator.Walk.Unsupported -> throw UnavailableException(walk.reason)
            }
        }
        throw UnavailableException("no $target within $MAX_HOPS reads")
    }

    companion object {
        /** First read, and each hop to a later box: enough for a typical faststart `ftyp` + `moov`. */
        const val HEAD_BYTES = 64 * 1024

        /** A `moov` holds 4+ bytes per sample, so hours of video run to a few MB; beyond this, give up. */
        const val MAX_MOOV_BYTES = 8L * 1024 * 1024

        /** Least read at a chosen fragment: enough for its `moof` even when the `sidx` sizes are odd. */
        const val FRAGMENT_HEAD_BYTES = 16 * 1024

        /**
         * Most read at a chosen fragment. Its keyframe comes right after the `moof` and is a large
         * part of it (often 100-500 KB at 1080p), so this usually brings both in one request.
         */
        const val MAX_FRAGMENT_READ_BYTES = 1024L * 1024

        /** 12 bytes per fragment: even hours of 1 s fragments stay well under this. */
        const val MAX_SIDX_BYTES = 1L * 1024 * 1024

        /** A `moof` lists its samples, a few bytes each: tens of KB at most in practice. */
        const val MAX_MOOF_BYTES = 1L * 1024 * 1024

        /** A keyframe bigger than this (an 8K intra frame) is skipped rather than fetched. */
        const val MAX_FRAME_BYTES = 4 * 1024 * 1024

        /** Boxes before the one looked for are few: `ftyp`, maybe `free`/`uuid`, `mdat`, `styp`. */
        const val MAX_HOPS = 6
    }
}
