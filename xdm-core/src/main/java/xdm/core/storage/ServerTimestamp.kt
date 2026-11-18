// Copyright 2026 XDM contributors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
package xdm.core.storage

import xdm.core.util.Logger

fun StorageFolder.preserveServerTimestamp(name: String, millis: Long?) {
    if (millis == null) return
    try {
        if (!setLastModified(name, millis)) {
            Logger.info("XDM", "Storage does not support server timestamps")
        }
    } catch (e: Exception) {
        // Metadata failure must not trigger a re-download.
        Logger.error("XDM", "Could not preserve server timestamp", e)
    }
}
