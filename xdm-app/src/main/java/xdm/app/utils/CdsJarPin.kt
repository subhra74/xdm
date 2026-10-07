package xdm.app.utils

import xdm.core.util.Logger
import xdm.core.util.PlatformUtils

/**
 * Keeps the bundled AppCDS archive (`xdm.jsa`, see APPCDS.md) usable.
 *
 * The JVM maps the archive only while the jar's size and mtime match what they were at dump time;
 * otherwise it silently runs without it, ~19 MB heavier. Copying, unzipping and installers all
 * rewrite mtimes, so the build pins the jar to a fixed time and passes that time in as
 * `-Dxdm.cds.mtime` (epoch seconds). If the jar has drifted, [repin] puts the time back so the
 * *next* start maps the archive. Without the property - a plain `java -jar`, or a bundle built
 * without an archive - there is nothing to do.
 */
object CdsJarPin {

    const val PROPERTY = "xdm.cds.mtime"

    fun repin() {
        runCatching {
            val expected = System.getProperty(PROPERTY)?.toLongOrNull() ?: return
            // HotSpot appends "sharing" to java.vm.info while a CDS archive is mapped. The jlink runtime has no
            // default archive, so that can only be xdm.jsa.
            if (System.getProperty("java.vm.info").orEmpty().contains("sharing")) {
                Logger.info("CDS: archive in use")
            } else {
                Logger.info("CDS: archive not in use (jar size/mtime or JVM flags differ from the dump)")
            }
            // Not this class's CodeSource: loaded from the archive, it names the dump-time jar (or is null).
            val jar = PlatformUtils.appClassPathRoot ?: return
            if (!jar.isFile || jar.lastModified() / 1000 == expected) return
            if (jar.setLastModified(expected * 1000)) {
                Logger.info("CDS: re-pinned the jar mtime; the archive is used from the next start")
            } else {
                Logger.error("CDS: cannot re-pin ${jar.absolutePath}; running without the archive")
            }
        }.onFailure { Logger.error("CDS: could not check the jar mtime", it) }
    }
}
