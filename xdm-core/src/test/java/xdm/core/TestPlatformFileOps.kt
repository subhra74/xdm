// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xdm.core.storage.NioFileOps
import xdm.core.storage.PlatformFiles
import xdm.core.storage.TargetExistsException
import xdm.core.util.FileUtils
import xdm.core.util.FormatHelper
import xdm.core.util.PlatformUtils
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The platform file layer that keeps `java.nio.file` out of xdm-core's other classes (Android 7 has
 * none), and the java.time-free replacements, checked against what they replaced.
 */
class TestPlatformFileOps {
    @TempDir
    lateinit var dir: File

    @Test
    fun desktopDefaultsToNio() {
        assertTrue(PlatformFiles.ops === NioFileOps, "desktop must use NioFileOps when nothing is installed")
    }

    @Test
    fun moveAtomic_noReplace_existingTarget_throwsTargetExists() {
        val src = File(dir, "a").apply { writeText("new") }
        val dst = File(dir, "b").apply { writeText("old") }

        assertThrows(TargetExistsException::class.java, { NioFileOps.moveAtomic(src, dst, replace = false) }, "no replace")
        assertEquals("new", src.readText(), "source untouched")
        assertEquals("old", dst.readText(), "target untouched")
    }

    @Test
    fun moveAtomic_replace_replacesTarget() {
        val src = File(dir, "a").apply { writeText("new") }
        val dst = File(dir, "b").apply { writeText("old") }

        NioFileOps.moveAtomic(src, dst, replace = true)

        assertFalse(src.exists(), "source moved")
        assertEquals("new", dst.readText(), "target replaced")
    }

    @Test
    fun deleteFolder_doesNotFollowSymlinkedFolder() {
        assumeTrue(!PlatformUtils.isWindows, "symlinks need extra rights on Windows")
        val outside = File(dir, "outside").apply { mkdirs() }
        val precious = File(outside, "keep.txt").apply { writeText("keep") }
        val temp = File(dir, "temp").apply { mkdirs() }
        File(temp, "seg.ts").writeText("x")
        Files.createSymbolicLink(File(temp, "link").toPath(), outside.toPath())

        assertTrue(FileUtils.deleteFolder(temp.path).isSuccess, "delete succeeds")

        assertFalse(temp.exists(), "temp folder removed")
        assertTrue(precious.isFile, "file behind the symlink must survive")
    }

    @Test
    fun formatDateShort_matchesJavaTimeOutput() {
        val shortFormat = DateTimeFormatter.ofPattern("MMM dd")
        val longFormat = DateTimeFormatter.ofPattern("dd MMM yyyy")
        fun javaTime(epoch: Long): String {
            val date = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).toLocalDate()
            return if (date.year == LocalDate.now().year) shortFormat.format(date) else longFormat.format(date)
        }
        val now = System.currentTimeMillis()
        for (epoch in listOf(now, now - 40L * 24 * 3600 * 1000, 981_158_400_000L, 0L)) {
            assertEquals(javaTime(epoch), FormatHelper.formatDateShort(epoch), "epoch $epoch")
        }
    }

    @Test
    fun toLongEta_joinsParts() {
        assertEquals("1h 1m", FormatHelper.toLongEta(3661), "hours and minutes")
        assertEquals("5s", FormatHelper.toLongEta(5), "seconds only")
    }
}
