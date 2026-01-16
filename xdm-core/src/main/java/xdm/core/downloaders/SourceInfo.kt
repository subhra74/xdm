package xdm.core.downloaders

sealed interface SourceInfo {
    data class HttpSourceInfo(
        val id: Long,
        val url: String,
        val fileName: String,
        val headers: Map<String, List<String>>?,
        val cookie: String?,
        val contentType: String?,
        val selectFolderByFileType: Boolean, // Download folder is set manually as opposed to category based onev
        val folder: String,
        val dateAdded: Long,
        val originPage: String?,
        val autoSelectExt: Boolean, // Change file extension in case of redirection but keep file name same
        val fileSize: Long?,
    ) : SourceInfo

    data class HlsSourceInfo(
        val id: Long,
        val url: String,
        val fileName: String,
        val headers: Map<String, List<String>>?,
        val cookie: String?,
        val contentType: String?,
        val selectFolderByFileType: Boolean, // Download folder is set manually as opposed to category based onev
        val folder: String,
        val dateAdded: Long,
        val originPage: String?,
        val autoSelectExt: Boolean, // Change file extension in case of redirection but keep file name same
        val fileSize: Long?,
        val audioUrl: String?,
    ) : SourceInfo
}

