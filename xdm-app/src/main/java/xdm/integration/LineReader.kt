package xdm.integration

import java.io.IOException
import java.io.InputStream

object LineReader {
    fun readLine(source: InputStream): String {
        val buffer = StringBuilder()
        while (true) {
            val x: Int = source.read()
            if (x == -1) throw IOException("Unexpected EOF while reading header line")
            if (x == '\n'.code) return buffer.toString()
            if (x != '\r'.code) buffer.append(x.toChar())
        }
    }
}
