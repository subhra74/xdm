package xdm.app.recording

import xdm.app.AppContext
import xdm.app.RecordStatus
import xdm.app.I8N.text
import xdm.app.ui.screens.BatchDownloadDialog
import xdm.app.ui.screens.BrowserSetupDialog
import xdm.app.ui.screens.NewDownloadWindow
import xdm.app.ui.screens.NewVideoDownloadWindow
import xdm.app.ui.screens.PropertiesDialog
import xdm.app.ui.screens.StreamDownloadDialog
import xdm.app.ui.components.AppMenuHandler
import xdm.app.ui.screens.SettingsWindow
import xdm.app.ui.screens.AppWindow
import xdm.core.downloaders.DownloadType
import xdm.core.util.Logger
import xdm.integration.BrowserIntegration
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Properties
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JTable
import javax.swing.JDialog
import javax.swing.JOptionPane
import javax.swing.JPasswordField
import javax.swing.JRadioButton
import javax.swing.JTabbedPane
import javax.swing.MenuSelectionManager
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.system.exitProcess

/**
 * The scripted session the class-list recording runs (APPCDS.md, PACKAGING.md "Recording the class
 * list"). Only active with `-Dxdm.record.session=<server url>`, which build-bundle.sh / .ps1 pass for
 * `--record-server` / `-RecordServer`, against packaging/recording/RecordServer.java.
 *
 * It does what a user and the browser extension do: messages go to the integration port the way the
 * extension sends them, and every dialog is answered by clicking its buttons on the EDT. Steps run one
 * after another, each waiting for its downloads to end. At the end it exits like the tray's Quit,
 * with the number of failed steps as the exit code. The build drops this package's classes from the
 * recorded list, so none of this ends up in the archive.
 */
object RecordingSession {
    const val PROPERTY = "xdm.record.session"

    private const val STEP_TIMEOUT_MS = 5 * 60_000L
    private const val UI_TIMEOUT_MS = 60_000L
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"

    private val results = ArrayList<String>()
    private var failures = 0
    private lateinit var base: String
    private lateinit var session: Properties

    /** Starts the session on its own thread; called once the main window has been created. */
    fun start(serverUrl: String) {
        base = serverUrl.trimEnd('/')
        Thread({ run() }, "record-session").apply { isDaemon = true }.start()
    }

    private fun run() {
        try {
            awaitWindow(AppWindow::class.java)
            Thread.sleep(5000)
            session = Properties().apply { load(StringReader(get(base, "/session.properties"))) }
            Thread({ answerPrompts() }, "record-prompts").apply { isDaemon = true }.start()
            Thread({ longPoll() }, "record-poll").apply { isDaemon = true }.start()

            step("http") { download(base + prop("http.file")) }
            step("https") { download(prop("https.url")) }
            step("chunked") { download(base + prop("chunked.file")) }
            step("basic-auth") { download(base + prop("auth.file")) }
            step("proxy") {
                withProxy { download(base + prop("http.proxy.file")) + ", " + download(prop("https.proxy.url")) }
            }
            step("hls") { video("hls", base + prop("hls"), "application/vnd.apple.mpegurl", null) }
            step("hls-aes") { video("hls-aes", base + prop("hls.aes"), "application/vnd.apple.mpegurl", null) }
            step("dash") { video("dash", base + prop("dash"), "application/dash+xml", null) }
            step("mp4") { video("mp4", base + prop("video"), "video/mp4", prop("video.size").toLong()) }
            step("batch") { batch(prop("batch"), each = false) }
            step("batch-each") { batch(prop("batch.each"), each = true) }
            step("settings") { openSettings() }
            step("menu") { openSortMenu() }
            step("properties") { openProperties() }
            step("stream-dialog") {
                // "?via=dialog" keeps these from repeating the downloads the media steps made.
                listOf(
                    streamDialog(base + prop("hls") + "?via=dialog", dash = false),
                    streamDialog(base + prop("hls.media") + "?via=dialog", dash = false),
                    streamDialog(base + prop("dash") + "?via=dialog", dash = true),
                ).joinToString(", ")
            }
        } catch (t: Throwable) {
            failures++
            results.add("session: FAILED ${t.message ?: t}")
            Logger.error("RECORD", "Recording session stopped", t)
        }
        results.forEach { Logger.info("RECORD", it); println("RECORD: $it") }
        println("RECORD: ${if (failures == 0) "all steps passed" else "$failures step(s) failed"}")
        Thread.sleep(3000)
        exitProcess(failures)
    }

