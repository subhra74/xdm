// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core

import xdm.core.util.RetryPolicy
import xdm.core.util.RetryTimings

/**
 * [RetryPolicy]'s timings scaled down for tests: "stalled" after 2 s without data instead of 15 s, a busy server
 * given up after 8 s instead of 5 minutes, backoff 0.2–1 s instead of 1–10 s. The proportions stay those of the
 * real values, so the same rules decide.
 */
object FastRetries {
    val TIMINGS = RetryTimings(
        stallMs = 2_000,
        budgetMs = 8_000,
        maxWaitMs = 8_000,
        firstBackoffMs = 200,
        maxBackoffMs = 1_000,
        stepMs = 50,
    )

    fun install() {
        RetryPolicy.timings = TIMINGS
    }

    fun reset() {
        RetryPolicy.timings = RetryTimings()
    }
}
