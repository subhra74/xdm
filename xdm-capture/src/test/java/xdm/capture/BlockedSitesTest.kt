package xdm.capture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlockedSitesTest {
    @Test
    fun blocksTheDomainsAndTheirSubdomains() {
        assertTrue(BlockedSites.isBlockedUrl("https://www.youtube.com/watch?v=x"), "www")
        assertTrue(BlockedSites.isBlockedUrl("https://m.youtube.com/"), "mobile site")
        assertTrue(BlockedSites.isBlockedUrl("https://youtu.be/x"), "short links")
        assertTrue(BlockedSites.isBlockedHost("I.YTIMG.COM."), "case and trailing dot")
    }

    @Test
    fun leavesOtherSitesAlone() {
        assertFalse(BlockedSites.isBlockedUrl("https://notyoutube.com/"), "suffix without a dot")
        assertFalse(BlockedSites.isBlockedUrl("https://youtube.com.example.net/"), "prefix")
        assertFalse(BlockedSites.isBlockedUrl("not a url"), "unparsable")
        assertFalse(BlockedSites.isBlockedUrl(null), "null")
    }
}
