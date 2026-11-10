// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.network.http

import xdm.core.network.http.impl.SharedTls
import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.concurrent.ConcurrentHashMap

/**
 * Server certificates the user chose to trust although the platform refused them, browser style: one
 * certificate (its SHA-256 fingerprint) for one host, shared by downloads and the Android browser.
 *
 * Kept in the file [FILE_NAME] in the app's data folder, written whole on every change. Loaded once by
 * [init]; before that, and when the file is missing or unreadable, the list is empty. Thread safe:
 * the TLS stack reads it on handshake threads.
 */
object CertExceptions {
    const val FILE_NAME = "cert-exceptions"

    private const val FORMAT_VERSION = 1

    /** One accepted certificate: [fingerprint] is the lowercase hex SHA-256 of the leaf's DER encoding. */
    data class Entry(val host: String, val fingerprint: String, val acceptedAt: Long)

    @Volatile
    private var folder: String? = null

    /** By [key]. */
    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Loads the list from [folder] (the app's data folder), replacing what was in memory; the list is
     * saved there from then on. Call it at startup, before the first HTTPS request.
     */
    @Synchronized
    fun init(folder: String) {
        this.folder = folder
        entries.clear()
        if (!AtomicIO.exists(FILE_NAME, folder)) return
        AtomicIO.readTransacted(FILE_NAME, folder) { input ->
            val version = input.readInt()
            if (version != FORMAT_VERSION) throw IllegalStateException("Unknown format $version")
            val loaded = ArrayList<Entry>()
            repeat(input.readInt()) {
                val host = input.readUTF()
                val fingerprint = input.readUTF()
                loaded.add(Entry(host, fingerprint, input.readLong()))
            }
            loaded
        }.onSuccess { loaded ->
            loaded.forEach { entries[key(it.host, it.fingerprint)] = it }
        }.onFailure {
            Logger.error("XDM", "Could not read the certificate exceptions; starting with none", it)
        }
    }

    /** Whether the user accepted the certificate with [fingerprint] for [host]. */
    fun isAccepted(host: String, fingerprint: String): Boolean = entries.containsKey(key(host, fingerprint))

    /** Whether the user accepted the certificate with [fingerprint] for any host. */
    fun isAcceptedFingerprint(fingerprint: String): Boolean {
        val fp = fingerprint.lowercase()
        return entries.values.any { it.fingerprint == fp }
    }

    /** Accepts the certificate with [fingerprint] for [host] and saves the list. */
    @Synchronized
    fun add(host: String, fingerprint: String) {
        val entry = Entry(host.lowercase(), fingerprint.lowercase(), System.currentTimeMillis())
        if (entries.putIfAbsent(key(entry.host, entry.fingerprint), entry) == null) save()
    }

    /**
     * Stops accepting the certificate with [fingerprint] for [host] and saves the list. TLS sessions
     * with [host] are dropped, so the next connection checks its certificate again.
     */
    @Synchronized
    fun remove(host: String, fingerprint: String) {
        if (entries.remove(key(host, fingerprint)) == null) return
        save()
        SharedTls.forgetHost(host.lowercase())
    }

    /** The accepted certificates, newest first. */
    fun list(): List<Entry> = entries.values.sortedByDescending { it.acceptedAt }

    /** The fingerprint the list keys certificates by: lowercase hex SHA-256 of [cert]'s DER encoding. */
    @JvmStatic
    fun fingerprint(cert: Certificate): String = fingerprint(cert.encoded)

    @JvmStatic
    fun fingerprint(der: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        val hex = CharArray(digest.size * 2)
        digest.forEachIndexed { i, b ->
            hex[i * 2] = HEX[(b.toInt() shr 4) and 0xf]
            hex[i * 2 + 1] = HEX[b.toInt() and 0xf]
        }
        return String(hex)
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun key(host: String, fingerprint: String) = host.lowercase() + "|" + fingerprint.lowercase()

    private fun save() {
        val dir = folder ?: return
        val snapshot = entries.values.toList()
        AtomicIO.writeTransacted(FILE_NAME, dir, ownerOnly = true) { out ->
            out.writeInt(FORMAT_VERSION)
            out.writeInt(snapshot.size)
            snapshot.forEach {
                out.writeUTF(it.host)
                out.writeUTF(it.fingerprint)
                out.writeLong(it.acceptedAt)
            }
        }.onFailure { Logger.error("XDM", "Could not save the certificate exceptions", it) }
    }
}
