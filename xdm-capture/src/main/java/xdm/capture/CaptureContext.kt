// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.capture

import xdm.core.CoreConfig
import xdm.core.network.http.CertTrust
import xdm.core.network.http.HttpAuth

/** The settings media detection reads; each app keeps them in its own settings store. */
interface CaptureConfig : CoreConfig {
    /** Where HLS/DASH segments are written before muxing. */
    val tempFolder: String

    /** Plain HTTP videos smaller than this many KB are not offered (ad clips, previews). */
    val minVideoSize: Long

    /** Fetch a manifest at most once per tab within a short window (see [VideoHelper]). */
    val skipDuplicateManifests: Boolean

    /** Path extensions taken as media ([MediaFilter]). */
    val videoExtensions: List<String>

    /** Hosts never captured from ([MediaFilter]). */
    val blockedHosts: List<String>

    /** Offer stand-alone subtitle files the browser loads (WebVTT, SRT, TTML) in the captured list. */
    val detectSubtitles: Boolean
}

/**
 * What media detection needs from the app around it. The desktop implements it over its config,
 * category rules and the extension's event channel; Android over its own settings and the browser.
 * Every method may be called from any thread.
 */
interface CaptureHost {
    val config: CaptureConfig

    /** Answers 401 / 407 for the manifest client. */
    val httpAuth: HttpAuth

    /**
     * Certificate checks of the app's own for the manifest client, or null for the shared ones
     * (the platform's plus the user's certificate exceptions). Read on every manifest fetch.
     */
    fun insecureTrust(): CertTrust? = null

    /** The category id (null = None) and target folder for a capture named [fileName]. */
    fun destination(fileName: String?, url: String?, pageUrl: String?): Pair<String?, String>

    /** The captured video list changed: entries were added, renamed or removed. */
    fun videoListChanged()

    /** The user picked captured video [videoId] (the extension's popup, or the app's own list). */
    fun offerVideo(videoId: Long, fileName: String, size: Long, contentType: String?)
}

/**
 * Service locator for the capture module, the same pattern as the desktop's `AppContext`. Each app
 * sets [host] and [tracker] at startup, before the first media message arrives.
 */
object CaptureContext {
    lateinit var host: CaptureHost
    lateinit var tracker: ICapturedVideoTracker

    val isReady: Boolean
        get() = ::host.isInitialized && ::tracker.isInitialized
}