    private fun step(name: String, action: () -> String) {
        Logger.info("RECORD", "Step $name")
        val outcome = runCatching(action)
        outcome.onFailure { failures++; Logger.error("RECORD", "Step $name failed", it) }
        results.add("$name: " + outcome.fold({ "OK $it" }, { "FAILED ${it.message ?: it}" }))
    }

    private fun prop(key: String): String = session.getProperty(key) ?: error("server has no '$key'")

    // ---- the steps ------------------------------------------------------------------------

    /** A link the extension hands over ("/download"), confirmed in the new-download window. */
    private fun download(url: String): String {
        val before = downloadIds()
        post(
            "/download", json(
                "url" to url,
                "filename" to url.substringAfterLast('/').substringBefore('?'),
                "tabUrl" to "$base/page/download",
                "tabId" to "rec-download",
                "referer" to "$base/page/download",
                "userAgent" to USER_AGENT,
                "requestHeaders" to RawJson("{\"User-Agent\":[${quote(USER_AGENT)}]}"),
            )
        )
        clickButton(awaitWindow(NewDownloadWindow::class.java), text("ND_DOWNLOAD"))
        return awaitDownloads(before, 1)
    }

    /**
     * A stream the extension saw ("/media"), with the response headers it observed, then picked
     * from its video list ("/vid").
     */
    private fun video(tab: String, url: String, mime: String, size: Long?): String {
        val tabId = "rec-$tab"
        val responseHeaders = buildList {
            add("\"Content-Type\":[${quote(mime)}]")
            size?.let { add("\"Content-Length\":[${quote(it.toString())}]") }
        }.joinToString(",", "{", "}")
        post(
            "/media", json(
                "url" to url,
                "tabUrl" to "$base/page/$tab",
                "tabId" to tabId,
                "tabTitle" to "XDM recording $tab",
                "mimeType" to mime,
                "fileSize" to size,
                "userAgent" to USER_AGENT,
                "requestHeaders" to RawJson("{\"User-Agent\":[${quote(USER_AGENT)}]}"),
                "responseHeaders" to RawJson(responseHeaders),
            )
        )
        val vid = poll("video item for $tab") {
            Regex("\\{\"id\":\"(-?\\d+)\"[^{}]*\"tabId\":\"${Regex.escape(tabId)}\"\\}")
                .find(get(extensionBase(), "/sync"))?.groupValues?.get(1)
        }
        val before = downloadIds()
        post("/vid", json("vid" to RawJson(vid), "tabId" to tabId, "tabUrl" to "$base/page/$tab"))
        clickButton(awaitWindow(NewVideoDownloadWindow::class.java), text("ND_DOWNLOAD"))
        return awaitDownloads(before, 1)
    }

    /** The extension's "Download all" ("/batch"), as one batch entry or one download per link. */
    private fun batch(paths: String, each: Boolean): String {
        val items = paths.split(',').joinToString(",") { p ->
            json("url" to base + p.trim(), "filename" to p.substringAfterLast('/'))
        }
        val before = downloadIds()
        post(
            "/batch", json(
                "tabUrl" to "$base/page/batch",
                "tabTitle" to "XDM recording batch",
                "userAgent" to USER_AGENT,
                "referer" to "$base/page/batch",
                "groups" to RawJson("[{\"items\":[$items]}]"),
            )
        )
        val dialog = awaitWindow(BatchDownloadDialog::class.java)
        val mode = text(if (each) "BATCH_MODE_EACH" else "BATCH_MODE_ONE")
        val radio = poll("batch mode option") { onEdt { find<JRadioButton>(dialog) { it.text == mode } } }
        onEdt { if (!radio.isSelected) radio.doClick() }
        clickButton(dialog, text("ND_DOWNLOAD"))
        return awaitDownloads(before, if (each) paths.split(',').size else 1)
    }

