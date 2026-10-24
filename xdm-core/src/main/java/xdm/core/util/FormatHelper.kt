// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import kotlin.math.ceil

object FormatHelper {
    // SimpleDateFormat is not thread-safe: only used under formatDateShort's lock.
    private val shortFormat = SimpleDateFormat("MMM dd")
    private val longFormat = SimpleDateFormat("dd MMM yyyy")
    private const val GB: Int = 1024 * 1024 * 1024
    private const val MB: Int = 1024 * 1024
    private const val KB: Int = 1024

    @Synchronized
    fun formatDateShort(epoch: Long): String {
        val year = Calendar.getInstance().apply { timeInMillis = epoch }.get(Calendar.YEAR)
        val format = if (year == Calendar.getInstance().get(Calendar.YEAR)) shortFormat else longFormat
        // Re-read the zone each call, as java.time's systemDefault() did.
        format.timeZone = TimeZone.getDefault()
        return format.format(Date(epoch))
    }

    fun hms(seconds: Int): String {
        var sec = seconds
        var hrs = 0
        var min = 0
        hrs = sec / 3600
        min = (sec % 3600) / 60
        sec = sec % 60
        val str = String.format("%02d:%02d:%02d", hrs, min, sec)
        return str
    }

    fun toLongEta(sec: Long): String {
        var sec = sec
        val hrs = sec / 3600
        val min = (sec % 3600) / 60
        sec = sec % 60
        val arr: MutableList<String> = ArrayList(4)
        if (hrs > 0) {
            arr.add(hrs.toString() + "h")
        }
        if (min > 0) {
            arr.add(min.toString() + "m")
        }
        if (hrs == 0L && min == 0L) {
            arr.add(sec.toString() + "s")
        }
        return arr.joinToString(" ")
    }

    fun getEtaAsSec(length: Double, rate: Float): Long {
        if (length == 0.0) return 0
        if (length < 1 || rate <= 0) return -1
        return ceil(length / rate).toLong()
    }

    fun formatSize(length: Double): String {
        if (length < 0) return "---"
        return if (length > GB) {
            String.format("%.1f GB", length.toFloat() / GB)
        } else if (length > MB) {
            String.format("%.1f MB", length.toFloat() / MB)
        } else if (length > KB) {
            String.format("%.1f KB", length.toFloat() / KB)
        } else {
            String.format("%d B", length.toInt())
        }
    }
}