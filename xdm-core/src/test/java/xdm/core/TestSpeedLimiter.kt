package xdm.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.core.downloaders.web.SpeedLimiter

/** [SpeedLimiter] on its own, without a download. */
class TestSpeedLimiter {

    /**
     * The limit is turned off, a lot is downloaded, and it is turned on again (Settings applies it to
     * running downloads). The first throttle after that must measure from then on, not sleep for
     * everything downloaded while the limit was off: minutes here, during which every segment waits.
     */
    @Test
    fun reenabled_doesNotSleepForBytesDownloadedWhileOff() {
        val config = TestConfig(maxSegments = 1).apply {
            speedLimiterEnabled = true
            speedLimit = 100 // KB/s
        }
        val limiter = SpeedLimiter(config)
        limiter.throttleIfNeeded(64 * 1024L)
        config.speedLimiterEnabled = false
        limiter.throttleIfNeeded(64L * 1024 * 1024)
        Thread.sleep(50) // time passes while the limit is off
        config.speedLimiterEnabled = true

        val call = Thread { limiter.throttleIfNeeded(64L * 1024 * 1024 + 64 * 1024) }.apply {
            isDaemon = true
            start()
        }
        call.join(3000)
        val stuck = call.isAlive
        limiter.disable() // releases a sleeping call either way
        assertTrue(!stuck, "the first throttle after turning the limit back on slept for what was downloaded while off")
    }
}
