package xdm.app.service

interface PlatformService {
    fun runVirusScan(file: String)

    fun runCustomCommand(file: String)

    fun shutdownPC()
}
