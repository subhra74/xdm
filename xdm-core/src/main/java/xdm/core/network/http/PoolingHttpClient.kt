// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.network.http

typealias HeaderMap = Map<String, List<String>>

interface PoolingHttpClient {
    fun close()
    /** A null [range] sends no `Range` header at all, as a browser does for a plain fetch. */
    fun getResponse(url: String, headers: HeaderMap?, cookie: String?, range: Range?): Result<HttpResponse>
}
