package xdm.core.network.http.impl

import okhttp3.*
import xdm.core.network.http.*
import xdm.core.util.Logger
import java.io.IOException
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class HttpClientImpl(poolSize: Int, proxy: Proxy? = null) : PoolingHttpClient {
    private val dispatcher: Dispatcher = Dispatcher().apply {
        maxRequests = poolSize
        maxRequestsPerHost = poolSize
    }

    var trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return arrayOf()
        }
    })

    val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    private val connectionPool: ConnectionPool = ConnectionPool(poolSize, 5, TimeUnit.SECONDS)
    private val client: OkHttpClient =
        OkHttpClient.Builder().dispatcher(dispatcher).connectionPool(connectionPool)
            .proxy(proxy)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier({ a, b -> true })
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

            val finalUrl = response.request.url.toUri().toASCIIString()
            val redirected = response.priorResponse?.isRedirect ?: false

            Logger.info(response.headers)

            HttpResponseImpl(
                contentDisposition = response.headers.get("Content-Disposition"),
                statusCode = response.code,
                statusMessage = response.message,
                contentLength = if (body.contentLength() > 0) body.contentLength() else null,
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
                })
        }
    }
}