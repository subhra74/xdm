import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal raw-socket HTTP/1.1 range server for simulating connection drops mid-transport with
 * full control of the TCP socket. `com.sun.net.httpserver.HttpServer` keeps connections alive and
 * leaves a truncated fixed-length response in a state that makes the client reuse a poisoned
 * pooled connection - so it can't cleanly model a dropped socket. Here we own the socket and can
 * RST it outright.
 *
 * Serves the given byte[] as `206 Partial Content` (honouring the request's Range). A connection
 * for which [shouldDrop] returns true sends only [dropAfterBytes] bytes of its range and then
 * hard-resets the socket (SO_LINGER 0). Every other connection serves its full range and closes
 * cleanly, so a retry (a fresh TCP connection) resumes and completes.
 */
class RawDropServer(
    private val data: ByteArray,
    private val dropAfterBytes: Int = 64 * 1024,
    private val shouldDrop: (connIndex: Int, rangeStart: Long) -> Boolean = { idx, _ -> idx == 1 },
) {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val connCount = AtomicInteger(0)
    val requestedRanges = CopyOnWriteArrayList<Long>()

    @Volatile
    private var running = true

    init {
        Thread {
            while (running) {
                val sock = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                Thread { handle(sock) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; name = "raw-drop-accept"; start() }
    }

    val port: Int get() = server.localPort
    fun url(path: String): String = "http://127.0.0.1:$port$path"

    private fun handle(sock: Socket) {
        val idx = connCount.incrementAndGet()
        try {
            val header = readRequestHeader(sock) ?: return
            val m = Regex("""Range: bytes=(\d+)-(\d*)""", RegexOption.IGNORE_CASE).find(header)
            val total = data.size.toLong()
            val start = m?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val end = m?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (total - 1)
            requestedRanges.add(start)
            val rangeLen = end - start + 1

            val out = sock.getOutputStream()
            val resp = "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Length: $rangeLen\r\n" +
                "Content-Range: bytes $start-$end/$total\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Connection: close\r\n\r\n"
            out.write(resp.toByteArray(Charsets.ISO_8859_1))

            if (shouldDrop(idx, start)) {
                val n = minOf(dropAfterBytes.toLong(), rangeLen).toInt()
                out.write(data, start.toInt(), n)
                out.flush()
                sock.setSoLinger(true, 0) // RST on close -> unambiguous drop
                sock.close()
            } else {
                out.write(data, start.toInt(), rangeLen.toInt())
                out.flush()
                out.close()
            }
        } catch (e: Exception) {
            // client went away / expected on drops
        } finally {
            try {
                sock.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun readRequestHeader(sock: Socket): String? {
        val input = sock.getInputStream()
        val sb = StringBuilder()
        val one = ByteArray(1)
        while (!sb.endsWith("\r\n\r\n")) {
            val r = input.read(one)
            if (r == -1) return if (sb.isNotEmpty()) sb.toString() else null
            sb.append((one[0].toInt() and 0xFF).toChar())
            if (sb.length > 16 * 1024) break // guard against a runaway request
        }
        return sb.toString()
    }

    fun stop() {
        running = false
        try {
            server.close()
        } catch (e: Exception) {
            // ignore
        }
    }
}
