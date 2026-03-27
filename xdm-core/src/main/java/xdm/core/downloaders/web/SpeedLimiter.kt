package xdm.core.downloaders.web

import xdm.core.downloaders.DownloadHost
import xdm.core.util.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import kotlin.math.ceil

class SpeedLimiter(val host: DownloadHost) {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock: CountDownLatch = CountDownLatch(1)
    private var lastTick: Long = 0
    private var lastBytes: Long = 0
    private val disabled = AtomicBoolean(false)

    fun throttleIfNeeded(downloaded: Long) {
        val speedLimit = getSpeedLimit()
        if (speedLimit < 1) return
        if (lastBytes == 0L || lastTick == 0L) {
            lastBytes = downloaded
            lastTick = System.currentTimeMillis()
            return
        }
        synchronized(this) {
            try {
                val maxBytesPerMS = speedLimit.toDouble() * 1024 / 1000
                val now = System.currentTimeMillis()
                val actualTimeSpent = now - lastTick
                if (actualTimeSpent < 1) return
                val diff = downloaded - lastBytes
                lastBytes = downloaded
                lastTick = now
                val expectedTimeSpent = diff / maxBytesPerMS

                if (actualTimeSpent < expectedTimeSpent) {
                    sleep(ceil(expectedTimeSpent - actualTimeSpent).toLong())
                }
            } catch (e: Exception) {
                Logger.error(e)
            }
        }
    }

    fun disable() {
        try {
            lock.countDown()
        } catch (e: Exception) {
            Logger.error(e)
        }
        disabled.set(true)
    }

    private fun sleep(interval: Long) {
        try {
            if (!disabled.get() && lock.await(interval, TimeUnit.MILLISECONDS)) {
                Logger.info("Speed limiter wait cancelled!")
            }
        } catch (e: Exception) {
            Logger.error(e)
        }
    }

    private fun getSpeedLimit(): Int {
        if (host.applySpeedLimit && host.speedLimit > 0) {
            return host.speedLimit
        }
        return 0
    }
}