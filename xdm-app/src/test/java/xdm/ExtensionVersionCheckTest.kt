package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.utils.Browser
import xdm.integration.ExtensionVersionCheck

class ExtensionVersionCheckTest {
    private val chromeUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    private val edgeUa = "$chromeUa Edg/141.0.0.0"
    private val firefoxUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0"

    @Test
    fun currentExtensionsAreNotReported() {
        assertNull(ExtensionVersionCheck.outdatedBrowser("chrome/3.5", "chrome-extension://abc", chromeUa), "chrome 3.5")
        assertNull(ExtensionVersionCheck.outdatedBrowser("chrome/3.10", null, chromeUa), "chrome 3.10")
        assertNull(ExtensionVersionCheck.outdatedBrowser("chrome/4.0", null, chromeUa), "chrome 4.0")
        assertNull(ExtensionVersionCheck.outdatedBrowser("firefox/1.1", "moz-extension://x", firefoxUa), "firefox 1.1")
        assertNull(ExtensionVersionCheck.outdatedBrowser("firefox/1.1.2", null, firefoxUa), "firefox 1.1.2")
    }

    @Test
    fun olderVersionsInTheHeaderAreReported() {
        assertEquals(Browser.CHROME, ExtensionVersionCheck.outdatedBrowser("chrome/3.4", null, chromeUa), "chrome 3.4")
        assertEquals(Browser.EDGE, ExtensionVersionCheck.outdatedBrowser("chrome/3.4", null, edgeUa), "edge 3.4")
        assertEquals(Browser.FIREFOX, ExtensionVersionCheck.outdatedBrowser("firefox/1.0", null, firefoxUa), "firefox 1.0")
        assertEquals(Browser.CHROME, ExtensionVersionCheck.outdatedBrowser("chrome/", null, chromeUa), "no version")
    }

    @Test
    fun extensionsWithoutTheHeaderAreOld() {
        assertEquals(Browser.CHROME, ExtensionVersionCheck.outdatedBrowser(null, "chrome-extension://abc", chromeUa), "chrome")
        assertEquals(Browser.EDGE, ExtensionVersionCheck.outdatedBrowser(null, "chrome-extension://abc", edgeUa), "edge")
        assertEquals(Browser.FIREFOX, ExtensionVersionCheck.outdatedBrowser(null, "moz-extension://uuid", firefoxUa), "firefox")
    }

    @Test
    fun nonExtensionRequestsAreIgnored() {
        assertNull(ExtensionVersionCheck.outdatedBrowser(null, null, "curl/8.5.0"), "local tool")
        assertNull(ExtensionVersionCheck.outdatedBrowser(null, null, chromeUa), "no origin")
        assertNull(ExtensionVersionCheck.outdatedBrowser(null, "https://example.com", chromeUa), "web page")
        assertNull(ExtensionVersionCheck.outdatedBrowser("safari/1.0", null, chromeUa), "unknown family")
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(ExtensionVersionCheck.isOlder("3.4", "3.5"), "3.4 < 3.5")
        assertFalse(ExtensionVersionCheck.isOlder("3.10", "3.5"), "3.10 > 3.5")
        assertFalse(ExtensionVersionCheck.isOlder("3.5.0", "3.5"), "3.5.0 == 3.5")
        assertTrue(ExtensionVersionCheck.isOlder("garbage", "3.5"), "unparsable counts as older")
    }
}
