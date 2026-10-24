package xdm.app.utils

import xdm.app.utils.win.Win32Shell
import xdm.core.util.Logger
import java.io.File
import java.io.IOException

object WinUtils {
    /**
     * Opens [f] with its default application through `ShellExecuteExW`, so the shell runs its zone
     * checks (SmartScreen) on a downloaded file the way an Explorer double-click does. No fallback:
     * `Desktop.open` would repeat a prompt the user declined, and `rundll32 url.dll` skips SmartScreen.
     * Blocks while a prompt is up.
     */
    fun open(f: File): Boolean = f.exists() && Win32Shell.openFile(f.absolutePath)

    /**
     * Opens [folder] in Explorer with [file] selected. Both go through the shell API rather than an
     * `explorer.exe` command line, which splits on commas. Initialises COM, so never on the EDT.
     * When the shell calls cannot run at all, falls back to `explorer`, with the path quoted.
     */
    fun openFolder(folder: String, file: String?): Boolean {
        if (file != null && Win32Shell.selectInExplorer(File(folder, file).absolutePath)) return true
        if (Win32Shell.execute(File(folder).absolutePath)) return true
        // ProcessBuilder passes an argument that is already quoted as-is; Windows paths cannot hold '"'.
        return runCatching { ProcessBuilder("explorer", "\"" + File(folder).absolutePath + "\"").start() }
            .onFailure { Logger.error(it) }
            .isSuccess
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