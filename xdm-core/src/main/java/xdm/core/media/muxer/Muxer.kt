package xdm.core.media.muxer


interface Muxer {
    fun mux(
        segments: List<String>, outputFile: String, progressCallback: (Int) -> Unit, tempDir: String,
        independentSegement: Boolean,
        isMp4: Boolean,
        // Manifest signalled a discontinuity (e.g. EXT-X-DISCONTINUITY): repair the output timeline
        // across mid-stream timestamp resets. No-op when false.
        discontinuous: Boolean = false,
    ): Boolean

    fun mux(
        file1: String, file2: String, outputFile: String, progressCallback: (Int) -> Unit, tempDir: String
    ): Boolean

    fun mux(
        audioSegments: List<String>,
        videoSegments: List<String>,
        outputFile: String,
        progressCallback: (Int) -> Unit,
        tempDir: String,
        independentSegement: Boolean,
        isMp4: Boolean,
        discontinuous: Boolean = false,
    ): Boolean

    fun stop()
}
