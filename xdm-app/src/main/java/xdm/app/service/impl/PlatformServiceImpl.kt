package xdm.app.service.impl

import xdm.app.service.PlatformService

class PlatformServiceImpl : PlatformService {
    override fun runVirusScan(file: String) {}

    override fun runCustomCommand(file: String) {}

    override fun shutdownPC() {}
}
