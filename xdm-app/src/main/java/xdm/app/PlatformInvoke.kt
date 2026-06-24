package xdm.app

import xdm.app.utils.initShutdown
import xdm.core.util.Logger

interface IPlatformInvoke {
    fun runVirusScan(file: String)
    fun runCustomCommand(file: String)
    fun shutdownPC()
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
        command.addAll(tokenize(config.virusScannerArgs))
        command.add(file)
        launch(command, "antivirus scan")
    }

    override fun runCustomCommand(file: String) {
        val template = AppContext.config.customCommand.trim()
        if (template.isEmpty()) {
            Logger.info("Run command enabled but no command configured")
            return
        }
        val tokens = tokenize(template)
        // Substitute the %file% placeholder if present, otherwise append the file as an argument.
        val command = if (tokens.any { it.contains(FILE_TOKEN) }) {
            tokens.map { it.replace(FILE_TOKEN, file) }
        } else {
            tokens + file
        }
        launch(command, "custom command")
    }

    override fun shutdownPC() {
        Logger.info("Initiating shutdown after all downloads")
        initShutdown()
    }

    private fun launch(command: List<String>, what: String) {
        if (command.isEmpty()) return
        try {
            Logger.info("Running $what: $command")
            ProcessBuilder(command).inheritIO().start()
        } catch (e: Exception) {
            Logger.error("Failed to run $what", e)
        }
    }

    /** Splits a command line into tokens, honouring double-quoted segments. */
    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in input.trim()) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch.isWhitespace() && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.setLength(0)
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    companion object {
        private const val FILE_TOKEN = "%file%"
    }
}
