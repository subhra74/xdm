// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager.torrent

import java.util.Random

/**
 * The session settings, as `name=value` lines for [TorrentEngine.open]. Names are libtorrent's settings; the
 * values follow a widely used desktop client's defaults where they differ from libtorrent's (connections,
 * choking, disk, trackers), with XDM's own queue in place of libtorrent's (`active_* = -1`).
 */
object TorrentSettings {
    /** Per-torrent connection limit, set on each torrent when it is added. */
    const val MAX_CONNECTIONS_PER_TORRENT = 100

    private val defaults = linkedMapOf(
        "alert_queue_size" to "1073741823",
        "connection_speed" to "30",
        "connections_limit" to "500",
        "unchoke_slots_limit" to "20",
        "listen_queue_size" to "30",
        "seeding_outgoing_connections" to "true",
        "allow_multiple_connections_per_ip" to "false",
        "max_out_request_queue" to "500",
        "max_allowed_in_request_queue" to "2000",
        "peer_turnover" to "4",
        "peer_turnover_cutoff" to "90",
        "peer_turnover_interval" to "300",
        "enable_outgoing_utp" to "true",
        "enable_incoming_utp" to "true",
        "mixed_mode_algorithm" to "0", // prefer_tcp
        "choking_algorithm" to "0", // fixed_slots_choker
        "seed_choking_algorithm" to "1", // fastest_upload
        "in_enc_policy" to "1", // pe_enabled
        "out_enc_policy" to "1",
        "allowed_enc_level" to "2", // pe_rc4
        "prefer_rc4" to "true",
        "enable_dht" to "true",
        "enable_lsd" to "true",
        "dht_bootstrap_nodes" to "dht.libtorrent.org:25401,dht.transmissionbt.com:6881,router.bt.ouinet.work:6881",
        "use_dht_as_fallback" to "false",
        "enable_upnp" to "true",
        "enable_natpmp" to "true",
        "announce_to_all_trackers" to "false",
        "announce_to_all_tiers" to "true",
        "max_concurrent_http_announces" to "50",
        "stop_tracker_timeout" to "2",
        "auto_scrape_interval" to "1200",
        "auto_scrape_min_interval" to "900",
        "validate_https_trackers" to "true",
        "ssrf_mitigation" to "true",
        "allow_idna" to "false",
        "no_connect_privileged_ports" to "false",
        "max_retry_port_bind" to "0",
        "listen_system_port_fallback" to "false",
        "peer_dscp" to "1",
        "aio_threads" to "10",
        "hashing_threads" to "1",
        "file_pool_size" to "100",
        "checking_mem_usage" to "2048",
        "max_queued_disk_bytes" to (100 * 1024 * 1024).toString(),
        "disk_io_read_mode" to "0", // enable_os_cache
        "disk_io_write_mode" to "0",
        "enable_set_file_valid_data" to "true",
        "send_buffer_watermark" to (4 * 1024 * 1024).toString(),
        "send_buffer_low_watermark" to (128 * 1024).toString(),
        "send_buffer_watermark_factor" to "100",
        "active_checking" to "1",
        "active_downloads" to "-1",
        "active_seeds" to "-1",
        "active_limit" to "-1",
        "active_tracker_limit" to "-1",
        "active_dht_limit" to "-1",
        "active_lsd_limit" to "-1",
        "resolver_cache_timeout" to "1200",
        // Large v2/hybrid torrents need more bencode tokens than libtorrent's default to load their metadata.
        "metadata_token_limit" to "10000000",
        // The speed limits count the data only, like the HTTP limit (protocol overhead is not charged).
        "rate_limit_ip_overhead" to "false",
    )

    /** A listen port for the first session: random, above the well-known and common client ranges. */
    fun randomPort(random: Random = Random()): Int = 10000 + random.nextInt(55000)

    /**
     * The settings for a new session. [listenPort] is set only when there is no saved state to take the port
     * from (first start) or the user picked one; otherwise the saved state keeps the previous port.
     * [downloadLimit]/[uploadLimit] are bytes per second, 0 for none.
     */
    fun session(
        listenPort: Int?,
        downloadLimit: Int = 0,
        uploadLimit: Int = 0,
        proxy: TorrentProxy? = null,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val all = LinkedHashMap(defaults)
        listenPort?.let { all["listen_interfaces"] = listenInterfaces(it) }
        all.putAll(limits(downloadLimit, uploadLimit))
        proxy?.let { all.putAll(it.settings()) }
        all.putAll(extra)
        // No peer id prefix or user agent of our own: libtorrent's defaults ("-LT" + its version,
        // "libtorrent/<version>") identify the client to peers and trackers.
        return all.entries.joinToString("\n", postfix = "\nxt.disk_io=pread\n") {
            "${it.key}=${it.value}"
        }
    }

    fun listenInterfaces(port: Int) = "0.0.0.0:$port,[::]:$port"

    /**
     * The options the user sets on the BitTorrent page, as libtorrent settings. [encryption] is one of
     * EngineConfig's TORRENT_ENCRYPTION_* (prefer, require, off). A fixed [port] (above 0) rebinds the listen sockets.
     */
    fun options(
        portMapping: Boolean, dht: Boolean, lsd: Boolean, encryption: Int, port: Int,
        peerIdPrefix: String? = null, userAgent: String? = null,
    ): Map<String, String> {
        // libtorrent: pe_forced 0, pe_enabled 1, pe_disabled 2
        val policy = when (encryption) {
            1 -> "0"
            2 -> "2"
            else -> "1"
        }
        val m = linkedMapOf(
            "enable_upnp" to portMapping.toString(),
            "enable_natpmp" to portMapping.toString(),
            "enable_dht" to dht.toString(),
            "enable_lsd" to lsd.toString(),
            "in_enc_policy" to policy,
            "out_enc_policy" to policy,
        )
        if (port in 1..65535) m["listen_interfaces"] = listenInterfaces(port)
        // The client identity picked in the settings; without one libtorrent's own stays.
        peerIdPrefix?.takeIf { it.isNotBlank() }?.let { m["peer_fingerprint"] = it }
        userAgent?.takeIf { it.isNotBlank() }?.let { m["user_agent"] = it }
        return m
    }

    /** The global rate limits (bytes per second, 0 = none) as settings for [TorrentEngine.applySettings]. */
    fun limits(downloadLimit: Int, uploadLimit: Int) = mapOf(
        "download_rate_limit" to downloadLimit.coerceAtLeast(0).toString(),
        "upload_rate_limit" to uploadLimit.coerceAtLeast(0).toString(),
    )

    fun lines(settings: Map<String, String>) = settings.entries.joinToString("\n") { "${it.key}=${it.value}" }

}

/** XDM's proxy setting for torrent traffic (tracker, web seed and DHT; peers connect directly). */
data class TorrentProxy(
    val type: Type,
    val host: String,
    val port: Int,
    val user: String? = null,
    val password: String? = null,
) {
    enum class Type { HTTP, SOCKS4, SOCKS5 }

    fun settings(): Map<String, String> {
        val auth = !user.isNullOrEmpty()
        val type = when (type) {
            Type.SOCKS4 -> 1
            Type.SOCKS5 -> if (auth) 3 else 2
            Type.HTTP -> if (auth) 5 else 4
        }
        val m = linkedMapOf(
            "proxy_type" to type.toString(),
            "proxy_hostname" to host,
            "proxy_port" to port.toString(),
            "proxy_hostnames" to "true",
            "proxy_peer_connections" to "false",
        )
        if (auth) {
            m["proxy_username"] = user!!
            m["proxy_password"] = password.orEmpty()
        }
        return m
    }
}
