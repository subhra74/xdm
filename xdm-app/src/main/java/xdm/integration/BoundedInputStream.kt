package xdm.integration

import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import kotlin.math.min

/**
 * A request body on the connection: exactly [length] bytes of [source], never more, so a decoder
 * that reads ahead cannot run into the next request. A body still arriving after [deadlineNanos]
 * (a [System.nanoTime] value) fails like a timed-out read, and one that ends early fails too.
 * Closing it does not close the connection.
 */
class BoundedInputStream(
    private val source: InputStream,
    length: Long,
    private val deadlineNanos: Long,
) : InputStream() {
    private var remaining = length

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = source.read()
        afterRead(if (b == -1) -1 else 1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining <= 0) return -1
        val n = source.read(b, off, min(len.toLong(), remaining).toInt())
        afterRead(n)
        return n
    }

    override fun available(): Int = min(source.available().toLong(), remaining).toInt()

    /** Reads and drops what is left of the body, so the connection is at the next request. */
    fun discardRemaining() {
        val buf = ByteArray(8192)
        while (read(buf, 0, buf.size) != -1) Unit
    }

    override fun close() = Unit

    private fun afterRead(n: Int) {
        if (n == -1) throw IOException("Connection closed with $remaining body bytes unread")
        remaining -= n
        if (System.nanoTime() - deadlineNanos > 0) throw SocketTimeoutException("Request took too long")
    }
}
