package xdm.app

import kotlinx.serialization.Serializable

@Serializable
data class AppConfigData(
    var tempDir: String,
    var fileExtList: List<String> = mutableListOf(
        "3GP", "7Z", "AVI", "BZ2", "DEB", "DOC", "DOCX", "EXE", "ISO",
        "MSI", "PDF", "PPT", "PPTX", "RAR", "RPM", "XLS", "XLSX", "SIT", "SITX", "TAR", "JAR", "ZIP", "XZ"
    ),
    var blockedHosts: List<String> = mutableListOf("update.microsoft.com", "windowsupdate.com", "thwawte.com"),
    var videoExtList: List<String> = mutableListOf(
        "MP4", "M3U8", "F4M", "WEBM", "OGG", "MP3", "AAC", "FLV", "MKV", "DIVX",
        "MOV", "MPG", "MPEG", "OPUS", "MPD"
    )
)