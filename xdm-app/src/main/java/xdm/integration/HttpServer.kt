package xdm.integration


import xdm.core.util.Logger
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * Serves an already-bound socket. Binding is deliberately *not* done here: whoever owns the port
 * binds it first and synchronously (see [BrowserIntegration.acquire]), because a failed bind means
 * "another instance owns this machine's XDM" and has to be answered before the app starts, not on a
 * background thread afterwards. Previously bind and the accept loop shared one try/catch and one
 * `onFailure` callback, so a startup failure and a loop that died later were indistinguishable.
 *
 * Connections that arrive between the bind and [start] are held in the listen backlog, so the
 * caller can finish wiring up its services before any request is handled.
 */
class HttpServer(
    private val serverSocket: ServerSocket,
    private val requestListener: (ctx: RequestContext) -> Unit,
) {
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
            processRequest(socket)
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
                socket.use {
                    while (true) {
                        val ctx = HttpParser.parseContext(socket)
                        requestListener(ctx)
                        if (!ctx.keepAlive) {
                            break
                        }
                    }
                }
            } catch (_: HttpParser.ConnectionClosedException) {
                // Client closed an idle kept-alive connection.
            } catch (e: Exception) {
                Logger.info(e.message)
            }
        }.start()
    }
}
