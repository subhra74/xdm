package xdm.core.network.http.impl

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.hc.core5.http.HttpHeaders
import xdm.core.network.http.HeaderCollection
import xdm.core.network.http.HttpResponse
import xdm.core.network.http.PoolingHttpClient
import xdm.core.network.http.Range
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.function.Function

public class HttpClientImpl(poolSize: Int) : PoolingHttpClient {
    private val dispatcher: Dispatcher = Dispatcher().apply {
        maxRequests = poolSize
        maxRequestsPerHost = poolSize
    }
    private val connectionPool: ConnectionPool = ConnectionPool(poolSize, 5, TimeUnit.SECONDS)
    private val client: OkHttpClient = OkHttpClient.Builder().dispatcher(dispatcher).connectionPool(connectionPool)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override fun close() {
        client.dispatcher.cancelAll()
        dispatcher.executorService.shutdownNow()
        connectionPool.evictAll()
    }

    override fun getResponse(url: String, headers: HeaderCollection?, cookie: String?, range: Range?): HttpResponse {
        val requestBuilder = Request.Builder().url(url).get()
        val cookies: MutableSet<String> = LinkedHashSet()

        headers?.all?.forEach {
            if (it.name.lowercase() == "cookie") {
                cookies.add(it.value)
            } else {
                requestBuilder.addHeader(it.name, it.value)
            }
        }

        if (range != null) {
            if (range.end!! <= 0) {
                requestBuilder.addHeader(HttpHeaders.RANGE, String.format("bytes=%d-", range.start))
            } else {
                requestBuilder.addHeader(
                    HttpHeaders.RANGE, String.format("bytes=%d-%d", range.start, range.end)
                )
            }
        } else {
            requestBuilder.addHeader(HttpHeaders.RANGE, "bytes=0-")
        }

        cookie?.let { cookies.add(it) }

        if (cookies.isNotEmpty()) {
            requestBuilder.addHeader(HttpHeaders.COOKIE, cookies.joinToString(";"))
        }
        val request = requestBuilder.build()
        val response = client.newCall(request).execute()
        val body = response.body ?: run {
            response.close()
            throw IOException("Body missing")
        }
        val inputStreamBody = body.byteStream()

        val finalUrl = response.request.url.toUri()
        val isRedirected = response.priorResponse?.isRedirect ?: false

        return HttpResponseImpl().apply {
            statusCode = response.code
            statusMessage = response.message
            contentLength = body.contentLength()
            contentType = body.contentType()?.type
            contentDisposition = HttpHeaders.CONTENT_DISPOSITION
            lastModified = LocalDateTime.now()
            this.inputStream = inputStreamBody
            this.isRedirected = isRedirected
            this.finalUrl = finalUrl
            closeCallback = Runnable {
                try {
                    inputStream.close()
                } catch (ex: Exception) {
                    // Swallow error
                }
                try {
                    body.close()
                } catch (ex: Exception) {
                    // Swallow error
                }
                try {
                    response.close()
                } catch (ex: Exception) {
                    // Swallow error
                }
            }
            headerCallback = Function { name: String ->
                response.header(
                    name
                )
            }
        }
    }
}