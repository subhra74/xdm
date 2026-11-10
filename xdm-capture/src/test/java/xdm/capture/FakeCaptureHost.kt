package xdm.capture

import xdm.core.network.http.CredentialPrompt
import xdm.core.network.http.HttpAuth
import java.net.Proxy

/** Defaults as the desktop ships them; no proxy, no prompts. */
class FakeCaptureConfig(override val tempFolder: String = System.getProperty("java.io.tmpdir")) : CaptureConfig {
    override var speedLimit = 0
    override var speedLimiterEnabled = false
    override var maxSegments = 8
    override var maxRetries = 10
    override var useProxy = false
    override var socksProxy = false
    override var proxyHost = ""
    override var proxyPort = 0
    override var proxyUser = ""
    override var proxyPass = ""
    override val minVideoSize = 1024L
    override val skipDuplicateManifests = false
    override val videoExtensions = MediaFilter.DEFAULT_VIDEO_EXTENSIONS
    override val blockedHosts = MediaFilter.DEFAULT_BLOCKED_HOSTS
    override var detectSubtitles = true
    override fun toProxy(): Proxy? = null
}

/** A [CaptureHost] for tests: saves to [folder], counts list changes. */
class FakeCaptureHost(
    override val config: CaptureConfig = FakeCaptureConfig(),
    private val folder: String = System.getProperty("java.io.tmpdir"),
) : CaptureHost {
    @Volatile
    var changes = 0

    override val httpAuth = HttpAuth(CredentialPrompt { _, _ -> null })

    override fun destination(fileName: String?, url: String?, pageUrl: String?): Pair<String?, String> = null to folder

    override fun videoListChanged() {
        changes++
    }

    override fun offerVideo(videoId: Long, fileName: String, size: Long, contentType: String?) {}

    companion object {
        /** Installs a fresh host (and [tracker]) in [CaptureContext]. */
        fun install(tracker: ICapturedVideoTracker = CapturedVideoTracker()): FakeCaptureHost =
            FakeCaptureHost().also {
                CaptureContext.host = it
                CaptureContext.tracker = tracker
            }
    }
}
