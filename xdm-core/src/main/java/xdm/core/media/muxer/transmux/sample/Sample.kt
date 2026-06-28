package xdm.core.media.muxer.transmux.sample

/**
 * One coded sample (a video access unit or an audio frame) staged on disk.
 *
 * To keep memory bounded for long videos, sample *bytes* are streamed straight into the output
 * file's mdat; only this lightweight record is retained in RAM. [fileOffset]/[size] point into
 * the mdat, and [pts]/[dts] are in the track timescale (90 kHz for video, sample-rate for audio).
 */
class Sample(
    val fileOffset: Long,
    val size: Int,
    val pts: Long,
    val dts: Long,
    val isKeyframe: Boolean
) {
    /** Duration in track timescale, filled in once the next sample's DTS is known. */
    var durationTicks: Long = 0
}
