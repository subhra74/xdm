// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.storage

import xdm.core.util.Logger

/**
 * Makes sure downloads can be written here before any data is fetched: creates the folder (and its
 * parents), then creates and deletes a small probe file. An existing folder can still refuse writes
 * (no storage permission, a read-only or removed volume, a revoked grant), so creating it is not
 * enough. False when either step is refused; never throws.
 */
fun StorageFolder.prepareForWriting(): Boolean {
    if (!create()) {
        Logger.error("XDM", "Unable to create folder $this")
        return false
    }
    val probe = ".xdm-write-check-" + java.lang.Long.toHexString(System.nanoTime())
    return try {
        open(probe, create = true).close()
        true
    } catch (e: Exception) {
        Logger.error("XDM", "Unable to write in folder $this", e)
        false
    } finally {
        try {
            delete(probe)
        } catch (_: Exception) {
            // Nothing was created, or it goes with the folder.
        }
    }
}
