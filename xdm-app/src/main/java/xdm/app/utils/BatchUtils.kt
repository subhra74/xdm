package xdm.app.utils

import xdm.core.util.FileUtils
import xdm.core.util.getExtension
import xdm.core.util.getFileNameWithoutExtension
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Why a batch cannot use a folder, or [Ok]. */
enum class BatchFolderState {
    /** Missing (it will be created) or an empty folder (it will be reused). */
    Ok,

    /** A folder that already holds files. */
    NotEmpty,

    /** A file, not a folder. */
    NotAFolder,
}

fun batchFolderState(folder: File): BatchFolderState = when {
    !folder.exists() -> BatchFolderState.Ok
    !folder.isDirectory -> BatchFolderState.NotAFolder
    folder.list()?.isEmpty() == true -> BatchFolderState.Ok
    else -> BatchFolderState.NotEmpty
}

/** A batch may be saved into [folder]: it is missing or an empty folder. */
fun isUsableBatchFolder(folder: File) = batchFolderState(folder) == BatchFolderState.Ok

private const val MAX_BATCH_NAME = 100

/**
 * The name a new batch starts with: the page title made safe for a folder name, or, without one, the
 * page's host, or `Batch`; then the date (`Gallery Feb-24-2026`, `example.com Feb-24-2026`).
 */
fun defaultBatchName(pageTitle: String?, pageUrl: String?, now: Date = Date()): String {
    val date = SimpleDateFormat("MMM-dd-yyyy", Locale.ENGLISH).format(now)
    val host = pageUrl?.let { runCatching { URI(it).host }.getOrNull() }?.removePrefix("www.")
    val base = sanitizeBatchName(pageTitle) ?: sanitizeBatchName(host) ?: "Batch"
    // Shorten the name, not the date, to stay within MAX_BATCH_NAME.
    return "${base.take(MAX_BATCH_NAME - date.length - 1).trimEnd('.', ' ').ifEmpty { "Batch" }} $date"
}

/** [name] as a folder name (no path separators or reserved names, at most 100 characters), or null if blank. */
fun sanitizeBatchName(name: String?): String? {
    val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val safe = FileUtils.sanitizeFileName(trimmed) ?: return null
    return safe.take(MAX_BATCH_NAME).trimEnd('.', ' ').ifEmpty { null }
}

/** [name], or `name (2)`, `name (3)`… — the first one [parent] can hold as a batch folder. */
fun suggestBatchName(parent: File, name: String): String {
    if (isUsableBatchFolder(File(parent, name))) return name
    var n = 2
    while (!isUsableBatchFolder(File(parent, "$name ($n)"))) n++
    return "$name ($n)"
}

/**
 * Makes [names] unique ignoring case (files in one folder), in order: the first keeps its name,
 * later ones become `name_1.ext`, `name_2.ext`…, the scheme the downloader uses for clashes.
 */
fun uniqueFileNames(names: List<String>): List<String> {
    val taken = HashSet<String>()
    return names.map { name ->
        var candidate = name
        var n = 0
        val base = getFileNameWithoutExtension(name)
        val ext = getExtension(name) ?: ""
        while (!taken.add(candidate.lowercase())) {
            candidate = "${base}_${++n}$ext"
        }
        candidate
    }
}
