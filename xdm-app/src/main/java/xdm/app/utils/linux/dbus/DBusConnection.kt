package xdm.app.utils.linux.dbus

import xdm.core.util.Logger
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** One D-Bus message: the header fields XDM uses, and the body read with its signature. */
class Message(
    val type: Int,
    val flags: Int,
    val serial: Int,
    val path: String?,
    val interfaceName: String?,
    val member: String?,
    val errorName: String?,
    val replySerial: Int?,
    val destination: String?,
    val sender: String?,
    val signature: String,
    val body: List<Any?>,
) {
    companion object {
        const val METHOD_CALL = 1
        const val METHOD_RETURN = 2
        const val ERROR = 3
        const val SIGNAL = 4

        const val NO_REPLY_EXPECTED = 0x1

        private const val PATH = 1
        private const val INTERFACE = 2
        private const val MEMBER = 3
        private const val ERROR_NAME = 4
        private const val REPLY_SERIAL = 5
        private const val DESTINATION = 6
        private const val SENDER = 7
        private const val SIGNATURE = 8

        /** The largest message the spec allows; anything bigger means a broken stream. */
        private const val MAX_MESSAGE = 128 * 1024 * 1024

        fun encode(
            type: Int, serial: Int, flags: Int = 0,
            path: String? = null, interfaceName: String? = null, member: String? = null,
            errorName: String? = null, replySerial: Int? = null, destination: String? = null,
            signature: String = "", body: List<Any?> = emptyList(),
        ): ByteArray {
            val bodyBytes = Writer().apply { if (signature.isNotEmpty()) write(signature, body) }.bytes()
            val fields = buildList {
                path?.let { add(listOf(PATH, Variant("o", it))) }
                interfaceName?.let { add(listOf(INTERFACE, Variant("s", it))) }
                member?.let { add(listOf(MEMBER, Variant("s", it))) }
                errorName?.let { add(listOf(ERROR_NAME, Variant("s", it))) }
                replySerial?.let { add(listOf(REPLY_SERIAL, Variant("u", it))) }
                destination?.let { add(listOf(DESTINATION, Variant("s", it))) }
                if (signature.isNotEmpty()) add(listOf(SIGNATURE, Variant("g", signature)))
            }
            val header = Writer().apply {
                write("yyyyuua(yv)", listOf('l'.code, type, flags, 1, bodyBytes.size, serial, fields))
                pad(8)
            }
            return header.bytes() + bodyBytes
        }

        /** Reads one whole message from [channel]; blocks until it has arrived. */
        fun read(channel: ReadableByteChannel): Message {
            val fixed = readFully(channel, ByteBuffer.allocate(16))
            val order = when (fixed.get(0).toInt().toChar()) {
                'l' -> ByteOrder.LITTLE_ENDIAN
                'B' -> ByteOrder.BIG_ENDIAN
                else -> throw IOException("Bad D-Bus endianness marker ${fixed.get(0)}")
            }
            fixed.order(order)
            val bodyLength = fixed.getInt(4)
            val fieldsLength = fixed.getInt(12)
            val headerEnd = (16 + fieldsLength + 7) and 7.inv()
            val total = headerEnd.toLong() + bodyLength
            if (bodyLength < 0 || fieldsLength < 0 || total > MAX_MESSAGE) throw IOException("D-Bus message too large: $total")

            val all = ByteBuffer.allocate(total.toInt()).order(order)
            all.put(fixed.array())
            readFully(channel, all)
            all.flip()

            val reader = Reader(all)
            reader.position = 12
            @Suppress("UNCHECKED_CAST")
            val fields = (reader.readOne("a(yv)") as List<List<Any?>>)
                .associate { (it[0] as Byte).toInt() to (it[1] as Variant).value }
            val signature = fields[SIGNATURE] as String? ?: ""
            reader.position = headerEnd
            val body = if (signature.isEmpty()) emptyList() else reader.read(signature)
            return Message(
                type = all.get(1).toInt(),
                flags = all.get(2).toInt(),
                serial = all.getInt(8),
                path = fields[PATH] as String?,
                interfaceName = fields[INTERFACE] as String?,
                member = fields[MEMBER] as String?,
                errorName = fields[ERROR_NAME] as String?,
                replySerial = fields[REPLY_SERIAL] as Int?,
                destination = fields[DESTINATION] as String?,
                sender = fields[SENDER] as String?,
                signature = signature,
                body = body,
            )
        }

        private fun readFully(channel: ReadableByteChannel, buffer: ByteBuffer): ByteBuffer {
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw IOException("D-Bus connection closed")
            }
            return buffer
        }
    }
}

