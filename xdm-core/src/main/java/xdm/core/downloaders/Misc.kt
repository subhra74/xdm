// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.downloaders

interface Proxy {
    data class Proxy(val host: String, val port: Int, val userName: String, val password: String?)
    class None
}


data class AuthInfo(val userName: String, val password: String?)