package xdm.core.media.muxer

import xdm.core.storage.LocalStorage
import xdm.core.storage.StorageFile

/**
 * Assembles downloaded segments (local paths in the temp folder) into [StorageFile] outputs, which may
 * live outside the local file system (SAF on Android). The `String` overloads take a local output path.
 */
interface Muxer {
    fun mux(
        segments: List<String>, output: StorageFile, progressCallback: (Int) -> Unit, tempDir: String,
        independentSegement: Boolean,
        isMp4: Boolean,
        // Manifest signalled a discontinuity (e.g. EXT-X-DISCONTINUITY): repair the output timeline
        // across mid-stream timestamp resets. No-op when false.
        discontinuous: Boolean = false,
    ): Boolean

    fun mux(
        file1: String, file2: String, output: StorageFile, progressCallback: (Int) -> Unit, tempDir: String
    ): Boolean

    fun mux(
        audioSegments: List<String>,
        videoSegments: List<String>,
        output: StorageFile,
        progressCallback: (Int) -> Unit,
        tempDir: String,
        independentSegement: Boolean,
        isMp4: Boolean,
        discontinuous: Boolean = false,
    ): Boolean

    fun mux(
        segments: List<String>, outputFile: String, progressCallback: (Int) -> Unit, tempDir: String,
        independentSegement: Boolean,
        isMp4: Boolean,
        discontinuous: Boolean = false,
    ): Boolean = mux(
        segments, LocalStorage.file(outputFile), progressCallback, tempDir, independentSegement, isMp4, discontinuous
    )

    fun mux(
        file1: String, file2: String, outputFile: String, progressCallback: (Int) -> Unit, tempDir: String
    ): Boolean = mux(file1, file2, LocalStorage.file(outputFile), progressCallback, tempDir)

    fun mux(
        audioSegments: List<String>,
        videoSegments: List<String>,
        outputFile: String,
        progressCallback: (Int) -> Unit,
        tempDir: String,
        independentSegement: Boolean,
        isMp4: Boolean,
        discontinuous: Boolean = false,
    ): Boolean = mux(
        audioSegments, videoSegments, LocalStorage.file(outputFile), progressCallback, tempDir,
        independentSegement, isMp4, discontinuous
    )

    fun stop()

    /**
     * Where the last successful mux put time zero, in seconds of the source timeline (the earliest
     * presented PTS). Subtitles timed against the source clock are shifted by it. Null when unknown.
     */
    val timelineStartSec: Double? get() = null
}
