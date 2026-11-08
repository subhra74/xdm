// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.downloaders.web.streaming.manifest.common

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Manifest URLs are parsed as browsers parse them, not by `java.net.URI`: that rejects characters
 * browsers and servers accept (`[`, `]`, spaces, `|` in a path) and would fail the whole manifest.
 * Throws for anything that is not an http(s) URL.
 */
fun parseManifestUrl(url: String): HttpUrl = url.toHttpUrl()

fun resolveUri(baseUrl: HttpUrl, url: String): HttpUrl {
    val link = url.replace("\"", "").replace("'", "")
    return baseUrl.resolve(link) ?: throw IllegalArgumentException("Not an http(s) URL: $link")
}
