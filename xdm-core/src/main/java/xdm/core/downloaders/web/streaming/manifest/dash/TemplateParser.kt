package xdm.core.downloaders.web.streaming.manifest.dash


fun parseTemplate(
    matches: List<MatcherResult>,
    templateUrlIn: String,
    number: Long,
    time: Long,
    bandwidth: String,
    representationId: String
): String {
    var templateUrl = templateUrlIn
    for (match in matches) {
        val variable = match.group(1)
        if (variable?.startsWith("Number") == true) {
            if (variable == "Number") {
                templateUrl = templateUrl.replace("$$variable$", number.toString())
            } else {
                val num = formatDigit(number, match, true)
                templateUrl = templateUrl.replace("$$variable$", num)
            }
        } else if (variable?.startsWith("Time") == true) {
            if (variable == "Time") {
                templateUrl = templateUrl.replace("$$variable$", time.toString())
            } else {
                val num = formatDigit(time, match, false)
                templateUrl = templateUrl.replace("$$variable$", num)
            }
        } else if (variable == "RepresentationID") {
            templateUrl = templateUrl.replace("$$variable$", representationId)
        } else if (variable == "Bandwidth") {
            templateUrl = templateUrl.replace("$$variable$", bandwidth)
        }
    }
    return templateUrl
}

private fun formatDigit(digit: Long, match: MatcherResult, number: Boolean): String {
    val digitWidth = match.group(if (number) "numdigits" else "timedigits")
    var dx: String? = "d"
    if ((number && match.group("numdx") != null) || (!number && match.group("timedx") != null)) {
        dx = if (number) match.group("numdx") else match.group("timedx")
    }
    var width = 0
    if (!digitWidth.isNullOrEmpty()) {
        width = digitWidth.toInt()
    }
    return if (width > 0) {
        val fmt = String.format("%s%s%s", "%0", width, dx)
        String.format(fmt, digit)
    } else {
        digit.toString()
    }
}
