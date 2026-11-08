// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.storage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * OS file operations that java.io cannot express: atomic rename that tells a cross-device move apart,
 * folder fsync, owner-only permissions, sparse files and a delete that never follows links.
 *
 * The desktop uses [NioFileOps]. Android installs its own (built on `android.system.Os`) through
 * [PlatformFiles.install], because Android 7 has no `java.nio.file`. Keep every `java.nio.file`
 * reference in xdm-core inside [NioFileOps], so nothing else loads it on Android.
 */
interface PlatformFileOps {
    /**
     * Atomic rename. If [dst] exists it is replaced in the same step when [replace], otherwise
     * [TargetExistsException] is thrown. Throws [CrossDeviceException] when [src] and [dst] are on
     * different file systems.
     */
    fun moveAtomic(src: File, dst: File, replace: Boolean)

    /**
     * Rename within one folder: atomic where the file system can, a plain rename otherwise. Same
     * [replace] / [TargetExistsException] rules as [moveAtomic].
     */
    fun rename(src: File, dst: File, replace: Boolean)

    /** Flushes [dir]'s entries to disk, so a rename into it survives a power loss. Throws on failure. */
    fun forceDirectory(dir: File)

    /**
     * Creates [file] empty and readable/writable by the owner only, replacing an existing one. A no-op
     * where the file system has no owner permissions; the caller then creates the file normally.
     */
    fun createOwnerOnly(file: File)

    /** Best effort `rwx------` for a folder, `rw-------` for a file; logs and ignores a failure. */
    fun restrictToOwner(file: File)

    /**
     * Creates [file] [length] bytes long without writing the bytes: sparse where the file system can, so
     * the gap reads as zeros and takes no space. Best effort (the caller checks the length); does nothing
     * if it exists.
     */
    fun createSparse(file: File, length: Long)

    /**
     * Whether files in [dir] cannot have holes: on FAT32 and exFAT, a write past a file's end (or growing it)
     * makes the file system write zeros over the whole gap first, inside that one call. [dir] need not exist
     * yet. False when unknown. Never throws.
     */
    fun fillsGaps(dir: File): Boolean = false

    /** Deletes [dir] and everything under it without following symbolic links. */
    fun deleteTree(dir: File): Result<Unit>

    /**
     * Whether [a] and [b] are the same existing file or folder, also when spelled differently (letter
     * case, a symbolic link). The default compares canonical paths; [NioFileOps] asks the file system.
     */
    fun isSameFile(a: File, b: File): Boolean = a.canonicalPath == b.canonicalPath

    /**
     * Opens the existing [file] for writes from many threads at once ([PositionalOutput]), first sizing it
     * to [length] if that is given. Growing must not write the gap (see [createSparse]). The default lets
     * the writes take turns on a [RandomAccessFile]; [NioFileOps] writes in parallel.
     */
    fun openPositional(file: File, length: Long?): PositionalOutput {
        val out = LocalOutput(RandomAccessFile(file, "rw"))
        try {
            if (length != null && out.length() != length) out.setLength(length)
        } catch (e: IOException) {
            out.close()
            throw e
        }
        return LockedPositionalOutput(out)
    }
}

/**
 * Whether a file system type name, as the OS reports it, is FAT32/exFAT or another FAT variant: one that fills
 * gaps ([PlatformFileOps.fillsGaps]). Every [PlatformFileOps] judges the name here, so the platforms agree.
 *
 * A keyword, not a list: kernels and vendors name their drivers differently (`vfat`, `exfat`; `FAT32`, `exFAT` on
 * Windows; `sdfat`, `texfat` and others on phones), and all contain "fat" except `msdos` (Linux, macOS). No other
 * file system is known to have "fat" in its name; a false match would only cost a needless fill. A FUSE mount
 * (`fuseblk`) hides what is underneath and is not matched.
 */
fun isGapFillingFileSystem(type: String): Boolean {
    val t = type.lowercase()
    return "fat" in t || t == "msdos" || t == "umsdos"
}

/** The process-wide [PlatformFileOps]. */
object PlatformFiles {
    @Volatile
    private var installed: PlatformFileOps? = null

    /** Android calls this first, before any other xdm-core call. The desktop never calls it. */
    fun install(ops: PlatformFileOps) {
        installed = ops
    }

    /**
     * The installed implementation, else [NioFileOps]. Naming [NioFileOps] only here means ART resolves
     * it only if this branch runs, which on Android it never does once [install] was called.
     */
    val ops: PlatformFileOps get() = installed ?: NioFileOps
}
