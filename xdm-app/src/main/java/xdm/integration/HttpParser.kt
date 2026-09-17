package xdm.integration

import okhttp3.internal.toLongOrDefault
import xdm.core.util.Logger
import xdm.core.util.StringUtils
import xdm.integration.LineReader.readLine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.TreeMap
import kotlin.math.min

object HttpParser {
    /** Returns (method, path, version) of a request line such as "POST /download HTTP/1.1". */
    private fun parseRequestStatusLine(statusLine: String): Triple<String, String, String> {
        val arr = statusLine.split(" ")
        if (arr.size > 2) {
            return Triple(arr[0].uppercase(), arr[1], arr[2].uppercase())
        }
        throw IOException("Invalid HTTP status line: $statusLine")
    }

    private fun parseHeader(headerLine: String): Pair<String, String> {
        val index = headerLine.indexOf(":")
        if (index > 0) {
            return Pair(headerLine.substring(0, index).trim(), headerLine.substring(index + 1).trim())
        }
        throw IOException("Invalid header")
    }

    /** Header lookups ignore case (headers are stored in a case-insensitive map). */
    private fun parseContentLength(headers: Map<String, List<String>>): Long =
        headers["Content-Length"]?.firstOrNull()?.toLongOrDefault(-1) ?: -1

    /**
     * HTTP/1.1 connections are persistent unless the client sends `Connection: close`; HTTP/1.0 ones
     * only when it sends `Connection: keep-alive`. Header values are compared ignoring case.
     */
    private fun shouldKeepAlive(version: String, headers: Map<String, List<String>>): Boolean {
        val tokens = headers["Connection"].orEmpty().flatMap { it.split(",") }.map { it.trim().lowercase() }
        return if (version == "HTTP/1.1") "close" !in tokens else "keep-alive" in tokens
    }

    /**
     * Thrown when the client closes the connection before sending another request: a normal end of a
     * kept-alive connection, not an error.
     */
    class ConnectionClosedException : IOException("Connection closed by client")

    fun parseContext(socket: Socket): RequestContext {
        var method = "GET"
        var path = "/"
        var version = "HTTP/1.0"
        val headers: MutableMap<String, MutableList<String>> = TreeMap(String.CASE_INSENSITIVE_ORDER)
        var body: ByteArray? = null
        val io = socket.getInputStream()
        var first = true

        while (true) {
            val line = if (first) readFirstLine(io) else readLine(io)
            if (StringUtils.isNullOrEmpty(line)) {
                if (first) continue // tolerate blank lines before a request line
                break
            }
            if (first) {
                val (m, p, v) = parseRequestStatusLine(line)
                method = m
                path = p
                version = v
                first = false
                continue
            }

            val (key, value) = parseHeader(line)
            //Merge with any existing key
            val values = headers.getOrDefault(key, ArrayList())
            values.add(value)
            headers[key] = values
        }

        val contentLength = parseContentLength(headers)
        if (contentLength > 0) {
            ByteArrayOutputStream().use { baos ->
                copyTo(io, baos, contentLength)
                body = baos.toByteArray()
            }
        }

        return RequestContext(method, path, headers, body, socket, shouldKeepAlive(version, headers))
    }

    /** Reads a request's first line; end of stream before any byte means the client closed the connection. */
    private fun readFirstLine(io: InputStream): String = when (val b = io.read()) {
        -1 -> throw ConnectionClosedException()
        '\n'.code -> ""
        '\r'.code -> readLine(io)
        else -> b.toChar() + readLine(io)
    }


    private fun copyTo(source: InputStream, destination: OutputStream, limit: Long) {
        var rem = limit
        val buffer = ByteArray(8192)
        while (rem > 0) {
            val read = source.read(buffer, 0, min(buffer.size.toLong(), rem).toInt())
            if (read == -1) break
            destination.write(buffer, 0, read)
            rem -= read.toLong()
        }
    }
}
