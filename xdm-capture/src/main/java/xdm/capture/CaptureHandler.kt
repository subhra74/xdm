// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.capture

import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.media.preview.Mp4PreviewReader
import xdm.core.util.CoreUtils
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import xdm.core.util.getContentLength
import xdm.core.util.getHeader
import java.util.Locale

/**
 * What happens to a captured request, whichever way it arrived: over the desktop extension's local
 * HTTP server, or in-process from the Android browser's hooks. Thread-safe.
 */
object CaptureHandler {
    /** Keyframes a preview takes (one image tells captures apart), and how long it may take. */
    private const val PREVIEW_FRAMES = 1
    private const val PREVIEW_TIMEOUT_MS = 8_000L

    private val clearTabFilter = ClearTabFilter()
    private val blockedHeaders = setOf(
        "accept",
//        "authorization",
        "connection",
        "expect",
        "te",
        "upgrade",
        "range",
        "cookie",
        "transfer-encoding",
        "content-type",
        "content-length",
        "content-encoding",
        "accept-encoding"
    )

    /**
     * Whole header families that must not be replayed: conditionals (`If-None-Match`,
     * `If-Modified-Since`, ...) would turn the fetch into a 304 with no body, and `Proxy-*` belongs
     * to the browser's proxy hop, not ours.
     */
    private val blockedHeaderPrefixes = listOf("if-", "proxy-")

    /**
     * A media request: a manifest, a plain video, or one track of a split-track video.
     * [manifestBody] is the manifest text when the sender already has it (the Android page script
     * reads it from the player's own response); without it the manifest is fetched here.
     */
    fun onMedia(msg: ExtensionMessage, manifestBody: String? = null) {
        logMessage(msg)
        removeBlockedHeaders(msg)
        VideoHelper.processMediaMessage(msg, manifestBody)
    }

    /** The user picked a captured video in the browser. */
    fun onVideoPicked(msg: ExtensionMessage) {
        logMessage(msg)
        removeBlockedHeaders(msg)
        msg.vid?.let { CaptureContext.tracker.addVideoDownload(it) }
    }

    /**
     * A tab started loading a different document (reload, or navigation to another page), so
     * everything detected in it is stale.
     */
    fun onTabNavigated(msg: ExtensionMessage) {
        logMessage(msg)
        val tabId = msg.tabId ?: return
        if (!clearTabFilter.shouldClear(tabId, msg.tabUrl)) {
            Logger.info("Tab $tabId is still on the same page, keeping its videos")
            return
        }
        VideoHelper.onTabNavigated(tabId)
        CaptureContext.tracker.clearTab(tabId)
    }

    /**
     * The tab's title arrived (usually after the media request that was captured there), so the
     * videos detected on that page can be named after it.
     */
    fun onTabUpdated(msg: ExtensionMessage) {
        logMessage(msg)
        val tabUrl = msg.tabUrl ?: return
        val tabTitle = msg.tabTitle?.takeIf { it.isNotBlank() } ?: return
        CaptureContext.tracker.updateMediaTitle(tabUrl, tabTitle)
    }

    /**
     * Keyframes of captured video [videoId] for the video list's thumbnail: its MP4 file (a plain
     * capture or a split track's video) read one range at a time with the headers it was captured
     * with. Null when the capture has no single MP4 file to read (HLS, segmented DASH, gone).
     * Blocking; throws [java.io.IOException] (often [Mp4PreviewReader.UnavailableException]).
     */
    fun loadPreview(videoId: Long): Mp4PreviewReader.Preview? {
        val tracker = CaptureContext.tracker
        val http = tracker.getHttpVideo(videoId)
        val dash = if (http == null) tracker.getDashVideo(videoId) else null
        val url = http?.let { previewUrl(it) } ?: dash?.let { previewUrl(it) } ?: return null
        return VideoHelper.loadPreview(
            url, http?.headers ?: dash?.headers, http?.cookie ?: dash?.cookie, PREVIEW_FRAMES, PREVIEW_TIMEOUT_MS,
        )
    }

    /** A file download the browser handed over, as a task for the New download dialog. */
    fun toDownload(msg: ExtensionMessage): HttpDownloadTaskInfo? {
        logMessage(msg)
        removeBlockedHeaders(msg)
        msg.url ?: return null
        return toHttpSource(msg)
    }

    /**
     * Logs what a message is about. Never the message itself: its cookie and request header values
     * carry the user's credentials, and the log folder is not the place for them.
     */
    private fun logMessage(m: ExtensionMessage) {
        Logger.info(
            "INTEGRATION",
            "url=${m.url} tab=${m.tabId} mime=${m.mimeType} size=${m.fileSize} vid=${m.vid} " +
                    "cookie=${if (m.cookie.isNullOrEmpty()) "no" else "yes"} headers=${m.requestHeaders?.keys}"
        )
    }

    private fun removeBlockedHeaders(msg: ExtensionMessage) {
        val filteredHeaders = mutableMapOf<String, List<String>>()
        filteredHeaders.putAll(msg.requestHeaders?.filter {
            val name = it.key.lowercase(Locale.ENGLISH)
            !blockedHeaders.contains(name) && blockedHeaderPrefixes.none { prefix -> name.startsWith(prefix) }
        } ?: return)
        msg.requestHeaders.clear()
        msg.requestHeaders.putAll(filteredHeaders)
    }

    private fun toHttpSource(msg: ExtensionMessage): HttpDownloadTaskInfo {
        val respHeaders =
            msg.responseHeaders?.map { entry -> entry.key to entry.value.map { it.value } }?.associate { it }
        val fileName = FileUtils.sanitizeFileName(
            // Chrome's suggested name may include sub-directories (e.g. "sub/file.zip");
            // reduce it to the base name so separators aren't mangled into underscores.
            msg.filename?.let { FileUtils.getFileName(it) } ?: FileUtils.getFileName(msg.url)
        )!!
        val origin = msg.referer ?: msg.tabUrl
        // Used as is when the download starts without a dialog; the dialog resolves it again itself.
        val (categoryId, targetFolder) = CaptureContext.host.destination(fileName, msg.url, origin)
        // The link the browser was redirected from; the file's own URL is no source link.
        val sourceUrl = msg.sourceUrl?.trim()?.takeIf { it.isNotEmpty() && it != msg.url }
        return HttpDownloadTaskInfo(
            id = CoreUtils.uniqueId(),
            url = msg.url!!,
            fileName = fileName,
            respectFileName = false,
            cookie = msg.cookie,
            headers = msg.requestHeaders,
            origin = origin,
            categoryId = categoryId,
            targetFolder = targetFolder,
            maxPiece = CaptureContext.host.config.maxSegments,
            authInfo = null,
            knownFileSize = msg.fileSize ?: getContentLength(respHeaders),
            etag = getHeader("etag", respHeaders),
            sourceUrl = sourceUrl,
            // Only with the link they belong to.
            sourceCookie = msg.sourceCookie?.takeIf { sourceUrl != null && it.isNotBlank() },
        )
    }
}
