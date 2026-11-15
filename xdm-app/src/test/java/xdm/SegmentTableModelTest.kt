package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xdm.app.I8N.text
import xdm.app.ui.screens.SegmentTableModel
import xdm.core.downloaders.web.SlotProgress
import xdm.core.downloaders.web.http.ChunkStatus.Downloading
import xdm.core.downloaders.web.http.ChunkStatus.Failed
import xdm.core.downloaders.web.http.ChunkStatus.Finished
import xdm.core.downloaders.web.http.ChunkStatus.Ready
import xdm.core.util.FormatHelper.formatSize

/** The progress window's table: shows the connection slots it is given and keeps them on an update without any. */
class SegmentTableModelTest {

    @Test
    fun showsTheSlots_numberedWithStatusAndBytes() {
        val m = SegmentTableModel()
        m.update(listOf(SlotProgress(Downloading, 10), SlotProgress(Ready, 0), SlotProgress(Failed, 5), SlotProgress(Finished, 300)))
        assertEquals(4, m.rowCount, "a row per slot")
        assertEquals(listOf("1", "2", "3", "4"), (0 until 4).map { m.getValueAt(it, 0) }, "rows numbered 1..n")
        assertEquals(
            listOf(text("STAT_DOWNLOADING"), text("SEG_CONNECTING"), text("STAT_FAILED"), text("SEG_COMPLETED")),
            (0 until 4).map { m.getValueAt(it, 1) }, "status texts",
        )
        assertEquals(formatSize(300.0), m.getValueAt(3, 2), "bytes")
    }

    @Test
    fun emptyUpdate_keepsTheRows() {
        val m = SegmentTableModel()
        m.update(List(4) { SlotProgress(Finished, 100) })
        m.update(emptyList())
        assertEquals(4, m.rowCount, "an empty update (assembling, stopping) clears nothing")
        assertEquals(formatSize(100.0), m.getValueAt(0, 2), "bytes kept")
    }
}
