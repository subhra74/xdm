package xdm.core.network.http

typealias HeaderMap = Map<String, List<String>>

interface PoolingHttpClient {
    fun close()
    fun getResponse(url: String, headers: HeaderMap?, cookie: String?, range: Range): Result<HttpResponse>
}
