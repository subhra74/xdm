package xdm.core.util

import java.io.PrintStream

object Logger {
    private val logStream: PrintStream
        get() = System.out

    private val errorStream: PrintStream
        get() = System.err

    fun info(tag: String, msg: String) {
        logStream.println("[ $tag ] $msg")
    }

    @JvmOverloads
    fun error(tag: String, msg: String, ex: Throwable? = null) {
        errorStream.println("[ $tag ] $msg")
        ex?.printStackTrace(errorStream)
    }

    fun info(obj: Any?) {
        if (obj is Throwable) {
            errorStream.print("[ " + Thread.currentThread().name + " ] ")
            obj.printStackTrace(errorStream)
        } else {
            logStream.println("[ " + Thread.currentThread().name + " ] " + obj)
        }
    }

    fun error(msg: String) {
        errorStream.println("[ " + Thread.currentThread().name + " ] " + msg)
    }

    fun error(t: Throwable) {
        errorStream.print("[ " + Thread.currentThread().name + " ] ")
        t.printStackTrace(errorStream)
    }

    fun error(msg: String?, t: Throwable?) {
        errorStream.println("[ " + Thread.currentThread().name + " ] " + msg)
        errorStream.print("[ " + Thread.currentThread().name + " ] ")
        t?.printStackTrace(errorStream)
    }
}
