package xdm.core.network.http

typealias HeaderMap = Map<String, List<String>>

interface PoolingHttpClient {
    fun close()
    /** A null [range] sends no `Range` header at all, as a browser does for a plain fetch. */
    fun getResponse(url: String, headers: HeaderMap?, cookie: String?, range: Range?): Result<HttpResponse>
}
