// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.capture

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A `/download` message from the extension carries the link the browser was redirected from (`sourceUrl`) and
 * that host's cookies (`sourceCookie`): they reach the download's task info, so an expired link can be
 * refreshed from it. A message without a redirect leaves both empty.
 */
class SourceLinkMessageTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val fileUrl = "https://cdn.example/file.bin?sig=1"
    private val link = "https://site.example/get?id=7"

    @BeforeEach
    fun setup() {
        FakeCaptureHost.install()
    }

    /** The message as the extension posts it; [extra] is spliced in as more JSON fields. */
    private fun message(extra: String) = json.decodeFromString<ExtensionMessage>(
        """{"url":"$fileUrl","cookie":"cdn=1","filename":"file.bin","fileSize":1000,
           "requestHeaders":{"User-Agent":["Mozilla/5.0"]},
           "responseHeaders":{"Content-Length":["1000"]}$extra}"""
    )

    @Test
    fun redirectedDownload_keepsItsSourceLinkAndCookies() {
        val task = CaptureHandler.toDownload(message(""","sourceUrl":"$link","sourceCookie":"site=1""""))!!
        assertEquals(fileUrl, task.url, "the download itself uses the URL the browser got the file from")
        assertEquals("cdn=1", task.cookie, "the file host's cookies stay with the download")
        assertEquals(link, task.sourceUrl, "the link it was redirected from")
        assertEquals("site=1", task.sourceCookie, "that link's own cookies")
    }

    @Test
    fun noRedirect_hasNoSourceLink() {
        val task = CaptureHandler.toDownload(message(""))!!
        assertNull(task.sourceUrl, "an extension that sends none")
        assertNull(task.sourceCookie)
    }

    @Test
    fun sourceLinkThatIsTheFileItself_isDropped() {
        val same = CaptureHandler.toDownload(message(""","sourceUrl":"$fileUrl","sourceCookie":"site=1""""))!!
        assertNull(same.sourceUrl, "the same URL is no source link")
        assertNull(same.sourceCookie, "no cookies without a source link")
        val blank = CaptureHandler.toDownload(message(""","sourceUrl":" ","sourceCookie":"site=1""""))!!
        assertNull(blank.sourceUrl, "a blank source link")
        assertNull(blank.sourceCookie, "no cookies without a source link")
    }

    @Test
    fun cookiesWithoutASourceLink_areDropped() {
        val task = CaptureHandler.toDownload(message(""","sourceCookie":"site=1""""))!!
        assertNull(task.sourceCookie, "cookies for no link")
    }
}
