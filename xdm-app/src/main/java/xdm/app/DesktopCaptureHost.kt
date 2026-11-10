package xdm.app

import xdm.app.utils.captureDestination
import xdm.capture.CaptureConfig
import xdm.capture.CaptureHost
import xdm.core.network.http.CertTrust
import xdm.core.network.http.HttpAuth
import xdm.integration.EventChannel

/** Media detection on the desktop: the app's config and category rules, the extension's event channel. */
object DesktopCaptureHost : CaptureHost {
    override val config: CaptureConfig
        get() = AppContext.config

    override val httpAuth: HttpAuth
        get() = AppContext.httpAuth

    /** No checks at all while the user has "Ignore certificate errors" on. */
    override fun insecureTrust(): CertTrust? = InsecureTrust.ifEnabled(AppContext.config.ignoreCertErrors)

    override fun destination(fileName: String?, url: String?, pageUrl: String?): Pair<String?, String> =
        captureDestination(fileName, url, pageUrl)

    /** Wakes the extension's long polls, so the popup's video list follows. */
    override fun videoListChanged() = EventChannel.notifyChanged()

    override fun offerVideo(videoId: Long, fileName: String, size: Long, contentType: String?) =
        AppContext.app.addVideoDownload(videoId, fileName, size, contentType)
}
