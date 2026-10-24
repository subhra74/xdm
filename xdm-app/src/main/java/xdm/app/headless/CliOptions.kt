package xdm.app.headless

import xdm.core.CoreConfig

/** What kind of download a URL is; [Auto] decides from the URL (`.m3u8` HLS, `.mpd` DASH, else HTTP). */
enum class StreamType { Auto, Http, Hls, Dash }

/**
 * Which format of an HLS/DASH stream to download. [Height] takes that height, or failing that the
 * nearest one below it (the lowest above when none is below).
 */
sealed interface Quality {
    data object Best : Quality
    data object Worst : Quality
    data object AudioOnly : Quality
    data class Height(val pixels: Int) : Quality

    /** 1-based position in the `--list-formats` table. */
    data class Index(val position: Int) : Quality
}

data class CliOptions(
    val urls: List<String>,
    val outputDir: String,
    val fileName: String?,
    val type: StreamType,
    val quality: Quality,
    /** Case-insensitive part of the audio track's label (language, name); null takes the default track. */
    val audio: String?,
    val listFormats: Boolean,
    /** Connections per download; 0 means the default. */
    val segments: Int,
    /** In KB/s; 0 means unlimited. */
    val speedLimit: Int,
    val headers: Map<String, List<String>>,
    val cookie: String?,
    val user: String?,
    val password: String?,
    /** `http://host:port` or `socks5://host:port`. */
    val proxy: String?,
    val proxyUser: String?,
    val proxyPassword: String?,
    val insecure: Boolean,
    val overwrite: Boolean,
    val quiet: Boolean,
    val verbose: Boolean,
)

/** A bad command line; the message is shown with a pointer to `--help`. */
class CliUsageException(message: String) : Exception(message)

/** The result of parsing: either options to run, or a request for the help text. */
sealed interface ParsedArgs {
    data object Help : ParsedArgs
    data class Run(val options: CliOptions) : ParsedArgs
}

/**
 * Hand-written parser for the headless command line (no third-party parser by design).
 *
 * Accepts `--name value`, `--name=value`, `-x value`, repeatable `-H`, and `--` to end options.
 * Anything not starting with `-` is a URL.
 */
object CliParser {
    val HEADLESS_FLAGS = setOf("--headless", "--no-gui")

    fun isHeadless(args: Array<String>): Boolean = args.any { it in HEADLESS_FLAGS }

    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"

    private val valueOptions = mapOf(
        "-o" to "--output-dir", "--output-dir" to "--output-dir", "--dir" to "--output-dir",
        "-f" to "--file-name", "--file-name" to "--file-name",
        "-t" to "--type", "--type" to "--type",
        "-q" to "--quality", "--quality" to "--quality",
        "--format" to "--format",
        "-a" to "--audio", "--audio" to "--audio",
        "-s" to "--segments", "--segments" to "--segments",
        "--speed-limit" to "--speed-limit",
        "-H" to "--header", "--header" to "--header",
        "-c" to "--cookie", "--cookie" to "--cookie",
        "-r" to "--referer", "--referer" to "--referer",
        "-A" to "--user-agent", "--user-agent" to "--user-agent",
        "-u" to "--user", "--user" to "--user",
        "-p" to "--password", "--password" to "--password",
        "--proxy" to "--proxy",
        "--proxy-user" to "--proxy-user",
        "--proxy-password" to "--proxy-password",
    )

    private val flagOptions = mapOf(
        "-l" to "--list-formats", "--list-formats" to "--list-formats",
        "-k" to "--insecure", "--insecure" to "--insecure",
        "-y" to "--overwrite", "--overwrite" to "--overwrite",
        "--quiet" to "--quiet",
        "-v" to "--verbose", "--verbose" to "--verbose",
        "-h" to "--help", "--help" to "--help", "-?" to "--help",
    )

    /** Flags the normal launch also understands; harmless here, so they are skipped rather than rejected. */
    private val ignored = HEADLESS_FLAGS + setOf("--no-gc", "--minimized")

