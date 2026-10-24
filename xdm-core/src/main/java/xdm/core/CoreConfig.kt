// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core

import java.net.Proxy

interface CoreConfig {
    var speedLimit: Int
    var speedLimiterEnabled: Boolean
    var maxSegments: Int
    var maxRetries: Int
    var useProxy: Boolean
    var socksProxy: Boolean
    var proxyHost: String
    var proxyPort: Int
    var proxyUser: String
    var proxyPass: String

    /** Seconds a connection may go without receiving data before the read times out and is retried. */
    val readTimeoutSeconds: Int get() = DEFAULT_READ_TIMEOUT_SECONDS

    fun toProxy(): Proxy?

    companion object {
        const val DEFAULT_READ_TIMEOUT_SECONDS = 60

        /** Upper bound for [maxSegments] and a download's own segment count; the HTTP client pool is sized to it. */
        const val MAX_SEGMENTS = 256
    }
}