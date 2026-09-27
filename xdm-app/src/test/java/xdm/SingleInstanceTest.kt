package xdm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xdm.app.AppConfig
import xdm.app.AppContext
import xdm.app.CapturedVideoTracker
import xdm.app.IAppInstance
import xdm.integration.BrowserIntegration
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-instance handling. The integration port is the lock: whoever binds it is XDM, a second
 * launch hands its arguments over and exits, and a port held by something else is a hard failure
 * rather than a silently half-started app.
 */
class SingleInstanceTest {

    private lateinit var dir: File
    private val shown = AtomicInteger()
    private val strays = mutableListOf<ServerSocket>()

    @Before
    fun setup() {
        dir = Files.createTempDirectory("xdm-single-instance").toFile()
        AppContext.configDir = dir.absolutePath
        AppContext.config = AppConfig(dir.absolutePath)
        AppContext.videoTracker = CapturedVideoTracker()
        // /show raises the window and then falls through to the usual /sync reply, so the app
        // facade has to be there; count how often it is asked to show itself.
        AppContext.app = Proxy.newProxyInstance(
            IAppInstance::class.java.classLoader, arrayOf(IAppInstance::class.java)
        ) { _, method, _ ->
            if (method.name == "showAppWindow") shown.incrementAndGet()
            null
        } as IAppInstance
    }

    @After
    fun tearDown() {
        releaseAcquiredPort()
        strays.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    // BrowserIntegration is an object, so the socket a test acquires has to be given back before
    // the next one runs.
    private fun releaseAcquiredPort() {
        val field = BrowserIntegration::class.java.getDeclaredField("serverSocket").apply { isAccessible = true }
        (field.get(BrowserIntegration) as ServerSocket?)?.let { runCatching { it.close() } }
        field.set(BrowserIntegration, null)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** A listener that is not XDM: it accepts and hangs up without answering. */
    private fun rudeListener(port: Int): ServerSocket =
        ServerSocket().apply {
            bind(InetSocketAddress("127.0.0.1", port))
            strays.add(this)
            Thread {
                while (!isClosed) {
                    runCatching { accept().close() }
                }
            }.apply { isDaemon = true }.start()
        }

    @Test
    fun freePortIsAcquiredAndHeld() {
        val port = freePort()
        assertEquals(BrowserIntegration.Acquired.Primary, BrowserIntegration.acquire(emptyArray(), port))

        // Holding it means holding it: nothing else can bind while we are primary.
        val second = runCatching { ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", port)) } }
        second.getOrNull()?.close()
        assertTrue("the port should still be held by the primary instance", second.isFailure)
    }

    @Test
    fun secondLaunchHandsOverAndIsToldToExit() {
        val port = freePort()
        assertEquals(BrowserIntegration.Acquired.Primary, BrowserIntegration.acquire(emptyArray(), port))
        BrowserIntegration.serve()

        val outcome = BrowserIntegration.acquire(arrayOf("xdm-app://launch"), port)

        assertEquals(BrowserIntegration.Acquired.AnotherInstance, outcome)
        assertEquals("the running instance should have been asked to show itself", 1, shown.get())
    }

    @Test
    fun portHeldByAnotherProgramIsReported() {
        val port = freePort()
        rudeListener(port)

        assertEquals(BrowserIntegration.Acquired.PortTaken, BrowserIntegration.acquire(emptyArray(), port))
        assertEquals("nothing should have been asked to show a window", 0, shown.get())
    }

    /**
     * The instance that answered the probe goes away before the handover request. Without the
     * retry this launch would exit and leave the user with no XDM at all.
     */
    @Test
    fun instanceThatDiesDuringHandoverLetsThisLaunchTakeOver() {
        val port = freePort()
        val probed = CountDownLatch(1)
        val listener = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", port)) }
        strays.add(listener)
        Thread {
            runCatching {
                // Answer the /sync probe convincingly, then vanish.
                listener.accept().use { answerLikeXdm(it) }
                listener.close()
                probed.countDown()
            }
        }.apply { isDaemon = true }.start()

        val outcome = BrowserIntegration.acquire(emptyArray(), port)

        assertTrue("the fake instance was never probed", probed.await(5, TimeUnit.SECONDS))
        assertEquals(BrowserIntegration.Acquired.Primary, outcome)
    }

    private fun answerLikeXdm(socket: Socket) {
        socket.getInputStream().read(ByteArray(4096))
        val body = """{"enabled":true,"fileExts":[],"instanceId":"fake"}"""
        val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\nConnection: close\r\n\r\n$body"
        socket.getOutputStream().apply {
            write(response.toByteArray(StandardCharsets.UTF_8))
            flush()
        }
    }
}