    fun parse(args: Array<String>): ParsedArgs {
        val urls = ArrayList<String>()
        val values = HashMap<String, String>()
        val headers = LinkedHashMap<String, MutableList<String>>()
        val flags = HashSet<String>()
        var i = 0
        var optionsEnded = false
        while (i < args.size) {
            val arg = args[i++]
            if (optionsEnded || !arg.startsWith("-") || arg == "-") {
                urls += arg
                continue
            }
            if (arg == "--") {
                optionsEnded = true
                continue
            }
            if (arg in ignored) continue
            val eq = if (arg.startsWith("--")) arg.indexOf('=') else -1
            val name = if (eq > 0) arg.substring(0, eq) else arg
            flagOptions[name]?.let { flag ->
                if (eq > 0) throw CliUsageException("$name does not take a value")
                flags += flag
                continue
            }
            val option = valueOptions[name] ?: throw CliUsageException("Unknown option: $name")
            val value = when {
                eq > 0 -> arg.substring(eq + 1)
                i < args.size -> args[i++]
                else -> throw CliUsageException("$name needs a value")
            }
            if (option == "--header") {
                val colon = value.indexOf(':')
                if (colon <= 0) throw CliUsageException("Header must be \"Name: value\": $value")
                headers.getOrPut(value.substring(0, colon).trim()) { ArrayList() } += value.substring(colon + 1).trim()
            } else {
                values[option] = value
            }
        }
        if ("--help" in flags) return ParsedArgs.Help

        if (urls.isEmpty()) throw CliUsageException("No URL given")
        urls.firstOrNull { !it.startsWith("http://", true) && !it.startsWith("https://", true) }?.let {
            throw CliUsageException("Not an http(s) URL: $it")
        }
        val listFormats = "--list-formats" in flags
        val outputDir = values["--output-dir"]
        if (outputDir.isNullOrBlank() && !listFormats) {
            throw CliUsageException("The target folder is required: -o <folder>")
        }
        val fileName = values["--file-name"]?.takeIf { it.isNotBlank() }
        if (fileName != null && urls.size > 1) throw CliUsageException("--file-name works with a single URL only")

        values["--referer"]?.let { setHeader(headers, "Referer", it) }
        values["--user-agent"]?.let { setHeader(headers, "User-Agent", it) }
        if (headers.keys.none { it.equals("User-Agent", true) }) headers["User-Agent"] = mutableListOf(DEFAULT_USER_AGENT)
        // A Cookie header given with -H is the same as --cookie; keep one place for it.
        val cookieHeader = headers.keys.firstOrNull { it.equals("Cookie", true) }?.let { headers.remove(it) }
        val cookie = values["--cookie"] ?: cookieHeader?.joinToString("; ")

        var user = values["--user"]
        var password = values["--password"]
        if (user != null && password == null && ':' in user) {
            password = user.substringAfter(':')
            user = user.substringBefore(':')
        }

        val proxy = values["--proxy"]?.also { parseProxy(it) }

        return ParsedArgs.Run(
            CliOptions(
                urls = urls,
                outputDir = outputDir.orEmpty(),
                fileName = fileName,
                type = parseType(values["--type"]),
                quality = values["--format"]?.let { parseIndex(it) } ?: parseQuality(values["--quality"]),
                audio = values["--audio"]?.takeIf { it.isNotBlank() },
                listFormats = listFormats,
                segments = values["--segments"]?.let { positiveInt("--segments", it, max = CoreConfig.MAX_SEGMENTS) } ?: 0,
                speedLimit = values["--speed-limit"]?.let { positiveInt("--speed-limit", it) } ?: 0,
                headers = headers,
                cookie = cookie,
                user = user,
                password = password,
                proxy = proxy,
                proxyUser = values["--proxy-user"],
                proxyPassword = values["--proxy-password"],
                insecure = "--insecure" in flags,
                overwrite = "--overwrite" in flags,
                quiet = "--quiet" in flags,
                verbose = "--verbose" in flags,
            )
        )
    }

    private fun setHeader(headers: MutableMap<String, MutableList<String>>, name: String, value: String) {
        headers.keys.filter { it.equals(name, true) }.forEach { headers.remove(it) }
        headers[name] = mutableListOf(value)
    }

    private fun parseType(value: String?): StreamType = when (value?.lowercase()) {
        null, "auto" -> StreamType.Auto
        "http", "https" -> StreamType.Http
        "hls", "m3u8" -> StreamType.Hls
        "dash", "mpd" -> StreamType.Dash
        else -> throw CliUsageException("--type must be auto, http, hls or dash: $value")
    }

