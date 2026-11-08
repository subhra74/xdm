// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.capture

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * Sites XDM never captures from, whatever the user's own settings say: store policy does not allow
 * downloading from YouTube. The same list as the browser extension's `blocked-sites.js`; keep the two
 * in step. Not configurable.
 */
object BlockedSites {
    private val domains = listOf(
        "youtube.com",
        "youtu.be",
        "youtube-nocookie.com",
        "youtubekids.com",
        "ytimg.com", // thumbnails and player assets
    )

    /** True for one of the domains above or any subdomain of it ("m.youtube.com", "i.ytimg.com"). */
    fun isBlockedHost(hostname: String?): Boolean {
        if (hostname.isNullOrEmpty()) return false
        val host = hostname.lowercase(Locale.ROOT).removeSuffix(".")
        return domains.any { host == it || host.endsWith(".$it") }
    }

    /** [isBlockedHost] for a URL; false for anything that does not parse. */
    fun isBlockedUrl(url: String?): Boolean = isBlockedHost(url?.toHttpUrlOrNull()?.host)
}