/** The reply to a method call on one of our objects. */
class Reply(val signature: String = "", val values: List<Any?> = emptyList())

/** An object XDM puts on the bus. Called on the bus's dispatch thread; must not block. */
interface ExportedObject {
    /** The `<interface>` elements of this object's introspection data. */
    val introspection: String

    /** Handles [member] of [interfaceName]; null for a method it doesn't have. May throw [DBusError]. */
    fun invoke(interfaceName: String?, member: String, args: List<Any?>): Reply?
}

/**
 * A minimal D-Bus client, just enough for a tray icon and notifications: connect to the session bus over
 * a Unix domain socket (java.base only), authenticate with EXTERNAL, call methods, export objects and
 * receive signals. No unix-fd passing, no abstract sockets, no server side.
 *
 * Threads: one reader thread that only parses messages and completes pending calls, one thread for
 * incoming method calls and one for signals, so a signal listener can make a call and a call into us
 * is answered while another thread waits on a reply.
 */
class DBusConnection private constructor(private val channel: SocketChannel) : Closeable {

    private val serials = AtomicInteger()
    private val writeLock = Any()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Message>>()
    private val objects = ConcurrentHashMap<String, ExportedObject>()
    private val signalListeners = CopyOnWriteArrayList<(Message) -> Unit>()
    private val calls: ExecutorService = Executors.newSingleThreadExecutor(daemon("dbus-calls"))
    private val signals: ExecutorService = Executors.newSingleThreadExecutor(daemon("dbus-signals"))

    @Volatile
    var isOpen = true
        private set

    /** Our unique bus name, e.g. `:1.42`. */
    lateinit var uniqueName: String
        private set

    companion object {
        const val BUS = "org.freedesktop.DBus"
        const val BUS_PATH = "/org/freedesktop/DBus"
        private const val TIMEOUT_MILLIS = 10_000L

        /** Connects to the session bus. Throws when there is none we can reach. */
        fun openSession(): DBusConnection {
            val socket = sessionSocket() ?: throw IOException("No session bus address")
            val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                channel.connect(UnixDomainSocketAddress.of(socket))
                authenticate(channel)
            } catch (e: Exception) {
                channel.close()
                throw e
            }
            return DBusConnection(channel).apply { start() }
        }

        /**
         * The socket from `DBUS_SESSION_BUS_ADDRESS` (`unix:path=…` entries; `abstract=` needs native
         * code), else `$XDG_RUNTIME_DIR/bus`, where systemd and dbus-broker put it.
         */
        private fun sessionSocket(): Path? {
            System.getenv("DBUS_SESSION_BUS_ADDRESS")?.split(';')?.forEach { address ->
                if (!address.startsWith("unix:")) return@forEach
                address.removePrefix("unix:").split(',').forEach { kv ->
                    if (kv.startsWith("path=")) {
                        val path = Path.of(unescape(kv.removePrefix("path=")))
                        if (Files.exists(path)) return path
                    }
                }
            }
            return System.getenv("XDG_RUNTIME_DIR")?.let { Path.of(it, "bus") }?.takeIf { Files.exists(it) }
        }