    fun parseQuality(value: String?): Quality {
        val v = value?.trim()?.lowercase() ?: return Quality.Best
        return when (v) {
            "best", "highest", "max" -> Quality.Best
            "worst", "lowest", "min" -> Quality.Worst
            "audio", "audio-only" -> Quality.AudioOnly
            else -> {
                val pixels = v.removeSuffix("p").toIntOrNull()?.takeIf { it > 0 }
                    ?: throw CliUsageException("--quality must be best, worst, audio or a height like 720p: $value")
                Quality.Height(pixels)
            }
        }
    }

    private fun parseIndex(value: String): Quality =
        Quality.Index(positiveInt("--format", value).takeIf { it > 0 } ?: throw CliUsageException("--format starts at 1"))

    private fun positiveInt(name: String, value: String, max: Int = Int.MAX_VALUE): Int {
        val n = value.trim().toIntOrNull()
        if (n == null || n < 0 || n > max) throw CliUsageException("$name must be a number from 0 to $max: $value")
        return n
    }

    /** Splits `scheme://host:port` into (socks?, host, port). */
    fun parseProxy(value: String): Triple<Boolean, String, Int> {
        val scheme = value.substringBefore("://", "http").lowercase()
        val socks = when (scheme) {
            "http", "https" -> false
            "socks", "socks4", "socks5" -> true
            else -> throw CliUsageException("--proxy must be http://host:port or socks5://host:port: $value")
        }
        val hostPort = value.substringAfter("://").trimEnd('/')
        val colon = hostPort.lastIndexOf(':')
        val port = if (colon > 0) hostPort.substring(colon + 1).toIntOrNull() else null
        if (port == null || port !in 1..65535) throw CliUsageException("--proxy needs a port: $value")
        return Triple(socks, hostPort.substring(0, colon), port)
    }

    val USAGE = """
        |Usage: xdm-app --headless -o <folder> [options] <url>...
        |
        |Downloads files from the command line, without the window or browser integration.
        |HTTP(S) files, HLS (.m3u8) and DASH (.mpd) streams are supported; URLs are downloaded one by one.
        |
        |Required:
        |  -o, --output-dir <folder>   Folder to save into (created if missing)
        |
        |Stream selection:
        |  -t, --type <auto|http|hls|dash>
        |                              How to treat the URL (default auto: .m3u8 is HLS, .mpd is DASH)
        |  -q, --quality <q>           best (default), worst, audio, or a height such as 1080p or 720
        |                              (takes the nearest lower height when there is no exact match)
        |      --format <n>            Format number n from --list-formats
        |  -a, --audio <text>          Audio track whose name or language contains <text>
        |  -l, --list-formats          List the formats of a stream and exit
        |
        |Output:
        |  -f, --file-name <name>      File name (one URL only). Default: from the server or the URL
        |  -y, --overwrite             Replace an existing file (default: save as name_1.ext)
        |
        |Network:
        |  -s, --segments <n>          Connections per download (default 8)
        |      --speed-limit <KB/s>    Limit the download speed
        |  -H, --header "Name: value"  Extra request header (repeatable)
        |  -c, --cookie <cookies>      Cookie header value, e.g. "a=1; b=2"
        |  -r, --referer <url>         Referer header
        |  -A, --user-agent <ua>       User-Agent header
        |  -u, --user <user[:pass]>    Server login (Basic or NTLM)
        |  -p, --password <pass>       Server password
        |      --proxy <url>           http://host:port or socks5://host:port
        |      --proxy-user <user>     Proxy login
        |      --proxy-password <pass> Proxy password
        |  -k, --insecure              Do not verify TLS certificates
        |
        |Other:
        |      --quiet                 No progress output (the saved paths are still printed)
        |  -v, --verbose               Show the engine log on stderr
        |  -h, --help                  Show this help
        |
        |Each saved file's path is printed to stdout; progress and errors go to stderr.
        |Exit status: 0 all downloaded, 1 a download failed, 2 bad arguments, 130 interrupted (Ctrl+C).
        |""".trimMargin()
}
