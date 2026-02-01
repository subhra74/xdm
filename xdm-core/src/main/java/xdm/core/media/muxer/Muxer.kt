package xdm.core.media.muxer


interface Muxer {
    fun mux(
        segments: List<String>, outputFile: String, progressCallback: (Int) -> Unit, tempDir: String
    ): Boolean

    fun mux(
        file1: String, file2: String, outputFile: String, progressCallback: (Int) -> Unit, tempDir: String
    ): Boolean

    fun mux(
        audioSegments: List<String>,
        videoSegments: List<String>,
        outputFile: String,
        progressCallback: (Int) -> Unit,
        tempDir: String
    ): Boolean

    fun stop()
}
