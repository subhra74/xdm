// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xdm.core.storage.LocalStorage
import xdm.core.storage.prepareForWriting
import java.io.File

/** The check downloads run on their target folder before fetching anything. */
class TestWritableFolder {
    @TempDir
    lateinit var dir: File

    @Test
    fun createsMissingFoldersAndLeavesNothingBehind() {
        val target = File(dir, "XDM/Video")
        assertTrue(LocalStorage.folder(target.path).prepareForWriting(), "a writable folder passes")
        assertTrue(target.isDirectory, "missing parents are created")
        assertEquals(emptyList<String>(), target.list()!!.toList(), "the probe file is deleted")
    }

    @Test
    fun refusesAFolderThatExistsButTakesNoWrites() {
        val target = File(dir, "readonly").apply { mkdirs() }
        assumeTrue(target.setWritable(false, false), "can't make a read-only folder here")
        try {
            // Root ignores folder permissions; nothing to check then.
            assumeTrue(!canCreate(File(target, "x")), "running as root")
            assertFalse(LocalStorage.folder(target.path).prepareForWriting(), "an existing read-only folder is refused")
        } finally {
            target.setWritable(true, false)
        }
    }

    private fun canCreate(file: File): Boolean = try {
        file.createNewFile().also { if (it) file.delete() }
    } catch (e: java.io.IOException) {
        false
    }

    @Test
    fun refusesAFolderThatCannotBeCreated() {
        val parent = File(dir, "locked").apply { mkdirs() }
        assumeTrue(parent.setWritable(false, false), "can't make a read-only folder here")
        try {
            assumeTrue(!File(parent, "x").mkdir(), "running as root")
            assertFalse(LocalStorage.folder(File(parent, "XDM/Video").path).prepareForWriting(), "a folder that can't be created is refused")
        } finally {
            parent.setWritable(true, false)
        }
    }
}
