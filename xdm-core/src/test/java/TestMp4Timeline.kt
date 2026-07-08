import org.junit.Assert.assertEquals
import org.junit.Test
import xdm.core.media.muxer.transmux.mp4.assignSampleDurations
import xdm.core.media.muxer.transmux.sample.Sample

/**
 * Unit coverage for [assignSampleDurations] — the MP4 writer's per-sample duration derivation and
 * the discontinuity timeline repair. The repair path is what honours HLS EXT-X-DISCONTINUITY: a
 * mid-stream timestamp reset corrupts exactly one inter-sample DTS delta (backward or huge), which
 * the repair replaces with the track's typical frame duration so the output stays monotonic.
 */
class TestMp4Timeline {

    /** dts-only samples (pts==dts, keyframe) at the given decode times. */
    private fun samples(vararg dts: Long): List<Sample> = dts.map { Sample(0, 10, it, it, true) }

    private fun durations(vararg dts: Long): List<Long> {
        val s = samples(*dts)
        assignSampleDurations(s, repairTimeline = false)
        return s.map { it.durationTicks }
    }

    private fun repairedDurations(vararg dts: Long): List<Long> {
        val s = samples(*dts)
        assignSampleDurations(s, repairTimeline = true)
        return s.map { it.durationTicks }
    }

    @Test
    fun continuousTimeline_durationsAreDeltas_lastReusesPrevious() {
        // 30fps-ish constant cadence.
        assertEquals(listOf(100L, 100L, 100L, 100L), durations(0, 100, 200, 300))
    }

    @Test
    fun backwardJump_withoutRepair_clampsToZero() {
        // Reset from 200 back to 50 => negative delta at index 2, clamped to 0 (a freeze/glitch).
        assertEquals(listOf(100L, 100L, 0L, 100L, 100L, 100L), durations(0, 100, 200, 50, 150, 250))
    }

    @Test
    fun backwardJump_withRepair_substitutesTypicalDuration() {
        // The corrupted boundary delta is replaced with the median frame duration (100).
        assertEquals(listOf(100L, 100L, 100L, 100L, 100L, 100L), repairedDurations(0, 100, 200, 50, 150, 250))
    }

    @Test
    fun hugeForwardJump_withoutRepair_isPreserved() {
        // Without a discontinuity signal, a large gap is left intact (could be a legitimate gap).
        assertEquals(listOf(100L, 100L, 99800L, 100L, 100L, 100L), durations(0, 100, 200, 100000, 100100, 100200))
    }

    @Test
    fun hugeForwardJump_withRepair_isCollapsedToTypical() {
        assertEquals(listOf(100L, 100L, 100L, 100L, 100L, 100L), repairedDurations(0, 100, 200, 100000, 100100, 100200))
    }

    @Test
    fun repair_leavesModestVariationUntouched() {
        // Mild VFR variation (< 8x typical) must not be repaired away.
        assertEquals(listOf(100L, 120L, 90L, 90L), repairedDurations(0, 100, 220, 310))
    }

    @Test
    fun singleSample_getsUnitDuration() {
        assertEquals(listOf(1L), durations(0))
        assertEquals(listOf(1L), repairedDurations(0))
    }

    @Test
    fun emptyList_noCrash() {
        val s = emptyList<Sample>()
        assignSampleDurations(s, repairTimeline = true)
        assertEquals(0, s.size)
    }
}
