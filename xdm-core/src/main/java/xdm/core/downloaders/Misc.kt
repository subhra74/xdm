// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.downloaders

interface Proxy {
    data class Proxy(val host: String, val port: Int, val userName: String, val password: String?)
    class None
}


data class AuthInfo(val userName: String, val password: String?)