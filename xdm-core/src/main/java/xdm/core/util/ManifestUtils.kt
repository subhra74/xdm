//package xdm.core.util
//
//
//import xdm.core.network.http.HeaderCollection
//import xdm.core.network.http.PoolingHttpClient
//import java.io.File
//import java.io.FileOutputStream
//import java.io.IOException
//import java.nio.file.Files
//import java.nio.file.Paths
//import java.util.UUID
//import java.util.concurrent.atomic.AtomicBoolean
//
//object ManifestUtils {
//    fun downloadManifestBytes(
//        httpClient: PoolingHttpClient,
//        url: String,
//        headers: HeaderCollection?,
//        cookie: String?,
//        stopFlag: AtomicBoolean
//    ): ByteArray? {
//        downloadManifest(
//            httpClient,
//            url,
//            headers,
//            cookie,
//            stopFlag
//        )?.let { return Files.readAllBytes(Paths.get(it)) }
//        return null
//    }
//
//    fun downloadManifest(
//        httpClient: PoolingHttpClient,
//        url: String,
//        headers: HeaderCollection?,
//        cookie: String?,
//        stopFlag: AtomicBoolean
//    ): String? {
//        try {
//            while (!stopFlag.get()) {
//                try {
//                    httpClient.getResponse(
//                        url, headers, cookie, null
//                    ).use { response ->
//                        if (stopFlag.get()) return null
//                        val code: Int = response.statusCode
//                        if (code != 200 && code != 206) {
//                            Logger.error("Manifest download failed")
//                            return null
//                        }
//                        val file = File.createTempFile(
//                            UUID.randomUUID().toString(),
//                            ".m3u8"
//                        )
//                        FileOutputStream(
//                            file
//                        ).use { w ->
//                            val b = ByteArray(8192)
//                            response.inputStream.use { inputStream ->
//                                while (!stopFlag.get()) {
//                                    val x: Int = inputStream.read(b)
//                                    if (x == -1) break
//                                    w.write(b, 0, x)
//                                }
//                                return file.absolutePath
//                            }
//                        }
//                    }
//                } catch (ex: IOException) {
//                    Logger.error("Error downloading manifest", ex)
//                    Thread.sleep(3000)
//                }
//            }
//        } catch (ex: InterruptedException) {
//            Logger.error("Thread interrupted", ex)
//            Thread.currentThread().interrupt()
//        } catch (ex: Exception) {
//            Logger.error("Error downloading manifest", ex)
//        }
//        return null
//    }
//}