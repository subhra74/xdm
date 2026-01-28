package xdm.core.network.http.impl

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import xdm.core.network.http.*
import xdm.core.util.Logger
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class HttpClientImpl(poolSize: Int) : PoolingHttpClient {
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
        Thread {
            Logger.info("XDM", "Trying connection pool clean up..")
            dispatcher.executorService.awaitTermination(Int.MAX_VALUE.toLong(), TimeUnit.HOURS)
            Logger.info("XDM", "Connection pool clean up.. triggering GC")
            System.gc()
        }.start()
    }

    override fun getResponse(url: String, headers: HeaderMap?, cookie: String?, range: Range): Result<HttpResponse> {
        val requestBuilder = Request.Builder().url(url).get()
        val cookies: MutableSet<String> = LinkedHashSet()

        headers?.forEach { (name, value) ->
            if (name.lowercase() == "cookie" && value.isNotEmpty()) {
                cookies.add(value.first())
            } else {
                for (headerValue in value) {
                    requestBuilder.addHeader(name, headerValue)
                }
            }
        }

        val end = range.end ?: 0
        if (end <= 0) {
            requestBuilder.addHeader("Range", String.format("bytes=%d-", range.start))
        } else {
            requestBuilder.addHeader(
                "Range", String.format("bytes=%d-%d", range.start, range.end)
            )
        }

        cookie?.let { cookies.add(it) }
        if (cookies.isNotEmpty()) {
            requestBuilder.addHeader("Cookie", cookies.joinToString(";"))
        }

        return kotlin.runCatching {
            val request = requestBuilder.build()
            val response = client.newCall(request).execute()
            val body = response.body ?: run {
                response.close()
                throw IOException("Body missing")
            }
            val inputStreamBody = body.byteStream()
//            val inputSource = body.source()

            val finalUrl = response.request.url.toUri().toASCIIString()
            val redirected = response.priorResponse?.isRedirect ?: false

            HttpResponseImpl(
                contentDisposition =
                    response.headers.get("Content-Disposition"),
                statusCode = response.code,
                statusMessage = response.message,
                contentLength = body.contentLength(),
                contentType = body.contentType()?.type,
                lastModified = LocalDateTime.now(),
                inputStream = inputStreamBody,
                isRedirected = redirected,
                finalUrl = finalUrl,
                closeCallback = {
                    try {
                        inputStreamBody.close()
                    } catch (ex: Exception) {// Swallow error
                    }
                    try {
                        body.close()
                    } catch (ex: Exception) {// Swallow error
                    }
                    try {
                        response.close()
                    } catch (ex: Exception) {// Swallow error
                    }
                },
                headerCallback = {
                    response.header(
                        it
                    )
                }
            )
        }
    }
}