package xdm.core.downloaders.web.streaming.manifest.hls

import xdm.core.util.StringUtils
import java.math.BigInteger
import java.util.*
import java.util.regex.Pattern
import kotlin.math.max

object CodecMap {
    private val videoCodecs = setOf("avc", "dvh", "hev", "hvc", "av0", "dav")
    private val audioCodecs = setOf("ac", "ec", "mp")
    private fun checkCodec(codecName: String?, codecs: Set<String>) =
        codecName?.let { codecs.any { c -> StringUtils.containsIgnoreCase(it, c) } } ?: false

    fun containsAudioCodec(codecName: String?) = checkCodec(codecName, audioCodecs)
    fun containsVideoCodec(codecName: String?) = checkCodec(codecName, videoCodecs)
}

fun HlsMasterPlaylist.getInfoString(): String? {
    val arr = ArrayList<String>()
    attributes["RESOLUTION"]?.let { arr.add(it) }
    attributes["BANDWIDTH"]?.let { arr.add("${it.toLong() / 1024} kbps") }
    attributes["NAME"]?.let { arr.add(it) }
    attributes["LANGUAGE"]?.let { arr.add(it) }
    return if (arr.isEmpty()) null else arr.joinToString(" ")
}

private val ATTRIBUTE_PATTERN = Pattern.compile("([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))")

fun parseAttributes(attributeString: String): Map<String, String> {
    val attributes = HashMap<String, String>()
    val matcher = ATTRIBUTE_PATTERN.matcher(attributeString)

    while (matcher.find()) {
        val key = matcher.group(1)
        val value = if (matcher.group(2) != null) matcher.group(2) else matcher.group(3)
        attributes[key] = value
    }

    return attributes
}

fun toBigEndian128BitHex(number: Long): String {
    val bigInt = BigInteger.valueOf(number)
    var byteArray = bigInt.toByteArray()

    if (byteArray.isNotEmpty() && byteArray[0].toInt() == 0 && byteArray.size > 16) {
        byteArray = Arrays.copyOfRange(byteArray, 1, byteArray.size)
    }

    val buffer = ByteArray(16)
    val srcPos = max(0.0, (byteArray.size - 16).toDouble()).toInt()
    val destPos = 16 - (byteArray.size - srcPos)
    val length = byteArray.size - srcPos
    System.arraycopy(byteArray, srcPos, buffer, destPos, length)

    // Convert to hexadecimal string
    val hexBuilder = StringBuilder()
    for (b in buffer) {
        hexBuilder.append(String.format("%02X", b))
    }

    return hexBuilder.toString()
}
