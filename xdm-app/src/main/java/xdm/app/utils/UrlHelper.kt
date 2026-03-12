package xdm.app.utils

import java.net.URI

fun validateURL(url: String): Boolean {
    try {
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith(
                "https://",
                ignoreCase = true
            ) || url.startsWith("ftp://", ignoreCase = true)
        ) {
            URI.create(url)
            return true
        }
        return false
    } catch (e: Exception) {
        e.printStackTrace()
        return false
    }
}