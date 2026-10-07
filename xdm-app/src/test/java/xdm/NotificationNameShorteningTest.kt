package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.app.utils.shortenLongNames

/** Long file names in notifications keep their first 50 and last 10 characters. */
class NotificationNameShorteningTest {
    @Test
    fun longNameKeepsHeadAndTail() {
        val name = "Some.Very.Long.Show.Name.S01E01.The.Episode.Title.Goes.Here.2160p.WEB-DL.DDP5.1.H.265-GROUP.mkv"
        val expected = name.take(50) + "…" + name.takeLast(10)
        assertEquals("$expected download finished", shortenLongNames("$name download finished"), "shortened name")
    }

    @Test
    fun namesUpToSixtyOneCharactersAreKept() {
        val name = "a".repeat(57) + ".mp4"
        assertEquals("$name download finished", shortenLongNames("$name download finished"), "61-character name unchanged")
    }
}
