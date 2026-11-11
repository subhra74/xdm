// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

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

    /**
     * What made the last mux fail, when it failed on an exception (not on a stop or on missing media), so
     * the caller can tell a full disk from a muxing problem. Null otherwise.
     */
    val lastError: Throwable? get() = null

    /**
     * The container (an extension without the dot, e.g. `mkv`) the last successful mux wrote, which
     * may differ from the output's name ([MuxContainer]): the published name takes it. Null when
     * unknown (no successful mux yet, or a muxer that doesn't say).
     */
    val outputContainer: String? get() = null
}
