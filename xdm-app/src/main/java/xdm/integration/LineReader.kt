package xdm.integration

import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

object LineReader {
    /** What is left for a request head: bytes, and the [System.nanoTime] it must arrive by. */
    class Budget(var bytes: Int, val deadlineNanos: Long)

    /**
     * Reads one line, without its line break. A line longer than [maxBytes], or one that would take
     * [budget] past its byte count, is refused with 431; one still arriving after the budget's
     * deadline fails with [SocketTimeoutException], like a read that timed out.
     */
    fun readLine(source: InputStream, maxBytes: Int, budget: Budget): String {
        val buffer = StringBuilder()
        while (true) {
            val x: Int = source.read()
            if (x == -1) throw IOException("Unexpected EOF while reading header line")
            if (--budget.bytes < 0) throw HttpParser.RequestRejectedException(431, "Request Header Fields Too Large")
            if (System.nanoTime() - budget.deadlineNanos > 0) throw SocketTimeoutException("Request took too long")
            if (x == '\n'.code) return buffer.toString()
            if (x == '\r'.code) continue
            if (buffer.length >= maxBytes) throw HttpParser.RequestRejectedException(431, "Request Header Fields Too Large")
            buffer.append(x.toChar())
        }
    }
}
