package xdm.app

import java.util.Locale

/**
 * Picks the category a browser-captured download is preselected into (docs/download-categories.md §3.2).
 *
 * - Patterns are case-insensitive globs: `*` is any run of characters, `?` one character. A pattern with
 *   no wildcard matches anywhere in the value ("contains"); one with a wildcard must match the whole value.
 * - URL patterns are tried against both the download URL and the page it came from.
 * - A category matches with Any (one pattern of any kind) or All (every non-empty kind) semantics.
 * - When several match, the most specific kind that matched wins (URL > file name > extension), then
 *   list order.
 *
 * Compiled patterns are cached per category list instance: the config replaces the list as a whole on
 * every change, so a new instance means the snapshot is rebuilt, and readers on any thread see either
 * the old snapshot or the new one.
 */
object CategoryRules {
    private enum class Tier { EXTENSION, FILE_NAME, URL }

    private class Compiled(
        val category: DownloadCategory,
        val urls: List<Glob>,
        val names: List<Glob>,
    )

    private class Snapshot(val source: List<DownloadCategory>, val compiled: List<Compiled>)

    @Volatile
    private var snapshot: Snapshot? = null

    /** The category [fileName] / [url] / [pageUrl] belongs to in [categories], or null for None. */
    fun match(
        fileName: String?,
        url: String?,
        pageUrl: String?,
        categories: List<DownloadCategory> = AppContext.config.categories,
    ): DownloadCategory? {
        var best: Pair<Tier, DownloadCategory>? = null
        for (c in compiled(categories)) {
            val tier = matchTier(c, fileName, url, pageUrl) ?: continue
            // Strictly greater: within a tier the first category in list order keeps the match.
            if (best == null || tier > best.first) best = tier to c.category
        }
        return best?.second
    }

    /** The most specific kind through which [c] matches, or null if it does not. */
    private fun matchTier(c: Compiled, fileName: String?, url: String?, pageUrl: String?): Tier? {
        val name = fileName?.takeIf { it.isNotBlank() }
        val ext = c.category.extensions.isNotEmpty() && name != null && c.category.matches(name)
        val byName = c.names.isNotEmpty() && name != null && c.names.any { it.matches(name) }
        val byUrl = c.urls.isNotEmpty() && listOfNotNull(url, pageUrl).any { u -> c.urls.any { it.matches(u) } }
        if (c.category.matchAll) {
            if (c.category.extensions.isNotEmpty() && !ext) return null
            if (c.names.isNotEmpty() && !byName) return null
            if (c.urls.isNotEmpty() && !byUrl) return null
        }
        return when {
            byUrl -> Tier.URL
            byName -> Tier.FILE_NAME
            ext -> Tier.EXTENSION
            else -> null
        }
    }

    private fun compiled(categories: List<DownloadCategory>): List<Compiled> {
        snapshot?.takeIf { it.source === categories }?.let { return it.compiled }
        val fresh = categories.map { c ->
            Compiled(c, c.urlPatterns.mapNotNull { Glob.of(it) }, c.fileNamePatterns.mapNotNull { Glob.of(it) })
        }
        snapshot = Snapshot(categories, fresh)
        return fresh
    }

    /** A compiled pattern. Matching is linear in the value's length, so no pattern can stall the caller. */
    class Glob private constructor(private val pattern: String, private val contains: Boolean) {
        fun matches(value: String): Boolean {
            val v = value.lowercase(Locale.ROOT)
            return if (contains) v.contains(pattern) else wildcardMatch(pattern, v)
        }

        companion object {
            /** Null for a blank pattern, which matches nothing. */
            fun of(raw: String): Glob? {
                val p = raw.trim().lowercase(Locale.ROOT)
                if (p.isEmpty()) return null
                return Glob(p, contains = '*' !in p && '?' !in p)
            }

            /** Greedy wildcard match with backtracking to the last `*`: O(pattern × value) worst case. */
            private fun wildcardMatch(p: String, v: String): Boolean {
                var pi = 0
                var vi = 0
                var star = -1
                var mark = 0
                while (vi < v.length) {
                    when {
                        pi < p.length && (p[pi] == '?' || p[pi] == v[vi]) -> { pi++; vi++ }
                        pi < p.length && p[pi] == '*' -> { star = pi++; mark = vi }
                        star >= 0 -> { pi = star + 1; vi = ++mark }
                        else -> return false
                    }
                }
                while (pi < p.length && p[pi] == '*') pi++
                return pi == p.length
            }
        }
    }
}
