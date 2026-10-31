package xdm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.utils.validateURL

class UrlHelperTest {
    @Test
    fun acceptsWhatBrowsersAccept() {
        assertTrue(validateURL("https://h.example/a/Show.S01E06.[Group.Com].mkv?download_token=d754"), "brackets in the path")
        assertTrue(validateURL("http://h.example/a b|c.zip"), "space and pipe in the path")
        assertTrue(validateURL("HTTPS://h.example/x"), "scheme case ignored")
    }

    @Test
    fun rejectsNonHttp() {
        assertFalse(validateURL("ftp://h.example/a.zip"), "ftp is not supported")
        assertFalse(validateURL("h.example/a.zip"), "no scheme")
        assertFalse(validateURL("https://"), "no host")
        assertFalse(validateURL(""), "empty")
    }
}
