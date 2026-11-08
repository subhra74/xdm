// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.util

/** Runs a garbage collection when xdm-core wants the heap to shrink (after an HTTP client closes). */
fun interface GcHook {
    fun collect()
}

/**
 * xdm-core's explicit garbage collections go through here, so the host decides whether they run.
 * The desktop's JVM returns memory only after a GC, so by default this is [System.gc]; Android's
 * runtime sizes its heap itself and installs a no-op, as does the desktop's `--no-gc`.
 */
object Gc {
    @Volatile
    private var installed: GcHook? = null

    /** Called by the host at startup, before any download runs. */
    fun install(hook: GcHook) {
        installed = hook
    }

    /** The installed hook, else [System.gc]. */
    fun collect() {
        val hook = installed
        if (hook != null) hook.collect() else System.gc()
    }
}
