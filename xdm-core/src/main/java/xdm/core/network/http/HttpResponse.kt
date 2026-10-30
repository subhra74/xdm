// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.network.http

import java.io.InputStream
import java.net.URI

interface HttpResponse : AutoCloseable {
    override fun close()

    /**
     * Aborts the response from another thread: a read blocked on its body fails with an IOException. The
     * thread reading it still closes it. Unlike [close], safe while another thread is reading.
     */
    fun cancel() = close()
    val statusCode: Int
    val statusMessage: String
    val contentLength: Long?
    val contentDisposition: String?
    val contentType: String?
    /** The Last-Modified header as epoch milliseconds, or null if absent or unparseable. */
    val lastModifiedMillis: Long?
    val inputStream: InputStream
    val isRedirected: Boolean
    val finalUrl: String?
    fun getHeader(name: String): String?
}

class HttpResponseImpl(
    override val contentDisposition: String?,
    override val statusCode: Int,
    override val statusMessage: String,
    override val contentLength: Long?,
    override val contentType: String?,
    override val lastModifiedMillis: Long?,
    override val inputStream: InputStream,
    override val isRedirected: Boolean,
    override val finalUrl: String?,
    private val closeCallback: () -> Unit,
    private val headerCallback: (String) -> String?,
    private val cancelCallback: (() -> Unit)? = null,
) : HttpResponse {
    override fun close() {
        closeCallback()
    }

    override fun cancel() {
        cancelCallback?.invoke() ?: close()
    }

    override fun getHeader(name: String): String? = headerCallback(name)
}
