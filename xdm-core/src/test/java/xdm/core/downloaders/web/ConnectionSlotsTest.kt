package xdm.core.downloaders.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.http.ChunkStatus.Downloading
import xdm.core.downloaders.web.http.ChunkStatus.Failed
import xdm.core.downloaders.web.http.ChunkStatus.Finished
import xdm.core.downloaders.web.http.ChunkStatus.Ready

/** The progress window's connection slots: one per connection, kept until a new piece takes it over, bytes added up. */
class ConnectionSlotsTest {

    private fun seg(id: Long, status: ChunkStatus, bytes: Long = id * 10, live: Boolean = status == Downloading) =
        SegmentProgress(id = id, status = status, live = live, bytes = bytes)

    private fun ConnectionSlots.statuses() = snapshot().map { it.status }
    private fun ConnectionSlots.bytes() = snapshot().map { it.bytes }

    @Test
    fun finishedPieces_keepTheirSlot_untilANewOneTakesIt() {
        val s = ConnectionSlots()
        s.update((1L..8L).map { seg(it, Downloading) })
        assertEquals(8, s.snapshot().size, "a slot per live piece")

        // 2, 5 and 7 finish; 9 starts and takes the first freed slot.
        s.update((1L..9L).map { seg(it, if (it == 2L || it == 5L || it == 7L) Finished else Downloading) })
        assertEquals(8, s.snapshot().size, "never more slots than were live at once")
        assertEquals(
            listOf(Downloading, Downloading, Downloading, Downloading, Finished, Downloading, Finished, Downloading),
            s.statuses(), "slot 2 reused by the new piece, slots 5 and 7 finished",
        )
    }

    @Test
    fun slotCount_isThePeakOfLivePieces() {
        val s = ConnectionSlots()
        s.update((1L..3L).map { seg(it, Downloading) })
        s.update((1L..3L).map { seg(it, if (it == 1L) Downloading else Finished) })
        s.update((1L..7L).map { seg(it, if (it == 2L || it == 3L) Finished else Downloading) })
        assertEquals(5, s.snapshot().size, "1 still live, 4 and 5 reuse slots 2 and 3, 6 and 7 add two")
    }

    @Test
    fun goneFailedAndConnecting_shownAsSuch() {
        val s = ConnectionSlots()
        s.update(listOf(seg(1, Downloading), seg(2, Downloading), seg(3, Ready, live = true)))
        assertEquals(listOf(Downloading, Downloading, Ready), s.statuses(), "a part split off shows connecting")
        // 1 merged into its neighbour (gone from the list), 2 failed.
        s.update(listOf(seg(2, Failed), seg(3, Downloading)))
        assertEquals(listOf(Finished, Failed, Downloading), s.statuses(), "gone counts as done, failed stays failed")
        assertEquals(listOf(10L, 20L, 30L), s.bytes(), "a gone piece keeps its bytes in its slot")
    }

    @Test
    fun takenOverSlot_addsTheNewPiecesBytes_toTheOldOnes() {
        val s = ConnectionSlots()
        s.update(listOf(seg(1, Downloading, 100), seg(2, Downloading, 50)))
        s.update(listOf(seg(1, Finished, 300), seg(2, Downloading, 80)))
        assertEquals(listOf(300L, 80L), s.bytes(), "finished slot keeps its bytes until taken")

        s.update(listOf(seg(1, Finished, 300), seg(2, Downloading, 90), seg(3, Downloading, 40)))
        assertEquals(listOf(340L, 90L), s.bytes(), "piece 3 in slot 1: 300 before it, 40 of its own")

        s.update(listOf(seg(1, Finished, 300), seg(2, Downloading, 95), seg(3, Finished, 200), seg(4, Downloading, 5)))
        assertEquals(listOf(505L, 95L), s.bytes(), "a third piece in slot 1 adds to both earlier ones")
        assertEquals(listOf(Downloading, Downloading), s.statuses(), "the slot shows the piece in it now")
    }

    @Test
    fun retriedPiece_inAnotherSlot_isNotCountedTwice() {
        val s = ConnectionSlots()
        s.update(listOf(seg(1, Downloading, 100), seg(2, Downloading, 50)))
        // 1 fails with 100 bytes; 3 takes its slot, which keeps the 100.
        s.update(listOf(seg(1, Failed, 100), seg(2, Downloading, 50), seg(3, Downloading, 0)))
        // 2 finishes; 1 is retried and takes slot 2, going on from its 100 bytes.
        s.update(listOf(seg(1, Downloading, 130), seg(2, Finished, 70), seg(3, Downloading, 20)))
        assertEquals(listOf(120L, 100L), s.bytes(), "slot 1: 100 + 20; slot 2: 70 + the 30 new bytes of piece 1")
    }

    @Test
    fun retriedPiece_inItsOwnSlot_goesOn() {
        val s = ConnectionSlots()
        s.update(listOf(seg(1, Downloading, 100)))
        s.update(listOf(seg(1, Failed, 100)))
        s.update(listOf(seg(1, Downloading, 150)))
        assertEquals(listOf(150L), s.bytes(), "same piece, same slot: its own bytes")
        assertEquals(listOf(Downloading), s.statuses(), "downloading again")
    }

    @Test
    fun finish_showsEverySlotFinished_withFinalBytes() {
        val s = ConnectionSlots()
        s.update((1L..4L).map { seg(it, Downloading, 10) })
        // The last update before the end never showed these finished; finish takes the final state.
        s.finish((1L..4L).map { seg(it, if (it == 4L) Failed else Finished, 100) })
        assertEquals(listOf(Finished, Finished, Finished, Failed), s.statuses(), "all finished, a failed one stays failed")
        assertEquals(List(4) { 100L }, s.bytes(), "final bytes")
        s.finish(emptyList())
        assertEquals(4, s.snapshot().size, "slots stay when the pieces are gone")
        assertEquals(List(4) { 100L }, s.bytes(), "bytes kept")
    }
}
