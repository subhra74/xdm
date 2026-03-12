package xdm.app.utils

import xdm.core.util.Logger
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

object WinUtils {
    fun open(f: File): Boolean {
        return runCatching {
            if (!f.exists()) {
                return false
            }
            val builder = ProcessBuilder()
            val lst = ArrayList<String>()
            lst.add("rundll32")
            lst.add("url.dll,FileProtocolHandler")
            lst.add(f.absolutePath)
            builder.command(lst)
            builder.start()
        }.isSuccess
    }

    fun openFolder(folder: String, file: String?): Boolean {
        return runCatching {
            if (file == null) {
                openFolder2(folder)
            } else {
                val f = File(folder, file)
                if (!f.exists()) {
                    throw FileNotFoundException()
                }
                val builder = ProcessBuilder()
                val lst = ArrayList<String>()
                lst.add("explorer")
                lst.add("/select,")
                lst.add(f.absolutePath)
                builder.command(lst)
                builder.start()
            }
        }.isSuccess
    }

    private fun openFolder2(folder: String) {
        val builder = ProcessBuilder()
        val lst = ArrayList<String>()
        lst.add("explorer")
        lst.add(folder)
        builder.command(lst)
        builder.start()
    }

    fun keepAwakePing() {
       // NativeMethods.getInstance().keepAwakePing()
    }

    fun browseURL(url: String) {
        try {
            val builder = ProcessBuilder()
            val lst = ArrayList<String>()
            lst.add("rundll32")
            lst.add("url.dll,FileProtocolHandler")
            lst.add(url)
            builder.command(lst)
            builder.start()
        } catch (e: IOException) {
            Logger.info(e)
        }
    }

    fun initShutdown() {
        try {
            val builder = ProcessBuilder()
            val lst = ArrayList<String>()
            lst.add("shutdown")
            lst.add("-t")
            lst.add("30")
            lst.add("-s")
            builder.command(lst)
            builder.start()
        } catch (e: Exception) {
            Logger.info(e)
        }
    }
}