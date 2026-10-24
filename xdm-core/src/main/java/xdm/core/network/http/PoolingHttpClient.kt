package xdm.core.network.http

typealias HeaderMap = Map<String, List<String>>

interface PoolingHttpClient {
    fun close()

    /**
     * A null [range] sends no `Range` header at all, as a browser does for a plain fetch.
     *
     * [credentialsFor] is the URL the [headers] and [cookie] were captured for (the link the browser
     * fetched, the manifest a segment was listed in); null means [url] itself. They reach [url], and
     * every redirect after it, only as far as [CredentialPolicy] allows from that origin.
     */
    fun getResponse(
        url: String, headers: HeaderMap?, cookie: String?, range: Range?, credentialsFor: String? = null,
    ): Result<HttpResponse>
}
