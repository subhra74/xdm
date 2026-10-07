package xdm.app.utils

import xdm.core.util.PlatformUtils
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

/**
 * The MSI's two hooks for the Windows heap file (PACKAGING.md §5.7). Both run through the bundled
 * `java.exe` and exit before anything else in `AppMain` happens: no `Logger`, no profile folder, no
 * port, no UI.
 *
 *  - `--heap-test=<marker>`: run as the installing user with `-XX:AllocateHeapAt=<dir>`. Reaching
 *    `main` means the JVM created its heap there with the user's rights, so it writes the marker.
 *  - `--apply-heap-cfg=<marker> --heap-dir=<dir>`: run elevated. Rewrites every `<name>.cfg` next to
 *    the jar from its `<name>.cfg.base`, with `-XX:AllocateHeapAt=<dir>` only if a fresh marker exists,
 *    then deletes the marker. It always rewrites, so an upgrade never keeps the previous version's
 *    `.cfg` (MSI leaves unversioned files it thinks were modified).
 *
 * Output goes to stdout/stderr, which `WixQuietExec` copies into the MSI log.
 */
object HeapFileSetup {

    private const val TEST = "--heap-test="
    private const val APPLY = "--apply-heap-cfg="
    private const val HEAP_DIR = "--heap-dir="
    private const val BASE_SUFFIX = ".base"
    private const val JAVA_OPTIONS_SECTION = "[JavaOptions]"
    private const val HEAP_LINE = "java-options=-XX:AllocateHeapAt="

    /** A marker older than this was left by an interrupted install and must not turn the heap file on. */
    private const val MARKER_MAX_AGE_MS = 15 * 60 * 1000L

    /** Exits the JVM when [args] holds one of the hooks; otherwise returns and startup goes on. */
    fun handle(args: Array<String>) {
        args.firstOrNull { it.startsWith(TEST) }?.let {
            exitProcess(test(File(it.removePrefix(TEST))))
        }
        args.firstOrNull { it.startsWith(APPLY) }?.let { apply ->
            val heapDir = args.firstOrNull { it.startsWith(HEAP_DIR) }?.removePrefix(HEAP_DIR)?.ifBlank { null }
            exitProcess(
                apply(PlatformUtils.baseDirectory, File(apply.removePrefix(APPLY)), heapDir, System.currentTimeMillis())
            )
        }
    }

    internal fun test(marker: File): Int =
        runCatching {
            marker.absoluteFile.parentFile?.mkdirs()
            marker.writeText("ok")
            println("Heap test passed: ${System.getProperty("java.version")}")
            0
        }.getOrElse {
            System.err.println("Heap test: could not write $marker: $it")
            1
        }

    internal fun apply(appDir: File?, marker: File, heapDir: String?, now: Long): Int {
        try {
            val bases = appDir?.listFiles { f -> f.isFile && f.name.endsWith(".cfg$BASE_SUFFIX") }.orEmpty()
            if (bases.isEmpty()) {
                System.err.println("No *.cfg$BASE_SUFFIX in $appDir; launcher config left as installed")
                return 1
            }
            val age = now - marker.lastModified()
            val useHeap = heapDir != null && marker.isFile && age in -60_000L..MARKER_MAX_AGE_MS
            var failed = false
            bases.forEach { base ->
                val cfg = File(base.parentFile, base.name.removeSuffix(BASE_SUFFIX))
                runCatching { write(cfg, render(base.readText(), if (useHeap) heapDir else null)) }
                    .onFailure {
                        failed = true
                        System.err.println("Could not write $cfg: $it")
                    }
            }
            println(if (useHeap) "Heap file: $heapDir" else "No heap file (test marker missing or stale)")
            return if (failed) 1 else 0
        } finally {
            marker.delete()
        }
    }

    /**
     * [text] with any heap line removed and, when [heapDir] is set, one added at the top of
     * `[JavaOptions]` (the section is created if missing). Keeps the file's line separator.
     */
    internal fun render(text: String, heapDir: String?): String {
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split(nl).filterNot { it.trim().startsWith(HEAP_LINE) }.toMutableList()
        if (heapDir != null) {
            val line = HEAP_LINE + heapDir
            val section = lines.indexOfFirst { it.trim() == JAVA_OPTIONS_SECTION }
            if (section >= 0) {
                lines.add(section + 1, line)
            } else {
                // Before a trailing empty element, so the file keeps ending with a newline.
                val at = if (lines.lastOrNull() == "") lines.size - 1 else lines.size
                lines.addAll(at, listOf(JAVA_OPTIONS_SECTION, line))
            }
        }
        return lines.joinToString(nl)
    }

    /** Through a temp file and a rename, so the launcher never reads a half-written `.cfg`. */
    private fun write(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
