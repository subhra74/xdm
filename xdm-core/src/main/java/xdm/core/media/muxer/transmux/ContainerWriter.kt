package xdm.core.media.muxer.transmux

import xdm.core.media.muxer.transmux.es.SampleSink
import xdm.core.media.muxer.transmux.sample.Track

/**
 * A container-format writer that a demuxer streams sample bytes into (via [SampleSink]) and that
 * finalizes the output file from the accumulated [Track]s. Implemented by the MP4 writer
 * ([xdm.core.media.muxer.transmux.mp4.Mp4Writer]) and the Matroska/WebM writer
 * ([xdm.core.media.muxer.transmux.mkv.MkvWriter]).
 */
interface ContainerWriter : SampleSink {
    /** Writes the container structure and finalizes the file. Returns false if there is no media. */
    fun finish(tracks: List<Track>): Boolean

    /** Closes handles and deletes any partial output. */
    fun abort()
}
