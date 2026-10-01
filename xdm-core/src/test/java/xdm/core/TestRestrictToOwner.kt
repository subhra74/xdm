package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xdm.core.util.FileUtils
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** The config folder and log files are made private to the user on POSIX systems. */
class TestRestrictToOwner {
    @TempDir
    lateinit var tmp: File

    private fun perms(f: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))

    @Test
    fun foldersAndFiles_becomeOwnerOnly() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX only")
        val dir = File(tmp, "cfg").apply { mkdirs() }
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        val file = File(dir, "a.log").apply { writeText("x") }
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-r--r--"))

        FileUtils.restrictToOwner(dir)
        FileUtils.restrictToOwner(file)

        assertEquals("rwx------", perms(dir), "folder still open to others")
        assertEquals("rw-------", perms(file), "file still open to others")
    }
}
