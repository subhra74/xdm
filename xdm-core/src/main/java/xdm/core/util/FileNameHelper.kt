package xdm.core.util

fun getFileExtFromUrl(url: String): String? {
    val fileName = FileUtils.getFileName(url)
    if (fileName != null) {
        return XDMUtils.getExtension(fileName)
    }
    return null
}

fun getExtension(file: String): String? {
    val index = file.lastIndexOf(".")
    return if (index > 0) {
        file.substring(index)
    } else {
        null
    }
}