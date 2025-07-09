//package xdm.core.downloaders.http
//
//
//import kotlinx.serialization.ExperimentalSerializationApi
//import kotlinx.serialization.json.Json
//import kotlinx.serialization.json.decodeFromStream
//import kotlinx.serialization.json.encodeToStream
//import xdm.core.util.Logger
//import java.io.File
//import java.io.FileInputStream
//import java.io.FileOutputStream
//
//object HttpStateWriter {
//    @OptIn(ExperimentalSerializationApi::class)
//    fun write(state: HttpState) {
//        try {
//            val file = File(state.tempFolder, "${state.id}-state.dat")
//            val tmpFile = File(state.tempFolder, "${state.id}-state.bak")
//            FileOutputStream(tmpFile).use {
//                Json.encodeToStream(state, it)
//            }
//            file.delete()
//            tmpFile.renameTo(file)
//        } catch (ex: Exception) {
//            Logger.log(ex)
//        }
//    }
//
//    @OptIn(ExperimentalSerializationApi::class)
//    fun read(id: Long, tempFolder: String): HttpState? {
//        try {
//            var file = File(tempFolder, "${id}-state.dat")
//            if (!file.exists()) {
//                file = File(tempFolder, "${id}-state.bak")
//            }
//            if (file.exists()) {
//                FileInputStream(file).use {
//                    return Json.decodeFromStream(it)
//                }
//            }
//        } catch (ex: Exception) {
//            Logger.log(ex)
//        }
//        return null
//    }
//}