    /** Settings from the toolbar, closed again without saving. */
    private fun openSettings(): String {
        val main = awaitWindow(AppWindow::class.java)
        val button = poll("settings button") { onEdt { find<JButton>(main) { it.name == "TOOL_SETTINGS" } } }
        SwingUtilities.invokeLater { button.doClick() }
        clickButton(awaitWindow(SettingsWindow::class.java), text("ND_CANCEL"))
        return "(opened and closed)"
    }

    /** The toolbar's sort menu: a popup with radio items, closed again without a choice. */
    private fun openSortMenu(): String {
        val main = awaitWindow(AppWindow::class.java)
        val button = poll("sort button") { onEdt { find<JButton>(main) { it.name == "TOOL_SORT" } } }
        SwingUtilities.invokeLater { button.doClick() }
        poll("sort menu") { onEdt { MenuSelectionManager.defaultManager().selectedPath.firstOrNull() } }
        Thread.sleep(500)
        onEdt { MenuSelectionManager.defaultManager().clearSelectedPath() }
        return "(opened and closed)"
    }

    /** Properties of a plain download and of a batch, every tab shown, then closed. */
    private fun openProperties(): String {
        val records = AppContext.db.findAll { it.status == RecordStatus.FINISHED }
        val picks = listOfNotNull(
            records.firstOrNull { it.downloadType == DownloadType.Http },
            records.firstOrNull { it.downloadType == DownloadType.Batch },
        )
        check(picks.size == 2) { "no finished plain and batch download to show" }
        for (record in picks) {
            AppContext.app.showPropertiesWindow(record)
            val dialog = awaitWindow(PropertiesDialog::class.java)
            onEdt { find<JTabbedPane>(dialog) { true } }?.let { tabs ->
                for (i in 0 until onEdt { tabs.tabCount }) {
                    onEdt { tabs.selectedIndex = i }
                    Thread.sleep(300)
                }
            }
            clickButton(dialog, text("LBL_CLOSE"))
            poll("properties to close") { onEdt { dialog.isShowing }.takeIf { !it } }
        }
        return picks.joinToString(", ") { it.fileName }
    }

    /**
     * Main menu → Stream download: type and URL entered, a Referer row added on the headers tab,
     * loaded, the last format and audio track picked, downloaded.
     */
    private fun streamDialog(url: String, dash: Boolean): String {
        val main = awaitWindow(AppWindow::class.java)
        val before = Window.getWindows().filterIsInstance<StreamDownloadDialog>().toSet()
        SwingUtilities.invokeLater { AppMenuHandler.showStreamDialog(main) }
        val dialog = poll("StreamDownloadDialog") {
            onEdt { Window.getWindows().firstOrNull { it is StreamDownloadDialog && it.isShowing && it !in before } }
        }
        val type = text(if (dash) "SD_DASH" else "SD_HLS")
        onEdt {
            find<JRadioButton>(dialog) { it.text == type }?.doClick()
            find<JTextField>(dialog) { it.name == "SD_URL" }!!.text = url
        }
        val tabs = onEdt { find<JTabbedPane>(dialog) { true }!! }
        onEdt { tabs.selectedIndex = 1 }
        clickButton(dialog, text("SD_ADD"))
        answerHeaderDialog("Referer", "$base/page")
        poll("header row") { onEdt { find<JTable>(dialog) { it.rowCount > 0 } } }
        clickButton(dialog, text("SD_EDIT")) // the new row is selected
        answerHeaderDialog("Referer", "$base/page/stream")
        onEdt { tabs.selectedIndex = 0 }
        clickButton(dialog, text("SD_LOAD"))
        val formats = poll("formats loaded") { onEdt { find<JList<*>>(dialog) { it.name == "SD_FORMAT" && it.model.size > 0 } } }
        onEdt {
            formats.selectedIndex = formats.model.size - 1
            find<JComboBox<*>>(dialog) { it.name == "SD_AUDIO" }?.let { if (it.itemCount > 0) it.selectedIndex = it.itemCount - 1 }
        }
        val ids = downloadIds()
        clickButton(dialog, text("ND_DOWNLOAD"))
        return awaitDownloads(ids, 1)
    }

