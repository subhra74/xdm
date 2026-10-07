package xdm.core.util

import java.util.*

object StringUtils {
    fun isNullOrEmpty(str: String?): Boolean {
        return str.isNullOrEmpty()
    }

    fun isNullOrEmptyOrBlank(str: String?): Boolean {
        return str.isNullOrBlank()
    }

    fun getBytes(sb: CharSequence): ByteArray {
        return sb.toString().toByteArray()
    }

    fun getBytes(s: String): ByteArray {
        return s.toByteArray()
    }

    fun containsIgnoreCase(text: String?, match: String?): Boolean {
        if (text.isNullOrBlank() || match.isNullOrBlank()) return false
        return text.contains(match, ignoreCase = true)
    }

    fun equalsIgnoreCase(str1: String?, str2: String?): Boolean {
        if (str1.isNullOrBlank() || str2.isNullOrBlank()) return false
        return str1.equals(str2, ignoreCase = true)
    }
}
