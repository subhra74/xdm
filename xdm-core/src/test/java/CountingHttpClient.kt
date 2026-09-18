import xdm.core.network.http.HeaderMap
import xdm.core.network.http.HttpResponse
import xdm.core.network.http.PoolingHttpClient
import xdm.core.network.http.Range
import xdm.core.network.http.impl.HttpClientImpl
import java.util.concurrent.atomic.AtomicInteger

/** Real [HttpClientImpl] that records how many times the downloader closed it. */
class CountingHttpClient(private val delegate: HttpClientImpl = HttpClientImpl(8)) : PoolingHttpClient {
    val closeCount = AtomicInteger(0)

    override fun close() {
        closeCount.incrementAndGet()
        delegate.close()
    }

    override fun getResponse(url: String, headers: HeaderMap?, cookie: String?, range: Range): Result<HttpResponse> =
        delegate.getResponse(url, headers, cookie, range)
}

/**
 * A real client with the given read timeout. Uses the `readTimeoutSeconds` constructor parameter
 * when it exists; otherwise (code before B11) the client has no read timeout at all.
 */
fun httpClientWithReadTimeout(seconds: Int): HttpClientImpl {
    val ctor = HttpClientImpl::class.java.constructors.firstOrNull {
        it.parameterCount == 4 && it.parameterTypes[3] == Int::class.javaPrimitiveType
    }
    return ctor?.newInstance(8, null, false, seconds) as HttpClientImpl? ?: HttpClientImpl(8)
}
