package xdm.app

import xdm.core.util.Logger
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.*

object I8N {
    private val properties = Properties()

    // each file must have name like de.deutsch.german.txt
    @Synchronized
    fun text(id: String): String {
        return properties.getProperty(id)
    }

    fun loadTexts(code: String): Boolean {
        Logger.info("Loading language $code")
        try {
            val inStream = I8N::class.java.getResourceAsStream("/lang/en.txt") ?: FileInputStream("lang/$code.txt")
            inStream.use {
                InputStreamReader(it, Charset.forName("utf-8")).use { r ->
                    properties.load(r)
                }
            }
        } catch (e: Exception) {
            Logger.error(e)
            return false
        }
        if ("en" == code) {
            return true
        }
        try {
            val inStream = I8N::class.java.getResourceAsStream("/lang/$code.txt") ?: FileInputStream("lang/$code.txt")
            InputStreamReader(inStream, Charset.forName("utf-8")).use { r ->
                properties.load(r)
            }
            return true
        } catch (e: Exception) {
            Logger.error(e)
            return false
        }
    }
}