    /** Fills the dialog's Name / Value prompt (Add or Edit) and presses its OK. */
    private fun answerHeaderDialog(name: String, value: String) {
        val prompt = poll("header prompt") {
            onEdt {
                Window.getWindows().firstOrNull { w ->
                    w is JDialog && w.isShowing && find<JTextField>(w) { it.name == "SD_HEADER_NAME" } != null
                }
            }
        }
        onEdt {
            find<JTextField>(prompt) { it.name == "SD_HEADER_NAME" }!!.text = name
            find<JTextField>(prompt) { it.name == "SD_HEADER_VALUE" }!!.text = value
        }
        clickButton(prompt, UIManager.getString("OptionPane.okButtonText") ?: "OK")
        poll("header prompt to close") { onEdt { prompt.isShowing }.takeIf { !it } }
    }

    /** The proxy from Settings, with credentials, for the downloads in [action]. */
    private fun <T> withProxy(action: () -> T): T {
        val config = AppContext.config
        config.useProxy = true
        config.socksProxy = false
        config.proxyHost = URI(base).host
        config.proxyPort = prop("proxy.port").toInt()
        config.proxyUser = prop("user")
        config.proxyPass = prop("password")
        try {
            return action()
        } finally {
            config.useProxy = false
            config.proxyHost = ""
            config.proxyUser = ""
            config.proxyPass = ""
        }
    }

    /**
     * Answers what pops up on its own while the steps run: the server's and the proxy's credential
     * prompts get the session's user name and password; the first-run browser setup dialog is closed.
     */
    private fun answerPrompts() {
        while (true) {
            Thread.sleep(300)
            runCatching {
                onEdt {
                    for (w in Window.getWindows()) {
                        if (!w.isShowing) continue
                        if (w is BrowserSetupDialog) {
                            Logger.info("RECORD", "Closing the first-run browser setup dialog")
                            w.dispose()
                            continue
                        }
                        val pane = (w as? JDialog)?.let { find<JOptionPane>(it) { true } } ?: continue
                        val password = find<JPasswordField>(pane) { true } ?: continue
                        val user = find<JTextField>(pane) { it !is JPasswordField } ?: continue
                        if (password.password.isNotEmpty()) continue // already answered
                        Logger.info("RECORD", "Answering a credential prompt")
                        user.text = prop("user")
                        password.text = prop("password")
                        find<JButton>(pane) { true }?.let { ok -> SwingUtilities.invokeLater { ok.doClick() } }
                    }
                }
            }
        }
    }

    /**
     * The extension's long poll ("/poll"), kept open for the whole session as the extension does:
     * each reply carries the state version to wait past next. XDM answers the last one with a bye.
     */
    private fun longPoll() {
        var version = 0L
        while (true) {
            runCatching {
                val body = json("clientId" to "xdm-recording", "version" to version)
                val reply = request(extensionBase(), "POST", "/poll", body)
                Regex("\"version\":(\\d+)").find(reply.second)?.let { version = it.groupValues[1].toLong() }
            }.onFailure { Thread.sleep(1000) }
        }
    }

    // ---- downloads ------------------------------------------------------------------------

