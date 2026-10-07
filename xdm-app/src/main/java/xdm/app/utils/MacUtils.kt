package xdm.app.utils

import xdm.core.util.Logger
import java.io.File

object MacUtils {
    fun open(f: File): Boolean {
        return runCatching {
            if (!f.exists()) {
                return false
            }
            val pb = ProcessBuilder()
            pb.command("open", f.absolutePath)
            if (pb.start().waitFor() != 0) {
                Logger.error("Open failed")
                return false
            }
        }.isSuccess
    }

    fun openFolder(folder: String, file: String?): Boolean {
        return runCatching {
            if (file == null) {
                openFolder2(folder)
                return true
            }
            val f = File(folder, file)
            if (!f.exists()) {
                return false
            }
            val pb = ProcessBuilder()
            Logger.info("Opening folder: " + f.absolutePath)
            pb.command("open", "-R", f.absolutePath)
            if (pb.start().waitFor() != 0) {
                Logger.error("Open folder failed")
            }
        }.isSuccess
    }

    private fun openFolder2(folder: String) {
        val builder = ProcessBuilder()
        val lst = ArrayList<String>()
        lst.add("open")
        lst.add(folder)
        builder.command(lst)
        builder.start()
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

