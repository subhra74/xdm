package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.ui.screens.downloadErrorHelpUrl
import xdm.core.downloaders.DownloadError

/** The "How to fix this" link on a failed download points at one website page per error. */
class DownloadErrorHelpUrlTest {
    @Test
    fun everyFailureHasItsOwnPage() {
        val failures = DownloadError.entries.filter { it != DownloadError.Cancelled }
        val urls = failures.map { downloadErrorHelpUrl(it) }
        urls.forEachIndexed { i, url ->
            assertTrue(
                url != null && url.matches(Regex("https://xtremedownloadmanager\\.com/help/errors/[a-z-]+/")),
                "unexpected help URL for ${failures[i]}: $url"
            )
        }
        assertEquals(urls.size, urls.toSet().size, "two errors share a help page")
    }

    @Test
    fun cancelledHasNoLink() {
        assertNull(downloadErrorHelpUrl(DownloadError.Cancelled), "a user cancel needs no fix")
    }

    @Test
    fun linkExpiredSlug() {
        assertEquals(
            "https://xtremedownloadmanager.com/help/errors/link-expired/",
            downloadErrorHelpUrl(DownloadError.LinkExpired),
            "slug must match the website page"
        )
    }
}
