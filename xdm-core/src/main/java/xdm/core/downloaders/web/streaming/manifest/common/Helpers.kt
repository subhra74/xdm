package xdm.core.downloaders.web.streaming.manifest.common

import java.net.URI

fun resolveUri(baseUrl: URI, url: String): URI {
    return baseUrl.resolve(url.replace("\"", "").replace("'", ""))
}