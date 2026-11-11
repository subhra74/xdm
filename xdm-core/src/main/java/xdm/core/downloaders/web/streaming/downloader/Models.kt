// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.downloaders.web.streaming.downloader

import xdm.core.downloaders.DownloadError
import xdm.core.downloaders.DownloadHost
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.PoolingHttpClient
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** [StreamingChunk.tag] of a subtitle segment: saved beside the video, never muxed. */
const val SUBS_TAG = "SUBS"

data class StreamingChunk(
    val id: Long,
    val sequence: Long,
    var downloaded: AtomicLong = AtomicLong(0),
    var length: AtomicLong = AtomicLong(0),
    var status: AtomicReference<ChunkStatus>,
    var error: AtomicReference<DownloadError?>,
    var contentType: String? = null,
    var url: String,
    var byteRange: Pair<Long, Long>?,
    var keyUrl: String?,
    var iv: String? = null,
    val tag: String,
    val fileHandle: AtomicReference<RandomAccessFile?>,
    var encrypted: Boolean,
    /** Playback length in seconds from the playlist (`EXTINF`); 0 when unknown (DASH, older states). */
    var duration: Double = 0.0,
    /** Discontinuities before this segment in its playlist (`EXT-X-DISCONTINUITY`): its timeline section. */
    var discontinuity: Int = 0,
    /** An initialization segment (`EXT-X-MAP`) rather than media; false when unknown. */
    var init: Boolean = false,
)

interface StreamingTaskContext {
    val id: Long
    val chunks: List<StreamingChunk>
    val init: AtomicBoolean
    var totalSize: Long?
    var downloaded: AtomicLong
    var contentType: String?
    var headers: HeaderMap?
    var cookie: String?
    val httpClient: PoolingHttpClient
    val stopFlag: AtomicBoolean
    val completed: AtomicBoolean
    val diskError: AtomicBoolean
    val downloadHost: DownloadHost
    val tempFileName: String
    var tempFolder: String
    var hasSeparateStreams: Boolean
    val pieceCompletedCount: AtomicInteger
    val assembling: AtomicBoolean

    /**
     * Absolute path of the muxed output, recorded the first time it is chosen and never
     * recomputed. Commit, delete and retry-after-failed-commit all address this path, so a
     * destination changed after muxing cannot orphan the partial. Null until muxing starts.
     */
    var muxOutputPath: String?

    /**
     * The container the mux wrote (`mp4`, `mkv`...; see [xdm.core.media.muxer.MuxContainer]), kept so
     * a commit retried after a restart still names the file after it. Null until a mux succeeds.
     */
    var muxedContainer: String?
}

data class HlsTaskContext(
    override val id: Long,
    override val chunks: ArrayList<StreamingChunk>,
    override val init: AtomicBoolean = AtomicBoolean(false),
    override var totalSize: Long? = null,
    override var downloaded: AtomicLong = AtomicLong(0),
    override var contentType: String? = null,
    override var headers: HeaderMap? = null,
    override var cookie: String? = null,
    override val httpClient: PoolingHttpClient,
    override val stopFlag: AtomicBoolean = AtomicBoolean(false),
    override val completed: AtomicBoolean = AtomicBoolean(false),
    override val diskError: AtomicBoolean = AtomicBoolean(false),
    override val downloadHost: DownloadHost,
    override val tempFileName: String,
    override var tempFolder: String,
    override var hasSeparateStreams: Boolean = false,
    override val pieceCompletedCount: AtomicInteger = AtomicInteger(0),
    override val assembling: AtomicBoolean = AtomicBoolean(false),
    override var muxOutputPath: String? = null,
    override var muxedContainer: String? = null,
    var url: String,
    var audioUrl: String?,
    var audioOnly: Boolean = false,
    var independent: Boolean,
    var encrypted: Boolean,
    // At least one segment (video or audio) is preceded by EXT-X-DISCONTINUITY. Enables timeline
    // repair in the muxer so the output stays monotonic across the timestamp reset.
    var discontinuous: Boolean = false,
) : StreamingTaskContext

data class DashTaskContext(
    override val id: Long,
    override val chunks: List<StreamingChunk>,
    override val init: AtomicBoolean = AtomicBoolean(false),
    override var totalSize: Long? = null,
    override var downloaded: AtomicLong = AtomicLong(0),
    override var contentType: String? = null,
    override var headers: HeaderMap? = null,
    override var cookie: String? = null,
    override val httpClient: PoolingHttpClient,
    override val stopFlag: AtomicBoolean = AtomicBoolean(false),
    override val completed: AtomicBoolean = AtomicBoolean(false),
    override val diskError: AtomicBoolean = AtomicBoolean(false),
    override val downloadHost: DownloadHost,
    override val tempFileName: String,
    override var tempFolder: String,
    override var hasSeparateStreams: Boolean = false,
    override val pieceCompletedCount: AtomicInteger = AtomicInteger(0),
    override val assembling: AtomicBoolean = AtomicBoolean(false),
    override var muxOutputPath: String? = null,
    override var muxedContainer: String? = null,
    var url: String,
    val audioMime: String,
    val videoMime: String,
) : StreamingTaskContext