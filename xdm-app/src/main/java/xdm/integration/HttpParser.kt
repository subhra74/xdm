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
import kotlin.math.min

object HttpParser {
    /** Returns the (method, path) of an HTTP request line such as "POST /download HTTP/1.1". */
    private fun parseRequestStatusLine(statusLine: String): Pair<String, String> {
        try {
            val arr = statusLine.split(" ")
            if (arr.size > 2) {
                return Pair(arr[0].uppercase(), arr[1])
            }
        } catch (ex: Exception) {
            Logger.info(ex)
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

    private fun parseContentLength(headers: Map<String, List<String>>): Long {
        headers["Content-Length"]?.let { return if (it.isNotEmpty()) it[0].toLongOrDefault(-1) else -1 } ?: return -1
    }

    private fun shouldKeepAlive(headers: Map<String, List<String>>): Boolean {
        headers["Connection"]?.let { return it.isNotEmpty() && it[0] == "keep-alive" } ?: return false
    }

    fun parseContext(socket: Socket): RequestContext {
        var method = "GET"
        var path = "/"
        val headers: MutableMap<String, MutableList<String>> = HashMap()
        var body: ByteArray? = null
        val io = socket.getInputStream()
        var first = true

        while (true) {
            val line = readLine(io)
            if (StringUtils.isNullOrEmpty(line)) break
            if (first) {
                val (m, p) = parseRequestStatusLine(line)
                method = m
                path = p
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

        return RequestContext(method, path, headers, body, socket, shouldKeepAlive(headers))
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
