package xdm.core.util

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    /** Number of most-recent launch logs to keep on disk; older ones are pruned. */
    private const val MAX_LOG_FILES = 5
    private const val LOG_PREFIX = "xdm-"
    private const val LOG_SUFFIX = ".log"

    private val consoleOut: PrintStream = System.out
    private val consoleErr: PrintStream = System.err

    /** Buffered file sink for the current launch; null until [init] runs. */
    @Volatile
    private var fileStream: PrintStream? = null

    private val timestamp: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())

    /**
     * Starts logging to a fresh per-launch file under `<logDir>/logs`, keeping only the
     * most recent [MAX_LOG_FILES] launches and deleting anything older. Writes go through a
     * [BufferedOutputStream] and are flushed on JVM shutdown. Console output is unaffected.
     */
    @Synchronized
    fun init(logDir: File) {
        try {
            val dir = File(logDir, "logs")
            dir.mkdirs()
            rotate(dir)

            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
            val file = File(dir, "$LOG_PREFIX$stamp$LOG_SUFFIX")
            val stream = PrintStream(BufferedOutputStream(FileOutputStream(file, true), 16 * 1024), false, "UTF-8")
            fileStream = stream

            Runtime.getRuntime().addShutdownHook(Thread {
                try {
                    stream.flush()
                    stream.close()
                } catch (ignored: Exception) {
                }
            })

            info("Logger", "Logging to $file")
        } catch (e: Exception) {
            consoleErr.println("[ Logger ] Failed to init file logging: " + e.message)
        }
    }

    /** Deletes the oldest logs so that at most [MAX_LOG_FILES] - 1 remain before the new one. */
    private fun rotate(dir: File) {
        val logs = dir.listFiles { f ->
            f.isFile && f.name.startsWith(LOG_PREFIX) && f.name.endsWith(LOG_SUFFIX)
        } ?: return
        if (logs.size < MAX_LOG_FILES) return
        logs.sortBy { it.lastModified() }
        val toDelete = logs.size - (MAX_LOG_FILES - 1)
        for (i in 0 until toDelete) {
            logs[i].delete()
        }
    }

    private fun writeLine(line: String) {
        fileStream?.let {
            it.print(timestamp)
            it.print(' ')
            it.println(line)
        }
    }

    private fun writeThrowable(t: Throwable?) {
        t ?: return
        fileStream?.let { t.printStackTrace(it) }
    }

    fun info(tag: String, msg: String) {
        val line = "[ $tag ] $msg"
        consoleOut.println(line)
        writeLine(line)
    }

    @JvmOverloads
    fun error(tag: String, msg: String, ex: Throwable? = null) {
        val line = "[ $tag ] $msg"
        consoleErr.println(line)
        ex?.printStackTrace(consoleErr)
        writeLine(line)
        writeThrowable(ex)
    }

    fun info(obj: Any?) {
        val thread = Thread.currentThread().name
        if (obj is Throwable) {
            consoleErr.print("[ $thread ] ")
            obj.printStackTrace(consoleErr)
            writeLine("[ $thread ] ${obj.message}")
            writeThrowable(obj)
        } else {
            val line = "[ $thread ] $obj"
            consoleOut.println(line)
            writeLine(line)
        }
    }

    fun error(msg: String) {
        val line = "[ " + Thread.currentThread().name + " ] " + msg
        consoleErr.println(line)
        writeLine(line)
    }

    fun error(t: Throwable) {
        val prefix = "[ " + Thread.currentThread().name + " ] "
        consoleErr.print(prefix)
        t.printStackTrace(consoleErr)
        writeLine(prefix + t.message)
        writeThrowable(t)
    }

    fun error(msg: String?, t: Throwable?) {
        val line = "[ " + Thread.currentThread().name + " ] " + msg
        consoleErr.println(line)
        consoleErr.print("[ " + Thread.currentThread().name + " ] ")
        t?.printStackTrace(consoleErr)
        writeLine(line)
        writeThrowable(t)
    }
}
