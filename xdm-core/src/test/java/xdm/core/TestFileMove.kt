package xdm.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-move").toFile()
        src = File(dir, "tmp/video.part").apply { parentFile.mkdirs(); writeBytes(content) }
        dst = File(dir, "out/video.mp4").apply { parentFile.mkdirs() }
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Behaves like a different volume: the source cannot be renamed into the destination. */
    private open class OtherVolume(val space: Long = Long.MAX_VALUE) : MoveOps {
        override fun atomicMove(src: Path, dst: Path, replaceExisting: Boolean) {
            if (!src.fileName.toString().endsWith(".part") || src.parent != dst.parent) {
                throw AtomicMoveNotSupportedException(src.toString(), dst.toString(), "different file system")
            }
            MoveOps.Default.atomicMove(src, dst, replaceExisting)
        }

        override fun copy(src: Path, dst: Path, progress: ((Long) -> Boolean)?) =
            MoveOps.Default.copy(src, dst, progress)

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
        assertEquals(emptyList<File>(), partFiles(), "no .part left behind")
    }

    @Test
    fun copyFailsPartway_leavesSourceAndNoPartialFile() {
        val failing = object : OtherVolume() {
            override fun copy(src: Path, dst: Path, progress: ((Long) -> Boolean)?) {
                dst.toFile().writeBytes(content.copyOf(1000)) // partial write, then the drive fails
                throw IOException("device disconnected")
            }
        }
        assertEquals(DownloadError.OutputWriteError, FileUtils.moveFile(src, dst, failing))
        assertTrue(src.exists(), "source must survive for a retry")
        assertFalse(dst.exists(), "no partial file at the final name")
        assertEquals(emptyList<File>(), partFiles(), "no .part left behind")
    }

    @Test
    fun crossVolumeCopy_reportsProgress() {
        val seen = mutableListOf<Long>()
        assertNull(FileUtils.moveFile(src, dst, OtherVolume(), id = 7) { copied ->
            seen.add(copied)
            true
        })
        assertTrue(seen.isNotEmpty(), "progress must be reported while copying")
        assertEquals(content.size.toLong(), seen.last(), "last report is the whole file")
        assertTrue(content.contentEquals(dst.readBytes()))
    }

    @Test
    fun cancelledCopy_keepsSourceAndPublishesNothing() {
        val error = FileUtils.moveFile(src, dst, OtherVolume(), id = 7) { false }
        assertEquals(DownloadError.Cancelled, error)
        assertTrue(src.exists(), "source must survive so the publish can be retried")
        assertTrue(content.contentEquals(src.readBytes()))
        assertFalse(dst.exists(), "nothing under the real name")
        assertEquals(emptyList<File>(), partFiles(), "no scratch file left behind")
    }

    @Test
    fun sameVolumeMove_isNotReportedAsProgress() {
        var called = false
        assertNull(FileUtils.moveFile(src, dst, id = 7) { called = true; true })
        assertFalse(called, "a rename moves no bytes, so there is nothing to report")
    }

    @Test
    fun replaceExisting_overwritesAtomically() {
        dst.writeText("stale")
        assertNull(FileUtils.moveFile(src, dst, id = 7, replaceExisting = true))
        assertTrue(content.contentEquals(dst.readBytes()))
        assertFalse(src.exists())
    }

    @Test
    fun withoutReplaceExisting_refusesToOverwrite() {
        dst.writeText("mine")
        assertEquals(DownloadError.OutputWriteError, FileUtils.moveFile(src, dst, id = 7))
        assertEquals("mine", dst.readText())
        assertTrue(src.exists())
    }

    @Test
    fun scratchFileIsNamedFromTheDownloadId() {
        val seen = mutableListOf<String>()
        val watching = object : OtherVolume() {
            override fun copy(src: Path, dst: Path, progress: ((Long) -> Boolean)?) {
                seen.add(dst.fileName.toString())
                MoveOps.Default.copy(src, dst, progress)
            }
        }
        assertNull(FileUtils.moveFile(src, dst, watching, id = 42))
        assertEquals(listOf("video.mp4.42.part"), seen)
    }

    @Test
    fun notEnoughSpace_reportsDiskSpaceErrorAndWritesNothing() {
        assertEquals(DownloadError.DiskSpaceError, FileUtils.moveFile(src, dst, OtherVolume(space = 10)))
        assertTrue(src.exists())
        assertFalse(dst.exists())
        assertEquals(emptyList<File>(), partFiles())
    }

    @Test
    fun sameVolume_flushesSourceBeforeRename() {
        val calls = mutableListOf<String>()
        val recording = object : MoveOps by MoveOps.Default {
            override fun force(file: Path) {
                calls.add("force ${file.fileName}")
                MoveOps.Default.force(file)
            }

            override fun atomicMove(src: Path, dst: Path, replaceExisting: Boolean) {
                calls.add("move ${src.fileName}")
                MoveOps.Default.atomicMove(src, dst, replaceExisting)
            }
        }
        assertNull(FileUtils.moveFile(src, dst, recording))
        assertEquals(listOf("force video.part", "move video.part"), calls, "flush must come before the rename")
        assertTrue(content.contentEquals(dst.readBytes()))
    }

    @Test
    fun flushFails_keepsSourceAndPublishesNothing() {
        val failing = object : MoveOps by MoveOps.Default {
            override fun force(file: Path) = throw IOException("device disconnected")
        }
        assertEquals(DownloadError.OutputWriteError, FileUtils.moveFile(src, dst, failing))
        assertTrue(src.exists(), "source must survive for a retry")
        assertTrue(content.contentEquals(src.readBytes()))
        assertFalse(dst.exists(), "nothing under the real name")
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
