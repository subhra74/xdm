package xdm.core.downloaders.web.streaming.manifest.dash

import org.w3c.dom.NodeList


fun calculatePeriodDurationsIfMissing(
    periods: NodeList, mediaPresentationDuration: Long
): List<Long> {
    val n = periods.length
    val startAttr = arrayOfNulls<String>(n)
    val durAttr = arrayOfNulls<String>(n)
    for (i in 0..<n) {
        startAttr[i] = getAttr(periods.item(i), "start")
        durAttr[i] = getAttr(periods.item(i), "duration")
    }

    // Resolve each period's start time (in ms). A start is either explicit (@start), zero for the
    // first period, or the previous period's start plus its (explicit) duration.
    val starts = LongArray(n)
    val hasStart = BooleanArray(n)
    for (i in 0..<n) {
        when {
            startAttr[i] != null -> {
                starts[i] = parseXsDuration(startAttr[i]!!); hasStart[i] = true
            }
            i == 0 -> {
                starts[i] = 0L; hasStart[i] = true
            }
            hasStart[i - 1] && durAttr[i - 1] != null -> {
                starts[i] = starts[i - 1] + parseXsDuration(durAttr[i - 1]!!); hasStart[i] = true
            }
            else -> hasStart[i] = false
        }
    }

    // Resolve each period's duration: explicit @duration, else the gap to the next period's start,
    // else (for the last period) the remainder of the presentation.
    val durations = ArrayList<Long>(n)
    for (i in 0..<n) {
        val duration = when {
            durAttr[i] != null -> parseXsDuration(durAttr[i]!!)
            i < n - 1 && hasStart[i] && hasStart[i + 1] -> starts[i + 1] - starts[i]
            i == n - 1 && hasStart[i] -> mediaPresentationDuration - starts[i]
            else -> throw ParseException("Cannot determine duration for period $i")
        }
        durations.add(duration)
    }
    return durations
}