        private fun unescape(value: String): String {
            if (!value.contains('%')) return value
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                if (value[i] == '%' && i + 2 < value.length) {
                    out.write(value.substring(i + 1, i + 3).toInt(16))
                    i += 3
                } else {
                    out.write(value[i].code)
                    i++
                }
            }
            return out.toString(StandardCharsets.UTF_8)
        }

        /** SASL EXTERNAL: the bus checks our uid against the socket's peer credentials. */
        private fun authenticate(channel: SocketChannel) {
            val uid = currentUid()
            val hexUid = uid.toString().toByteArray(StandardCharsets.US_ASCII).joinToString("") { "%02x".format(it) }
            channel.write(ByteBuffer.wrap(byteArrayOf(0)))
            writeLine(channel, "AUTH EXTERNAL $hexUid")
            val answer = readLine(channel)
            if (!answer.startsWith("OK ")) throw IOException("D-Bus authentication refused: $answer")
            writeLine(channel, "BEGIN")
        }

        /** `/proc/self` belongs to us on Linux; elsewhere (a test bus on macOS) a new file does. */
        private fun currentUid(): Int = runCatching { Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int }
            .getOrElse {
                val probe = Files.createTempFile("xdm-uid", null)
                try {
                    Files.getAttribute(probe, "unix:uid") as Int
                } finally {
                    Files.deleteIfExists(probe)
                }
            }

        private fun writeLine(channel: SocketChannel, line: String) {
            val buffer = ByteBuffer.wrap("$line\r\n".toByteArray(StandardCharsets.US_ASCII))
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        private fun readLine(channel: SocketChannel): String {
            val line = StringBuilder()
            val one = ByteBuffer.allocate(1)
            while (line.length < 512) {
                one.clear()
                if (channel.read(one) < 0) throw IOException("D-Bus connection closed during authentication")
                val c = one.get(0).toInt().toChar()
                if (c == '\n') return line.toString().trimEnd('\r')
                line.append(c)
            }
            throw IOException("D-Bus authentication line too long")
        }

        private fun daemon(name: String) = { r: Runnable -> Thread(r, name).apply { isDaemon = true } }
    }

    private fun start() {
        Thread(::readLoop, "dbus-reader").apply { isDaemon = true }.start()
        uniqueName = call(BUS, BUS_PATH, BUS, "Hello").first() as String
    }

    /** Calls a method and waits for its reply's body. Throws [DBusError] for an error reply. */
    fun call(
        destination: String, path: String, interfaceName: String, member: String,
        signature: String = "", args: List<Any?> = emptyList(), timeoutMillis: Long = TIMEOUT_MILLIS,
    ): List<Any?> {
        val serial = serials.incrementAndGet()
        val reply = CompletableFuture<Message>()
        pending[serial] = reply
        try {
            send(
                Message.encode(
                    Message.METHOD_CALL, serial, path = path, interfaceName = interfaceName, member = member,
                    destination = destination, signature = signature, body = args,
                )
            )
            val message = try {
                reply.get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                throw DBusError("org.freedesktop.DBus.Error.Timeout", "$interfaceName.$member timed out")
            }
            if (message.type == Message.ERROR) {
                throw DBusError(message.errorName ?: "org.freedesktop.DBus.Error.Failed", message.body.firstOrNull() as? String)
            }
            return message.body
        } finally {
            pending.remove(serial)
        }
    }

    fun emitSignal(path: String, interfaceName: String, member: String, signature: String = "", args: List<Any?> = emptyList()) {
        send(
            Message.encode(
                Message.SIGNAL, serials.incrementAndGet(), path = path, interfaceName = interfaceName,
                member = member, signature = signature, body = args,
            )
        )
    }

    fun export(path: String, obj: ExportedObject) {
        objects[path] = obj
    }

    /** `RequestName` with `DO_NOT_QUEUE`; true when we became the owner. */
    fun requestName(name: String): Boolean =
        (call(BUS, BUS_PATH, BUS, "RequestName", "su", listOf(name, 4)).first() as Int) in setOf(1, 4)

    fun nameHasOwner(name: String): Boolean = call(BUS, BUS_PATH, BUS, "NameHasOwner", "s", listOf(name)).first() as Boolean

    /** Asks the bus to route signals matching [rule] to us, and calls [listener] for every signal. */
    fun addSignalListener(rule: String, listener: (Message) -> Unit) {
        call(BUS, BUS_PATH, BUS, "AddMatch", "s", listOf(rule))
        signalListeners += listener
    }

    override fun close() {
        isOpen = false
        runCatching { channel.close() }
        calls.shutdownNow()
        signals.shutdownNow()
        pending.values.forEach { it.completeExceptionally(IOException("D-Bus connection closed")) }
    }

    private fun send(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        synchronized(writeLock) {
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }

    private fun readLoop() {
        try {
            while (isOpen) {
                val message = Message.read(channel)
                when (message.type) {
                    Message.METHOD_RETURN, Message.ERROR -> message.replySerial?.let { pending[it]?.complete(message) }
                    Message.METHOD_CALL -> calls.execute { answer(message) }
                    Message.SIGNAL -> signals.execute {
                        signalListeners.forEach { listener ->
                            runCatching { listener(message) }.onFailure { Logger.error("DBus", "Signal listener failed", it) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (isOpen) Logger.error("DBus", "Connection lost: ${e.message}")
        } finally {
            close()
        }
    }

    private fun answer(call: Message) {
        val reply = try {
            dispatch(call)
        } catch (e: DBusError) {
            replyError(call, e.name, e.message)
            return
        } catch (e: Exception) {
            Logger.error("DBus", "${call.interfaceName}.${call.member} on ${call.path} failed", e)
            replyError(call, "org.freedesktop.DBus.Error.Failed", e.message)
            return
        }
        if (call.flags and Message.NO_REPLY_EXPECTED != 0) return
        runCatching {
            send(
                Message.encode(
                    Message.METHOD_RETURN, serials.incrementAndGet(), replySerial = call.serial,
                    destination = call.sender, signature = reply.signature, body = reply.values,
                )
            )
        }
    }

    private fun dispatch(call: Message): Reply {
        val member = call.member ?: throw DBusError("org.freedesktop.DBus.Error.UnknownMethod", "No member")
        when (call.interfaceName) {
            "org.freedesktop.DBus.Peer" -> return when (member) {
                "Ping" -> Reply()
                "GetMachineId" -> Reply("s", listOf(runCatching { File("/etc/machine-id").readText().trim() }.getOrDefault("")))
                else -> throw DBusError("org.freedesktop.DBus.Error.UnknownMethod", member)
            }
            "org.freedesktop.DBus.Introspectable" -> if (member == "Introspect") return Reply("s", listOf(introspect(call.path)))
        }
        val obj = objects[call.path] ?: throw DBusError("org.freedesktop.DBus.Error.UnknownObject", call.path)
        return obj.invoke(call.interfaceName, member, call.body)
            ?: throw DBusError("org.freedesktop.DBus.Error.UnknownMethod", "${call.interfaceName}.$member")
    }

    private fun introspect(path: String?): String {
        val children = objects.keys
            .filter { path != null && it != path && it.startsWith(if (path == "/") "/" else "$path/") }
            .map { it.removePrefix(if (path == "/") "/" else "$path/").substringBefore('/') }
            .distinct()
        return buildString {
            append("<!DOCTYPE node PUBLIC \"-//freedesktop//DTD D-BUS Object Introspection 1.0//EN\" ")
            append("\"http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd\">\n<node>\n")
            append("<interface name=\"org.freedesktop.DBus.Introspectable\"><method name=\"Introspect\">")
            append("<arg name=\"data\" type=\"s\" direction=\"out\"/></method></interface>\n")
            append("<interface name=\"org.freedesktop.DBus.Peer\"><method name=\"Ping\"/>")
            append("<method name=\"GetMachineId\"><arg name=\"id\" type=\"s\" direction=\"out\"/></method></interface>\n")
            objects[path]?.let { append(it.introspection).append('\n') }
            children.forEach { append("<node name=\"").append(it).append("\"/>\n") }
            append("</node>\n")
        }
    }

    private fun replyError(call: Message, name: String, text: String?) {
        if (call.flags and Message.NO_REPLY_EXPECTED != 0) return
        runCatching {
            send(
                Message.encode(
                    Message.ERROR, serials.incrementAndGet(), errorName = name, replySerial = call.serial,
                    destination = call.sender, signature = "s", body = listOf(text ?: name),
                )
            )
        }
    }
}
