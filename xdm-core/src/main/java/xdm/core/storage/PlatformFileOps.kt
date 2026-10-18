package xdm.core.storage

import java.io.File

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

    /** Creates [file] as sparse where that means something (NTFS). Best effort; does nothing if it exists. */
    fun createSparse(file: File)

    /** Deletes [dir] and everything under it without following symbolic links. */
    fun deleteTree(dir: File): Result<Unit>
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