    private fun downloadIds(): Set<Long> = AppContext.db.findAll { true }.mapTo(HashSet()) { it.id }

    /** Waits for [count] new downloads to end; their names and states, or a failure. */
    private fun awaitDownloads(before: Set<Long>, count: Int): String {
        val ended = setOf(RecordStatus.FINISHED, RecordStatus.ERROR, RecordStatus.PAUSED)
        val done = poll("$count download(s) to finish", STEP_TIMEOUT_MS) {
            AppContext.db.findAll { it.id !in before }
                .takeIf { list -> list.size >= count && list.all { it.status in ended } }
        }
        val summary = done.joinToString(", ") { "${it.fileName} ${it.status}" }
        check(done.all { it.status == RecordStatus.FINISHED }) { summary }
        return summary
    }

    // ---- UI -------------------------------------------------------------------------------

    private fun <T : Window> awaitWindow(type: Class<T>): T = poll(type.simpleName) {
        onEdt { Window.getWindows().firstOrNull { type.isInstance(it) && it.isShowing }?.let(type::cast) }
    }

    /** Clicks the button labelled [label] in [window] once it is enabled, as a user would. */
    private fun clickButton(window: Window, label: String) {
        val button = poll("'$label' in ${window.javaClass.simpleName}") {
            onEdt { find<AbstractButton>(window) { it.text == label && it.isShowing && it.isEnabled } }
        }
        SwingUtilities.invokeLater { button.doClick() }
    }

    private inline fun <reified T : Component> find(root: Container, noinline match: (T) -> Boolean): T? =
        find(root, T::class.java, match)

    private fun <T : Component> find(root: Container, type: Class<T>, match: (T) -> Boolean): T? {
        for (c in root.components) {
            if (type.isInstance(c) && match(type.cast(c))) return type.cast(c)
            if (c is Container) find(c, type, match)?.let { return it }
        }
        return null
    }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> poll(what: String, timeoutMs: Long = UI_TIMEOUT_MS, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(250)
        }
        error("timed out waiting for $what")
    }

    // ---- the extension's side of the integration port --------------------------------------

    private fun extensionBase() = "http://127.0.0.1:${BrowserIntegration.PORT}"

    private fun post(path: String, body: String) {
        val status = request(extensionBase(), "POST", path, body).first
        check(status == 200) { "POST $path answered $status" }
    }

    private fun get(url: String, path: String): String {
        val (status, body) = request(url, "GET", path, null)
        check(status == 200) { "GET $url$path answered $status" }
        return body
    }

    /**
     * One request on a plain socket: the extension's messages carry an extension `Origin`, as the
     * browser adds it. A socket rather than a JDK HTTP client, so the client side adds no classes.
     */
    private fun request(url: String, method: String, path: String, body: String?): Pair<Int, String> {
        val uri = URI(url)
        val bytes = body?.toByteArray(StandardCharsets.UTF_8)
        Socket(Proxy.NO_PROXY).use { socket ->
            socket.soTimeout = 60_000 // longer than the long poll's 20 s
            socket.connect(InetSocketAddress(uri.host, uri.port), 10_000)
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nConnection: close\r\n")
                if (method == "POST") append("Origin: chrome-extension://xdm-recording\r\n")
                if (bytes != null) append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(head.toByteArray(StandardCharsets.ISO_8859_1))
                bytes?.let { write(it) }
                flush()
            }
            val response = socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
            val status = response.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            return status to response.substringAfter("\r\n\r\n", "")
        }
    }

    private class RawJson(val text: String)

    private fun json(vararg fields: Pair<String, Any?>): String =
        fields.filter { it.second != null }.joinToString(",", "{", "}") { (k, v) ->
            quote(k) + ":" + when (v) {
                is RawJson -> v.text
                is Number, is Boolean -> v.toString()
                else -> quote(v.toString())
            }
        }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' || c == '\\' -> append('\\').append(c)
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
