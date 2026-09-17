package xdm.core.util


import xdm.core.network.http.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object ManifestUtils {
    fun downloadManifestBytes(
        httpClient: PoolingHttpClient,
        url: String,
        headers: HeaderMap?,
        cookie: String?,
        stopFlag: AtomicBoolean,
        onError: ((Throwable) -> Unit)? = null,
    ): ByteArray? {
        downloadManifestAsFile(
            httpClient,
            url,
            headers,
            cookie,
            stopFlag,
            onError
        )?.let {
            val tempFile = Paths.get(it);
            val bytes = Files.readAllBytes(tempFile);
            try {
                Files.delete(tempFile)
                return bytes
            } catch (e: Exception) {
                //Nothing to do
            }
        }
        return null
    }

    fun downloadManifestAsFile(
        httpClient: PoolingHttpClient,
        url: String,
        headers: HeaderMap?,
        cookie: String?,
        stopFlag: AtomicBoolean,
        onError: ((Throwable) -> Unit)? = null,
    ): String? {
        try {
            Logger.info("XDM","Downloading manifest: $url")
            httpClient.getResponse(
                url, headers, cookie, Range(start = 0)
            ).onSuccess {
                it.use { response ->
                    if (stopFlag.get()) return null
                    val code: Int = response.statusCode
                    Logger.info("Manifest download", "Return code: $code")
                    if (code != 200 && code != 206) {
                        Logger.error("Manifest download failed")
                        return null
                    }
                    return fileCopy(response, stopFlag)
                }
            }.onFailure {
                Logger.error("Error downloading manifest", it)
                onError?.invoke(it)
            }
        } catch (ex: IOException) {
            Logger.error("Error downloading manifest", ex)
            onError?.invoke(ex)
        }
        return null
    }

    private fun fileCopy(response: HttpResponse, stopFlag: AtomicBoolean): String {
        val file = File.createTempFile(
            UUID.randomUUID().toString(),
            ".m3u8"
        )
        FileOutputStream(
            file
        ).use { w ->
            val b = ByteArray(8192)
            response.inputStream.use { inputStream ->
                while (!stopFlag.get()) {
                    val x: Int = inputStream.read(b)
                    if (x == -1) break
                    w.write(b, 0, x)
                }
                return file.absolutePath
            }
        }
    }
}