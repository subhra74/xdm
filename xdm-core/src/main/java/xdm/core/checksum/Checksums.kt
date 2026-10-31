// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.checksum

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A digest a published checksum can be in, recognised by its hex length or its BSD tag. */
enum class ChecksumAlgorithm(val label: String, val javaName: String, val hexLength: Int, val extension: String) {
    MD5("MD5", "MD5", 32, "md5"),
    SHA1("SHA-1", "SHA-1", 40, "sha1"),
    SHA256("SHA-256", "SHA-256", 64, "sha256"),
    SHA512("SHA-512", "SHA-512", 128, "sha512");

    companion object {
        fun ofHexLength(length: Int): ChecksumAlgorithm? = entries.firstOrNull { it.hexLength == length }

        /** The tag of a BSD-style line (`SHA256 (file) = ...`), with or without the dash. */
        fun ofTag(tag: String): ChecksumAlgorithm? =
            entries.firstOrNull { it.javaName.replace("-", "").equals(tag.replace("-", ""), ignoreCase = true) }
    }
}

/** A hash to compare the file against, lower-case hex. */
data class ExpectedChecksum(val algorithm: ChecksumAlgorithm, val hex: String)

/**
 * Where checksums are conventionally published next to a download.
 *
 * Per-file sidecars append the algorithm to the file URL (`file.iso.sha256`; Maven Central,
 * most GitHub releases, Apache). Directory manifests list every file of a folder in one file
 * (`SHA256SUMS` on Ubuntu/Debian mirrors). The query string is dropped: mirrors publish the
 * sidecar at the bare path, and a signed query for the file rarely covers the sidecar anyway.
 */
object SidecarUrls {
    /** Sidecar for [algorithm], e.g. `https://host/dir/file.iso.sha256`; null if [downloadUrl] has no path. */
    fun sidecar(downloadUrl: String, algorithm: ChecksumAlgorithm): String? =
        basePath(downloadUrl)?.let { "$it.${algorithm.extension}" }

    /** What "Auto" tries, most common first: the per-file sidecars, then the folder's SHA256SUMS. */
    fun autoCandidates(downloadUrl: String): List<String> {
        val base = basePath(downloadUrl) ?: return emptyList()
        val perFile = listOf(ChecksumAlgorithm.SHA256, ChecksumAlgorithm.SHA512, ChecksumAlgorithm.SHA1, ChecksumAlgorithm.MD5)
            .map { "$base.${it.extension}" }
        return perFile + (base.substringBeforeLast('/') + "/SHA256SUMS")
    }

    /** The decoded last path segment: the name a checksum manifest lists the file under. */
    fun remoteFileName(downloadUrl: String): String? =
        downloadUrl.toHttpUrlOrNull()?.pathSegments?.last()?.takeIf { it.isNotEmpty() }

    /** Scheme, host and path without query or fragment; null unless the URL has a file-like path. */
    private fun basePath(downloadUrl: String): String? {
        val url = downloadUrl.toHttpUrlOrNull() ?: return null
        if (url.encodedPath.endsWith("/")) return null
        return url.newBuilder().query(null).fragment(null).build().toString()
    }
}

/**
 * Reads the checksum for one file out of a sidecar or a directory manifest. Understands the
 * formats in common use:
 *  - a bare hash (Maven Central, many GitHub releases), any case, possibly wrapped;
 *  - GNU coreutils lines, `<hex>  name` or `<hex> *name` (`sha256sum`, `SHA256SUMS`);
 *  - BSD lines, `SHA256 (name) = <hex>` (`shasum --tag`, Fedora `*-CHECKSUM`);
 *  - `gpg --print-md`, `name: AB12 CD34 ...` split over several lines (older Apache releases).
 */
object ChecksumParser {
    private val hexRegex = Regex("^[0-9a-fA-F]+$")
    private val bsdLine = Regex("""^([A-Za-z0-9-]+)\s*\((.+)\)\s*=\s*([0-9a-fA-F]+)$""")
    private val gnuLine = Regex("""^\\?([0-9a-fA-F]{32,128})\s+[ *]?(.+)$""")

    private data class Entry(val name: String?, val checksum: ExpectedChecksum)

    /** [text] as a hash on its own (pasted by the user), or null when it is not one. */
    fun parseRawHash(text: String, hint: ChecksumAlgorithm? = null): ExpectedChecksum? {
        val hex = text.trim().filterNot { it.isWhitespace() }
        if (hex.isEmpty() || !hexRegex.matches(hex)) return null
        val algorithm = ChecksumAlgorithm.ofHexLength(hex.length) ?: return null
        if (hint != null && hint != algorithm) return null
        return ExpectedChecksum(algorithm, hex.lowercase())
    }

    /**
     * The checksum in [text] for a file called one of [fileNames] (the saved name and the name in
     * the URL, which differ when XDM renamed a duplicate). A file with a single entry is taken
     * whatever name it gives, since a per-file sidecar often names the file differently; with
     * several entries, only a name match counts. [hint] keeps only entries of that algorithm.
     */
    fun parse(text: String, fileNames: Collection<String>, hint: ChecksumAlgorithm? = null): ExpectedChecksum? {
        val body = text.removePrefix("﻿")
        parseRawHash(body, hint)?.let { return it }

        val entries = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { parseLine(it) }
            .filter { hint == null || it.checksum.algorithm == hint }
            .toList()
            .ifEmpty { listOfNotNull(parseGpgPrintMd(body, hint)) }

        val wanted = fileNames.filter { it.isNotBlank() }
        val byName = entries.firstOrNull { e -> e.name != null && wanted.any { it == baseName(e.name) } }
            ?: entries.firstOrNull { e -> e.name != null && wanted.any { it.equals(baseName(e.name), ignoreCase = true) } }
        return byName?.checksum ?: entries.singleOrNull()?.checksum
    }

    private fun parseLine(line: String): Entry? {
        bsdLine.matchEntire(line)?.let { m ->
            val algorithm = ChecksumAlgorithm.ofTag(m.groupValues[1]) ?: return null
            val hex = m.groupValues[3]
            if (hex.length != algorithm.hexLength) return null
            return Entry(m.groupValues[2], ExpectedChecksum(algorithm, hex.lowercase()))
        }
        gnuLine.matchEntire(line)?.let { m ->
            val hex = m.groupValues[1]
            val algorithm = ChecksumAlgorithm.ofHexLength(hex.length) ?: return null
            return Entry(m.groupValues[2].trim(), ExpectedChecksum(algorithm, hex.lowercase()))
        }
        // A lone hash on one line of a longer file (some sidecars add a trailing comment line).
        return parseRawHash(line)?.let { Entry(null, it) }
    }

    /** `name: AB12 CD34 ...`, the hash grouped and wrapped by `gpg --print-md`. */
    private fun parseGpgPrintMd(text: String, hint: ChecksumAlgorithm?): Entry? {
        val colon = text.indexOf(':')
        if (colon <= 0) return null
        val checksum = parseRawHash(text.substring(colon + 1), hint) ?: return null
        return Entry(text.substring(0, colon).trim(), checksum)
    }

    /** Manifest names may carry a directory (`./dist/file.zip`); only the file name is compared. */
    private fun baseName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
}
