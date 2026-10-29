// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.app.utils

import org.conscrypt.Conscrypt
import xdm.core.util.CryptoProviders
import xdm.core.util.Logger

/**
 * Hands Conscrypt (BoringSSL) to [CryptoProviders] for TLS and digests, where its JNI library exists
 * (Windows x64, Linux x64/arm64, macOS x64/arm64); elsewhere, or if it fails to load, the JDK's TLS stays.
 * It is deliberately not installed as a global provider: Cipher and Mac (the HLS AES-128 cipher, NTLM's
 * HmacMD5) stay with the JDK's SunJCE, and the GraalVM native image only accepts JCE providers verified
 * at build time.
 */
object ConscryptTls {
    @JvmStatic
    fun install(): Boolean {
        return try {
            if (!Conscrypt.isAvailable()) {
                Logger.info("XDM", "Conscrypt native library not available")
                return false
            }
            CryptoProviders.preferred = Conscrypt.newProvider()
            val v = Conscrypt.version()
            Logger.info("XDM", "Using Conscrypt ${v.major()}.${v.minor()}.${v.patch()} for TLS and digests")
            true
        } catch (e: Throwable) {
            Logger.error("XDM", "Could not install Conscrypt", e)
            false
        }
    }
}
