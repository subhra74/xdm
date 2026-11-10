package xdm.app

import xdm.app.utils.OriginMarker
import xdm.app.utils.initShutdown
import xdm.core.downloaders.DownloadType
import xdm.core.util.Logger
import xdm.manager.DownloadSource
import java.io.File

interface IPlatformInvoke {
    fun runVirusScan(file: String)
    /**
     * Runs the command of the first [IAppConfig.commandRules] entry matching [path], else the catch-all
     * custom command if it is on. [path] is the file, or for a [DownloadType.Batch] the folder.
     */
    fun runCustomCommand(path: String, type: DownloadType)
    fun shutdownPC()

    /** Marks [file] as downloaded from the internet (see [OriginMarker]). Blocking; never throws. */
    fun markDownloadedFile(file: File, source: DownloadSource): Boolean
}

class PlatformInvoke : IPlatformInvoke {
    override fun runVirusScan(file: String) {
        val config = AppContext.config
        val scanner = config.virusScannerPath.trim()
        if (scanner.isEmpty()) {
            Logger.info("Antivirus scan enabled but no scanner configured")
            return
        }
        val command = mutableListOf(scanner)
        command.addAll(PostDownloadCommand.tokenize(config.virusScannerArgs))
        command.add(file)
        launch(command, "antivirus scan")
    }

    override fun runCustomCommand(path: String, type: DownloadType) {
        val config = AppContext.config
        val template = PostDownloadCommand.pick(
            config.commandRules, config.runCommand, config.customCommand, path, type
        ) ?: return
        launch(PostDownloadCommand.expand(template, path, type), "custom command")
    }

    override fun shutdownPC() {
        Logger.info("Initiating shutdown after all downloads")
        initShutdown()
    }

    override fun markDownloadedFile(file: File, source: DownloadSource): Boolean = OriginMarker.mark(file, source)

    private fun launch(command: List<String>, what: String) {
        if (command.isEmpty()) return
        try {
            Logger.info("Running $what: $command")
            ProcessBuilder(command).inheritIO().start()
        } catch (e: Exception) {
            Logger.error("Failed to run $what", e)
        }
    }
}
