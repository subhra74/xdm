package xdm.core.util

fun getFileExtFromUrl(url: String): String? {
    val fileName = FileUtils.getFileName(url)
    if (fileName != null) {
        return XDMUtils.getExtension(fileName)
    }
    return null
}