package xdm.core.downloaders.web.streaming.manifest.dash

import java.util.regex.Matcher

class MatcherResult private constructor() {
    private var groups: List<String> = ArrayList()
    private var namedGroups: Map<String, String> = HashMap()

    fun group(index: Int): String {
        return groups[index]
    }

    fun group(name: String): String? {
        return namedGroups[name]
    }

    companion object {
        fun toMatcherResult(matcher: Matcher): MatcherResult {
            val groups: MutableList<String> = ArrayList()
            val namedGroups: MutableMap<String, String> = HashMap()
            for (i in 0..<matcher.groupCount()) {
                groups.add(matcher.group(i))
            }
            namedGroups["timedx"] = matcher.group("timedx")
            namedGroups["timedigits"] = matcher.group("timedigits")
            namedGroups["numdx"] = matcher.group("numdx")
            namedGroups["numdigits"] = matcher.group("numdigits")
            val matcherResult = MatcherResult()
            matcherResult.groups = groups
            matcherResult.namedGroups = namedGroups
            return matcherResult
        }
    }
}
