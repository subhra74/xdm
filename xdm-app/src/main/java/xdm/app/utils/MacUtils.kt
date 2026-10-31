package xdm.app.utils

import xdm.core.util.Logger
import java.io.File
import java.util.concurrent.TimeUnit

object MacUtils {
    fun open(f: File): Boolean = run("open", f.absolutePath)

    /** Reveals [file] in Finder (`open -R`), or opens [folder] when there is no file. */
    fun openFolder(folder: String, file: String?): Boolean =
        if (file != null) run("open", "-R", File(folder, file).absolutePath) else run("open", folder)

    /**
     * Runs `open` and reports whether it succeeded. The wait is bounded so a stalled LaunchServices
     * cannot hang the caller; a timeout counts as success, since the request has been handed over.
     */
    private fun run(vararg cmd: String): Boolean = runCatching {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!proc.waitFor(5, TimeUnit.SECONDS)) return@runCatching true
        val ok = proc.exitValue() == 0
        if (!ok) Logger.error("${cmd.joinToString(" ")} failed: exit ${proc.exitValue()}")
        ok
    }.getOrElse {
        Logger.error(it)
        false
    }

//    fun launchApp(app: String?, args: String?): Boolean {
//        try {
//            val pb = ProcessBuilder()
//            pb.command("open", "-n", "-a", app, "--args", args)
//            if (pb.start().waitFor() != 0) {
//                throw FileNotFoundException()
//            }
//            // Runtime.getRuntime().exec(new String[] { "open \"" + app + "\" " + args });
//            return true
//        } catch (e: Exception) {
//            Logger.log(e)
//            return false
//        }
//    }
//
//    fun keepAwakePing() {
//        try {
//            Runtime.getRuntime().exec("caffeinate -i -t 3")
//        } catch (e: Exception) {
//            Logger.log(e)
//        }
//    }
//
//    fun addToStartup() {
//        val dir = File(System.getProperty("user.home"), "Library/LaunchAgents")
//        dir.mkdirs()
//        val f = File(dir, "org.sdg.xdman.plist")
//        var fs: FileOutputStream? = null
//        try {
//            fs = FileOutputStream(f)
//            fs.write(startupPlist.toByteArray())
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
//            val f = File(System.getProperty("user.home"), "Library/LaunchAgents/org.sdg.xdman.plist")
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
//        val f = File(System.getProperty("user.home"), "Library/LaunchAgents/org.sdg.xdman.plist")
//        f.delete()
//    }
//
//    val startupPlist: String
//        get() {
//            val str = ("""<?xml version="1.0" encoding="UTF-8"?>
//<!DOCTYPE plist PUBLIC "-//Apple Computer//DTD PLIST 1.0//EN"
//"http://www.apple.com/DTDs/PropertyList-1.0.dtd" >
//<plist version="1.0">
//	<dict>
//		<key>Label</key>
//		<string>org.sdg.xdman</string>
//		<key>ProgramArguments</key>
//		<array>
//			<string>%sbin/java</string>
//			<string>-Xmx1024m</string>
//			<string>-Xdock:name=XDM</string>
//			<string>-jar</string>
//			<!-- MODIFY THIS TO POINT TO YOUR EXECUTABLE JAR FILE -->
//			<string>%s</string>
//			<string>-m</string>
//		</array>
//		<key>OnDemand</key>
//		<true />
//		<key>RunAtLoad</key>
//		<true />
//		<key>KeepAlive</key>
//		<false />
//	</dict>
//</plist>""")
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
//            pb.command("open", url)
//            pb.start() // .waitFor();
//        } catch (e: Exception) {
//            Logger.log(e)
//        }
//    }
//
    fun initShutdown() {
        try {
            val builder = ProcessBuilder()
            val lst = ArrayList<String>()
            lst.add("osascript")
            lst.add("-e")
            lst.add("tell app \"System Events\" to shut down")
            builder.command(lst)
            builder.start()
        } catch (e: Exception) {
            Logger.error(e)
        }
    }
}

