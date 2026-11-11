package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.app.ui.screens.SegmentTableModel
import xdm.core.downloaders.web.SegmentProgress
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.http.ChunkStatus.Downloading
import xdm.core.downloaders.web.http.ChunkStatus.Failed
import xdm.core.downloaders.web.http.ChunkStatus.Finished
import xdm.core.downloaders.web.http.ChunkStatus.Ready
import xdm.core.util.FormatHelper.formatSize

/** The progress window's segment table: one row per connection slot, kept until a new segment takes it over. */
class SegmentTableModelTest {

    private fun seg(id: Long, status: ChunkStatus, bytes: Long = id * 10, live: Boolean = status == Downloading) =
        SegmentProgress(id = id, status = status, live = live, bytes = bytes)

    private fun SegmentTableModel.ids() = (0 until rowCount).map { getValueAt(it, 0) }

    @Test
    fun finishedSegments_keepTheirRow_untilANewOneTakesIt() {
        val m = SegmentTableModel()
        m.update((1L..8L).map { seg(it, Downloading) })
        assertEquals(8, m.rowCount, "a row per live segment")

        // 2, 5 and 7 finish; 9 starts and takes the first freed row.
        m.update((1L..9L).map { seg(it, if (it == 2L || it == 5L || it == 7L) Finished else Downloading) })
        assertEquals(8, m.rowCount, "never more rows than were live at once")
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8"), m.ids(), "rows numbered 1..n")
        assertEquals(
            listOf(Downloading, Downloading, Downloading, Downloading, Finished, Downloading, Finished, Downloading),
            m.statuses, "row 2 reused by the new segment, rows 5 and 7 finished",
        )
    }

    @Test
    fun downloadDone_allRowsStay_finished() {
        val m = SegmentTableModel()
        m.update((1L..4L).map { seg(it, Downloading) })
        m.update((1L..4L).map { seg(it, Finished, bytes = 100) })
        assertEquals(4, m.rowCount, "the last segments stay listed")
        assertEquals(List(4) { Finished }, m.statuses, "all finished")
        m.update(emptyList())
        assertEquals(4, m.rowCount, "an empty update (assembling, stopping) clears nothing")
        assertEquals(formatSize(100.0), m.getValueAt(0, 2), "bytes kept")
    }

    @Test
    fun rowCount_isThePeakOfLiveSegments() {
        val m = SegmentTableModel()
        m.update((1L..3L).map { seg(it, Downloading) })
        m.update((1L..3L).map { seg(it, if (it == 1L) Downloading else Finished) })
        m.update((1L..7L).map { seg(it, if (it == 2L || it == 3L) Finished else Downloading) })
        assertEquals(5, m.rowCount, "1 still live, 4 and 5 reuse rows 2 and 3, 6 and 7 add two rows")
    }

    @Test
    fun goneFailedAndConnecting_shownAsSuch() {
        val m = SegmentTableModel()
        m.update(listOf(seg(1, Downloading), seg(2, Downloading), seg(3, Ready, live = true)))
        assertEquals(listOf(Downloading, Downloading, Ready), m.statuses, "a part split off shows connecting")
        // 1 merged into its neighbour (gone from the list), 2 failed.
        m.update(listOf(seg(2, Failed), seg(3, Downloading)))
        assertEquals(listOf(Finished, Failed, Downloading), m.statuses, "gone counts as done, failed stays failed")
        assertEquals(3, m.rowCount, "rows kept")
    }
}
