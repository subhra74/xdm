package xdm.integration


import xdm.core.util.Logger
import java.io.BufferedInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/**
 * Decides, from a request's line and headers alone, whether the server reads the rest of it. Runs
 * before the body is read, so a refused request costs no more than its head.
 */
interface RequestPolicy {
    /** The (status code, message) to refuse [head] with, or null to accept it. */
    fun reject(head: HttpParser.RequestHead): Pair<Int, String>? = null

    /** Largest body accepted for [head]; a longer `Content-Length` is refused with 413 unread. */
    fun maxBodyBytes(head: HttpParser.RequestHead): Long = HttpParser.DEFAULT_MAX_BODY_BYTES

    companion object {
        val ACCEPT_ALL = object : RequestPolicy {}
    }
}

/**
 * Serves an already-bound socket. Binding is deliberately *not* done here: whoever owns the port
 * binds it first and synchronously (see [BrowserIntegration.acquire]), because a failed bind means
 * "another instance owns this machine's XDM" and has to be answered before the app starts, not on a
 * background thread afterwards. Previously bind and the accept loop shared one try/catch and one
 * `onFailure` callback, so a startup failure and a loop that died later were indistinguishable.
 *
 * Connections that arrive between the bind and [start] are held in the listen backlog, so the
 * caller can finish wiring up its services before any request is handled.
 *
 * Any web page can reach a loopback port, so every connection is bounded: at most
 * [maxConnections] at once, [readTimeoutMs] for each read, [HttpParser.REQUEST_DEADLINE_MS] for
 * a whole request, and the size limits of [HttpParser] and [policy].
 */
class HttpServer(
    private val serverSocket: ServerSocket,
    private val policy: RequestPolicy = RequestPolicy.ACCEPT_ALL,
    private val maxConnections: Int = MAX_CONNECTIONS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val requestListener: (ctx: RequestContext) -> Unit,
) {
    companion object {
        /**
         * Connections served at once. The extension needs a handful (parked polls are capped at
         * [EventChannel]'s waiter limit), so this only stops a flood from taking a thread each.
         */
        const val MAX_CONNECTIONS = 32

        /**
         * Longest wait for the client's next bytes, which is also how long an idle kept-alive
         * connection stays open. A parked long poll is not reading, so it is not affected.
         */
        const val READ_TIMEOUT_MS = 30_000

        private const val REFUSAL_LOG_INTERVAL_MS = 60_000L
    }

    private val permits = Semaphore(maxConnections)
    private val lastRefusalLog = AtomicLong(0)

    /** Starts the accept loop. The socket is already bound and listening by now. */
    fun start() {
        Thread {
            while (!serverSocket.isClosed) {
                process()
            }
        }.apply {
            name = "xdm-integration-accept"
            // Not a daemon, as before: with the window hidden (--minimized) this thread is part of
            // what keeps the JVM alive, and the shutdown hook closes the socket to release it.
        }.start()
    }

    private fun process() {
        try {
            val socket = serverSocket.accept()
            if (!socket.inetAddress.isLoopbackAddress) {
                Logger.info("INTEGRATION", "Rejected non-loopback connection from ${socket.inetAddress.hostAddress}")
                socket.close()
                return
            }
            if (!permits.tryAcquire()) {
                logRefusal()
                socket.close()
                return
            }
            try {
                socket.soTimeout = readTimeoutMs
                processRequest(socket)
            } catch (e: Throwable) {
                permits.release()
                runCatching { socket.close() }
                throw e
            }
        } catch (e: IOException) {
            if (!serverSocket.isClosed) Logger.info(e)
        } catch (e: Exception) {
            // One bad connection must not take the accept loop - and with it browser integration -
            // down for the rest of the session.
            Logger.error("INTEGRATION", "Error accepting a connection", e)
        }
    }

    fun stop() {
        try {
            serverSocket.close()
        } catch (e: IOException) {
            // ignore
        }
    }

    private fun processRequest(socket: Socket) {
        Thread {
            try {
                socket.use { serve(it) }
            } catch (_: HttpParser.ConnectionClosedException) {
                // Client closed an idle kept-alive connection.
            } catch (e: Exception) {
                Logger.info(e.message)
            } finally {
                permits.release()
            }
        }.start()
    }

    /** Serves requests on [socket] until one is the last on its connection or is refused. */
    private fun serve(socket: Socket) {
        // One buffer for the life of the connection: it may hold the start of the next request.
        val input = BufferedInputStream(socket.getInputStream())
        while (true) {
            val ctx = try {
                val head = HttpParser.parseHead(input)
                policy.reject(head)?.let { (code, message) -> return sendRefusal(socket, code, message) }
                val length = HttpParser.contentLength(head)
                if (length > policy.maxBodyBytes(head)) {
                    Logger.info("INTEGRATION", "Refused ${head.method} ${head.route}: body of $length bytes is too large")
                    return sendRefusal(socket, 413, "Content Too Large")
                }
                RequestContext(head, BoundedInputStream(input, length, head.deadlineNanos), length, socket)
            } catch (e: HttpParser.RequestRejectedException) {
                Logger.info("INTEGRATION", "Refused a malformed request: ${e.message}")
                return sendRefusal(socket, e.status, e.reason)
            }
            requestListener(ctx)
            if (!ctx.keepAlive) {
                return
            }
            ctx.discardRemainingBody()
        }
    }

    /** Answers a refused request and ends its connection: the rest of the request is left unread. */
    private fun sendRefusal(socket: Socket, code: Int, message: String) {
        val response = "HTTP/1.1 $code $message${CRLF}Content-Length: 0${CRLF}Connection: close$CRLF$CRLF"
        runCatching {
            socket.getOutputStream().apply {
                write(response.toByteArray(StandardCharsets.ISO_8859_1))
                flush()
            }
        }
    }

    private fun logRefusal() {
        val now = System.currentTimeMillis()
        val last = lastRefusalLog.get()
        if (now - last >= REFUSAL_LOG_INTERVAL_MS && lastRefusalLog.compareAndSet(last, now)) {
            Logger.info("INTEGRATION", "Refused a connection: $maxConnections are already open")
        }
    }
}
