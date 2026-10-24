package xdm.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.network.http.CredentialPolicy
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.impl.HttpClientImpl

/**
 * Browser-captured credentials stay with the origin they were captured for, on every hop: a link that
 * redirects elsewhere, or a later chunk that goes straight to the redirect target, must not hand the
 * target the user's cookie, `Authorization` or token headers.
 */
class TestCredentialScoping {
    private val origin = MockHttpServer()
    private val target = MockHttpServer()
    private val client = HttpClientImpl(2)
    private val file = target.register("/file", Endpoint(ByteArray(16)))

    private val captured: HeaderMap = mapOf(
        "Authorization" to listOf("Bearer secret"),
        "X-Api-Key" to listOf("k"),
        "Referer" to listOf("https://example.com/page"),
    )

    @AfterEach
    fun tearDown() {
        client.close()
        origin.stop()
        target.stop()
    }

    private fun fetch(url: String, credentialsFor: String? = null): Map<String, List<String>> {
        client.getResponse(url, captured, "sid=1", null, credentialsFor).getOrThrow().use { it.inputStream.readBytes() }
        return file.requests.last().headers
    }

    /** `localhost` and `127.0.0.1` are different hosts and sites, though both reach the test server. */
    private fun otherHost(url: String) = url.replace("127.0.0.1", "localhost")

    @Test
    fun crossSiteRedirect_dropsCookieAndCredentialHeaders() {
        origin.redirect("/link") { otherHost(target.url("/file")) }
        val seen = fetch(origin.url("/link"))
        assertNull(seen["cookie"], "cookie followed the redirect")
        assertNull(seen["authorization"], "Authorization followed the redirect")
        assertNull(seen["x-api-key"], "token header followed the redirect")
        assertEquals(listOf("https://example.com/page"), seen["referer"], "ordinary headers should still go")
    }

    @Test
    fun sameHostOtherPort_keepsCookieOnly() {
        origin.redirect("/link") { target.url("/file") }
        val seen = fetch(origin.url("/link"))
        assertEquals(listOf("sid=1"), seen["cookie"])
        assertNull(seen["authorization"])
        assertNull(seen["x-api-key"])
    }

    /** What HttpChunkRetriever does after the first chunk: go to the redirect target directly. */
    @Test
    fun directRequestToRedirectTarget_isScopedToTheLink() {
        val seen = fetch(otherHost(target.url("/file")), credentialsFor = origin.url("/link"))
        assertNull(seen["cookie"])
        assertNull(seen["authorization"])
    }

    @Test
    fun sameOrigin_keepsEverything() {
        val seen = fetch(target.url("/file"))
        assertEquals(listOf("sid=1"), seen["cookie"])
        assertEquals(listOf("Bearer secret"), seen["authorization"])
        assertEquals(listOf("k"), seen["x-api-key"])
    }

    @Test
    fun policy() {
        val www = "https://www.example.co.uk/a".toHttpUrl()
        assertTrue(CredentialPolicy.allowsCookie(www, "https://cdn.example.co.uk/s.ts".toHttpUrl()), "same site")
        assertFalse(CredentialPolicy.allowsCookie(www, "https://other.co.uk/s.ts".toHttpUrl()), "public suffix is not a site")
        assertFalse(CredentialPolicy.allowsCookie(www, "http://www.example.co.uk/a".toHttpUrl()), "https -> http")
        assertFalse(CredentialPolicy.allowsCredentialHeader(www, "https://cdn.example.co.uk/a".toHttpUrl()), "other host")
        assertTrue(CredentialPolicy.allowsCredentialHeader(www, "https://www.example.co.uk/b".toHttpUrl()))
        assertTrue(CredentialPolicy.isCredentialHeader("X-CSRF-Token"))
        assertFalse(CredentialPolicy.isCredentialHeader("Referer"))
        assertFalse(CredentialPolicy.isCredentialHeader("User-Agent"))
    }
}
