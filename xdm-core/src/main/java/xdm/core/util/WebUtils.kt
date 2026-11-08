// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.util

fun getQuery(url: String): String? {
    val idx = url.indexOf('?')
    return if (idx != -1) {
        url.substring(idx)
    } else {
        null
    }
}

fun appendQuery(url: String, query: String): String {
    return if (!url.contains('?')) {
        url + query
    } else {
        url
    }
}