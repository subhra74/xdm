// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.util

/**
 * True when [error], or anything in its cause chain, says the disk is full. The JDK carries the OS
 * text in the message: "No space left on device" (ENOSPC on Linux, macOS and Android, where a SAF
 * descriptor's ErrnoException reads "write failed: ENOSPC (No space left on device)") and "There is
 * not enough space on the disk" (ERROR_DISK_FULL / ERROR_HANDLE_DISK_FULL on Windows). Only these
 * count: a segment that can't be read or parsed is not a disk problem.
 */
fun isOutOfSpace(error: Throwable?): Boolean {
    var e = error
    var depth = 0
    while (e != null && depth++ < 16) {
        val msg = e.message
        if (msg != null && OUT_OF_SPACE.any { msg.contains(it, ignoreCase = true) }) return true
        if (e.cause === e) break
        e = e.cause
    }
    return false
}

private val OUT_OF_SPACE = listOf("ENOSPC", "No space left on device", "not enough space on the disk")
