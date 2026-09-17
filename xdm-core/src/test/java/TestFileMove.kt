import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xdm.core.downloaders.DownloadError
import xdm.core.util.FileUtils
import xdm.core.util.MoveOps
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression for CODE_REVIEW B7: [FileUtils.moveFile] must work across file systems, never overwrite
 * an existing file, never leave a partial destination, and report why it failed.
 *
 * A different volume is simulated with [MoveOps] whose atomic move of the source throws
 * [AtomicMoveNotSupportedException], exactly what the JDK does across file systems.
 */
class TestFileMove {
    private lateinit var dir: File
    private lateinit var src: File
    private lateinit var dst: File
    private val content = ByteArray(200_000) { (it % 251).toByte() }

    @Before
    fun setup() {
        dir = Files.createTempDirectory("xdm-move").toFile()
        src = File(dir, "tmp/video.part").apply { parentFile.mkdirs(); writeBytes(content) }
        dst = File(dir, "out/video.mp4").apply { parentFile.mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Behaves like a different volume: the source cannot be renamed into the destination. */
    private open class OtherVolume(val space: Long = Long.MAX_VALUE) : MoveOps {
        override fun atomicMove(src: Path, dst: Path) {
            if (!src.fileName.toString().endsWith(".part") || src.parent != dst.parent) {
                throw AtomicMoveNotSupportedException(src.toString(), dst.toString(), "different file system")
            }
            MoveOps.Default.atomicMove(src, dst)
        }

        override fun copy(src: Path, dst: Path) = MoveOps.Default.copy(src, dst)
        override fun usableSpace(folder: File) = space
    }

    private fun partFiles() = dst.parentFile.listFiles { f -> f.name.endsWith(".part") }!!.toList()

    @Test
    fun sameVolume_movesFile() {
        assertNull(FileUtils.moveFile(src, dst))
        assertFalse(src.exists())
        assertTrue(content.contentEquals(dst.readBytes()))
    }

    @Test
    fun otherVolume_copiesThenDeletesSource() {
        assertNull(FileUtils.moveFile(src, dst, OtherVolume()))
        assertFalse(src.exists())
        assertTrue(content.contentEquals(dst.readBytes()))
        assertEquals("no .part left behind", emptyList<File>(), partFiles())
    }

    @Test
    fun copyFailsPartway_leavesSourceAndNoPartialFile() {
        val failing = object : OtherVolume() {
            override fun copy(src: Path, dst: Path) {
                dst.toFile().writeBytes(content.copyOf(1000)) // partial write, then the drive fails
                throw IOException("device disconnected")
            }
        }
        assertEquals(DownloadError.OutputWriteError, FileUtils.moveFile(src, dst, failing))
        assertTrue("source must survive for a retry", src.exists())
        assertFalse("no partial file at the final name", dst.exists())
        assertEquals("no .part left behind", emptyList<File>(), partFiles())
    }

    @Test
    fun notEnoughSpace_reportsDiskSpaceErrorAndWritesNothing() {
        assertEquals(DownloadError.DiskSpaceError, FileUtils.moveFile(src, dst, OtherVolume(space = 10)))
        assertTrue(src.exists())
        assertFalse(dst.exists())
        assertEquals(emptyList<File>(), partFiles())
    }

    @Test
    fun destinationExists_isNeverOverwritten() {
        dst.writeText("user's file")
        assertEquals(DownloadError.OutputWriteError, FileUtils.moveFile(src, dst))
        assertEquals(DownloadError.OutputWriteError, FileUtils.moveFile(src, dst, OtherVolume()))
        assertEquals("user's file", dst.readText())
        assertTrue(src.exists())
    }
}
