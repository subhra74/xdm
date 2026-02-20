package xdm.core.downloaders.web.streaming.manifest.dash

import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.util.*


fun calculatePeriodDurationsIfMissing(
    periods: NodeList, mediaPresentationDuration: Long
): List<Long> {
    val stack: Deque<Node> = ArrayDeque()
    for (i in 0..<periods.length) {
        stack.push(periods.item(i))
    }
    val list: MutableList<Long> = ArrayList(periods.length)
    var last = mediaPresentationDuration
    val count = stack.size
    for (i in 0..<count) {
        val node = stack.pop()
        val duration1 = getAttr(node, "duration")
        var start1 = getAttr(node, "start")
        if (start1 == null && duration1 == null) {
            throw ParseException("Both period start and duration is missing")
        }
        if (duration1 != null) {
            val duration = parseXsDuration(duration1)
            list.add(duration)
            last = mediaPresentationDuration - duration
            continue
        }
        if (start1 == null && i == stack.size - 1) {
            start1 = "PT0S"
        }
        val start = parseXsDuration(start1!!)
        list.add(last - start)
        last = start
    }
    list.reverse()
    return list
}
