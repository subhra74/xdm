package xdm.core.util

fun getFileExtFromUrl(url: String): String? {
    val fileName = FileUtils.getFileName(url)
    return getExtension(fileName)
}

fun getExtension(file: String): String? {
    val index = file.lastIndexOf(".")
    return if (index > 0) {
        file.substring(index)
    } else {
        null
    }
}

fun getFileNameWithoutExtension(name: String): String {
    val index = name.lastIndexOf(".")
    return if (index > 0) {
        name.substring(0, index)
    } else {
        return name
    }
}