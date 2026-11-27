// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.capture

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Firefox reports the `Host` it sent with each request (Chrome does not). It names the host of that one URL,
 * so it is dropped from a capture: kept, it would be sent to a variant playlist or segment on another CDN host,
 * which refuses it. The browser's other headers stay as they were.
 */
class HostHeaderDroppedTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setup() {
        FakeCaptureHost.install()
    }

    @Test
    fun firefoxCapture_losesHost_keepsTheRest() {
        val msg = json.decodeFromString<ExtensionMessage>(
            """{"url":"https://cdn.example/file.bin","filename":"file.bin","fileSize":1000,
               "requestHeaders":{"Host":["cdn.example"],"User-Agent":["Mozilla/5.0"],"Referer":["https://site.example/"]},
               "responseHeaders":{"Content-Length":["1000"]}}"""
        )
        val task = CaptureHandler.toDownload(msg)!!
        val names = task.headers.orEmpty().keys
        assertTrue(names.none { it.equals("host", ignoreCase = true) }, "Host is dropped: $names")
        assertEquals(setOf("User-Agent", "Referer"), names, "the browser's other headers are kept")
    }
}
