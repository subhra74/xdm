package xdm.app.utils

import xdm.core.util.Logger
import java.io.*
import java.util.concurrent.TimeUnit

object LinuxUtils {
    // Each command is an explicit argv list so quoted arguments are passed verbatim (Runtime.exec
    // on a single String splits on whitespace and does NOT honor quotes, which corrupts dbus-send).
    // All of these work for an active local desktop session WITHOUT sudo on systemd distros
    // (Debian, Ubuntu, Fedora, etc.) via polkit's default allow_active=yes for power-off.
    // They are tried in order; the first one to exit 0 wins.
    var shutdownCmds: List<Array<String>> = listOf(
        // Preferred: systemd. Present on all mainstream modern distros.
        arrayOf("systemctl", "poweroff"),
        // Equivalent via logind over D-Bus (works even if `systemctl` isn't on PATH).
        arrayOf(
            "dbus-send", "--system", "--print-reply",
            "--dest=org.freedesktop.login1", "/org/freedesktop/login1",
            "org.freedesktop.login1.Manager.PowerOff", "boolean:true"
        ),
        // Legacy fallback for pre-systemd / ConsoleKit-based systems.
        arrayOf(
            "dbus-send", "--system", "--print-reply",
            "--dest=org.freedesktop.ConsoleKit", "/org/freedesktop/ConsoleKit/Manager",
            "org.freedesktop.ConsoleKit.Manager.Stop"
        )
    )

    fun initShutdown() {
        for (cmd in shutdownCmds) {
            try {
                val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                // Bound the wait so a polkit prompt or unresponsive bus can't hang the app.
                val finished = proc.waitFor(20, TimeUnit.SECONDS)
                if (finished && proc.exitValue() == 0) {
                    Logger.info("Shutdown initiated via: ${cmd.joinToString(" ")}")
                    return
                }
                if (!finished) proc.destroy()
            } catch (e: Exception) {
                Logger.error(e)
            }
        }
        Logger.error("All shutdown attempts failed; manual shutdown may be required")
    }


    fun open(f: File): Boolean {
        return runCatching {
            if (!f.exists()) {
                return false
            }
            val pb = ProcessBuilder()
            pb.command("xdg-open", f.absolutePath)
            pb.start() // .waitFor();
        }.isSuccess
    }

//    fun addToStartup() {
//        val dir = File(System.getProperty("user.home"), ".config/autostart")
//        dir.mkdirs()
//        val f = File(dir, "xdman.desktop")
//        var fs: FileOutputStream? = null
//        try {
//            fs = FileOutputStream(f)
//            fs.write(desktopFileString.toByteArray())
//        } catch (e: Exception) {
//            Logger.log(e)
//        } finally {
//            try {
//                fs?.close()
//            } catch (e2: Exception) {
//            }
//        }
//        f.setExecutable(true)
//    }
//
//    val isAlreadyAutoStart: Boolean
//        get() {
//            val f = File(System.getProperty("user.home"), ".config/autostart/xdman.desktop")
//            if (!f.exists()) return false
//            var `in`: FileInputStream? = null
//            val buf = ByteArray(f.length().toInt())
//            try {
//                `in` = FileInputStream(f)
//                if (`in`.read(buf).toLong() != f.length()) {
//                    return false
//                }
//            } catch (e: Exception) {
//                Logger.log(e)
//            } finally {
//                try {
//                    `in`?.close()
//                } catch (e2: Exception) {
//                }
//            }
//            val str = String(buf)
//            val s1 = getProperPath(System.getProperty("java.home"))
//            val s2 = XDMUtils.getJarFile().absolutePath
//            return str.contains(s1) && str.contains(s2)
//        }
//
//    fun removeFromStartup() {
//        val f = File(System.getProperty("user.home"), ".config/autostart/xdman.desktop")
//        f.delete()
//    }
//
//    private val desktopFileString: String
//        get() {
//            val str =
//                ("""[Desktop Entry]
//Encoding=UTF-8
//Version=1.0
//Type=Application
//Terminal=false
//Exec="%sbin/java" -Xmx1024m -jar "%s" -m
//Name=Xtreme Download Manager
//Comment=Xtreme Download Manager
//Categories=Network;
//Icon=/opt/xdman/icon.png""")
//            val s1 = getProperPath(System.getProperty("java.home"))
//            val s2 = XDMUtils.getJarFile().absolutePath
//            return String.format(str, s1, s2)
//        }
//
//    private fun getProperPath(path: String): String {
//        if (path.endsWith("/")) return path
//        return "$path/"
//    }
//
//    fun browseURL(url: String?) {
//        try {
//            val pb = ProcessBuilder()
//            pb.command("xdg-open", url)
//            pb.start() // .waitFor();
//        } catch (e: Exception) {
//            Logger.log(e)
//        }
//    }
//
//    val xDGDownloaDir: String?
//        get() {
//            var br: BufferedReader? = null
//            try {
//                br = BufferedReader(
//                    InputStreamReader(
//                        FileInputStream(
//                            File(
//                                System.getProperty("user.home"),
//                                ".config/user-dirs.dirs"
//                            )
//                        )
//                    )
//                )
//                while (true) {
//                    val line = br.readLine() ?: break
//                    if (line.startsWith("XDG_DOWNLOAD_DIR")) {
//                        val index = line.indexOf("=")
//                        if (index != -1) {
//                            var path = line.substring(index + 1).trim { it <= ' ' }
//                            path = path.replace("\$HOME", System.getProperty("user.home"))
//                            val f = File(path)
//                            if (f.exists()) {
//                                return f.absolutePath
//                            }
//                        }
//                    }
//                }
//            } catch (e: Exception) {
//                Logger.log(e)
//            } finally {
//                if (br != null) {
//                    try {
//                        br.close()
//                    } catch (e2: Exception) {
//                    }
//                }
//            }
//            return null
//        }
}
