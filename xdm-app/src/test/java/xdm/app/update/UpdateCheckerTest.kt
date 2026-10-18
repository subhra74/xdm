package xdm.app.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Update check: semver-style ordering with pre-releases, and picking the newest from the releases list. */
class UpdateCheckerTest {

    private fun newer(a: String, b: String) {
        assertTrue(UpdateChecker.compareVersions(a, b) > 0, "$a should be newer than $b")
        assertTrue(UpdateChecker.compareVersions(b, a) < 0, "$b should be older than $a")
    }

    @Test
    fun releaseCoresCompareNumerically() {
        newer("9.0.10", "9.0.2")
        newer("v9.1", "9.0.9")
        assertEquals(0, UpdateChecker.compareVersions("9.0", "v9.0.0"), "missing parts count as 0")
    }

    @Test
    fun releaseBeatsItsPreRelease() {
        newer("9.1.0", "9.1.0-beta.1")
        newer("9.1.0", "9.1.0-rc.3")
        newer("9.1.0-beta.1", "9.0.2")
    }

    @Test
    fun preReleasesCompareByParts() {
        newer("9.1.0-beta.10", "9.1.0-beta.2")
        newer("9.1.0-beta", "9.1.0-alpha.5")
        newer("9.1.0-rc.1", "9.1.0-beta.9")
        newer("9.1.0-beta.1", "9.1.0-beta")
        assertEquals(0, UpdateChecker.compareVersions("9.1.0-beta.1+build5", "9.1.0-beta.1"), "build metadata ignored")
    }

    @Test
    fun newestReleaseIncludesPreReleases() {
        val body = """
            [
              {"tag_name": "9.0.2", "prerelease": false},
              {"tag_name": "9.1.0-beta.2", "prerelease": true},
              {"tag_name": "9.1.0-beta.1", "prerelease": true},
              {"tag_name": "9.2.0", "draft": true}
            ]
        """.trimIndent()
        val latest = UpdateChecker.newestRelease(body)
        assertEquals("9.1.0-beta.2", latest?.version, "newest non-draft release wins")
        assertTrue(latest!!.isPreRelease, "GitHub pre-release flag carried over")
    }

    @Test
    fun finalReleaseWinsOverItsBetas() {
        val body = """[{"tag_name": "9.1.0-beta.2", "prerelease": true}, {"tag_name": "v9.1.0"}]"""
        val latest = UpdateChecker.newestRelease(body)
        assertEquals("v9.1.0", latest?.version, "final release outranks its betas")
        assertFalse(latest!!.isPreRelease, "final release is not a pre-release")
    }

    @Test
    fun tagSuffixMarksPreReleaseAndSingleObjectWorks() {
        val latest = UpdateChecker.newestRelease("""{"tag_name": "9.1.0-rc.1", "prerelease": false}""")
        assertEquals("9.1.0-rc.1", latest?.version, "single /releases/latest object still parsed")
        assertTrue(latest!!.isPreRelease, "-rc suffix marks a pre-release even without the flag")
    }

    @Test
    fun emptyListHasNoRelease() {
        assertNull(UpdateChecker.newestRelease("[]"), "no releases, no update")
    }
}
