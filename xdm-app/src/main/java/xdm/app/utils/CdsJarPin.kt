package xdm.app.utils

import xdm.core.util.Logger
import java.io.File

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
            val jar = File(CdsJarPin::class.java.protectionDomain.codeSource.location.toURI())
            if (!jar.isFile || jar.lastModified() / 1000 == expected) return
            if (jar.setLastModified(expected * 1000)) {
                Logger.info("CDS: re-pinned the jar mtime; the archive is used from the next start")
            } else {
                Logger.error("CDS: cannot re-pin ${jar.absolutePath}; running without the archive")
            }
        }.onFailure { Logger.error("CDS: could not check the jar mtime", it) }
    }
}
