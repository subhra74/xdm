package xdm.app

interface IPlatformInvoke {
    fun runVirusScan(file: String)
    fun runCustomCommand(file: String)
    fun shutdownPC()
}

class PlatformInvoke : IPlatformInvoke {
    override fun runVirusScan(file: String) {}

    override fun runCustomCommand(file: String) {}

    override fun shutdownPC() {}
}
