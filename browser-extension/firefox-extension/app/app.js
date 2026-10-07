"use strict";

/** Most links one bulk request may carry; a sanity cap, far beyond any real selection. */
const MAX_BATCH_ITEMS = 5000;

/** Opened once, on first install (not on updates): the next steps after adding the extension. */
const WELCOME_URL = "https://xtremedownloadmanager.com/extension/welcome/?utm_source=extension";

class App {

    constructor() {
        this.logger = new Logger();
        this.videoList = [];
        this.blockedHosts = [];
        this.fileExts = [];
        this.requestWatcher = new RequestWatcher(this.onRequestDataReceived.bind(this), this.onDownloadCandidate.bind(this));
        this.userDisabled = false;
        this.appEnabled = false;
        this.onTabUpdateCallback = this.onTabUpdate.bind(this);
        this.activeTabId = -1;
        this.connector = new Connector(this.onMessage.bind(this), this.onDisconnect.bind(this));
    }

    start() {
        this.logger.log("starting...");
        this.starAppConnector();
        this.register();
        this.logger.log("started.");
    }

    starAppConnector() {
        this.connector.connect();
    }

    onMessage(msg) {
        this.logger.log("message from XDM");
        this.logger.log(msg);
        this.appEnabled = msg.enabled === true;
        this.fileExts = msg.fileExts || [];
        this.blockedHosts = msg.blockedHosts || [];
        this.videoList = msg.videoList || [];
        this.requestWatcher.updateConfig({
            mediaExts: msg.requestFileExts,
            blockedHosts: msg.blockedHosts,
            mediaTypes: msg.mediaTypes
        });
        this.updateActionIcon();
    }

    onDisconnect() {
        this.logger.log("Disconnected from native host!");
        this.logger.log("Disconnected...");
        this.updateActionIcon();
    }

    isMonitoringEnabled() {
        this.logger.log(this.appEnabled + " " + this.userDisabled);
        return this.appEnabled === true && this.userDisabled === false && this.connector.isConnected();
    }

    onRequestDataReceived(data) {
        //Streaming video data received, send to native messaging application
        this.logger.log("onRequestDataReceived");
        this.logger.log(data);
        if (data.download) {
            // Already cancelled in the browser, so it goes to XDM regardless.
            this.sendDownload(data);
            return;
        }
        this.isMonitoringEnabled() && this.connector.isConnected() && this.connector.postMessage("/media", data);
    }

    /**
     * Firefox's stand-in for Chrome's onDeterminingFilename: called synchronously from the blocking
     * webRequest listener with a GET that would become a download. Returning true cancels it in the
     * browser and hands it to XDM; on false it is left completely alone. Fills in what the downloads
     * API would have settled on in Chrome - name, size and type - from the response headers.
     */
    onDownloadCandidate(data, res) {
        if (!this.isMonitoringEnabled()) {
            return false;
        }
        let filename = this.filenameFromDisposition(data.responseHeaders);
        if (!this.shouldTakeOver(data.url, filename)) {
            return false;
        }
        data.filename = filename;
        let size = +this.headerValue(data.responseHeaders, "Content-Length");
        data.fileSize = size > 0 ? size : undefined;
        let mime = this.headerValue(data.responseHeaders, "Content-Type");
        data.mimeType = mime ? mime.split(";")[0].trim() : undefined;
        data.referer = this.headerValue(data.requestHeaders, "Referer");
        return true;
    }

    headerValue(dict, name) {
        let key = Object.keys(dict || {}).find(k => k.toLowerCase() === name.toLowerCase());
        return key ? dict[key][0] : undefined;
    }

    /** The name a Content-Disposition header gives, preferring the RFC 5987 filename*. */
    filenameFromDisposition(headers) {
        let value = this.headerValue(headers, "Content-Disposition");
        if (!value) {
            return undefined;
        }
        let ext = /filename\*\s*=\s*([^']*)'[^']*'([^;]+)/i.exec(value);
        if (ext) {
            try {
                return decodeURIComponent(ext[2].trim().replace(/^"|"$/g, ""));
            } catch { }
        }
        let plain = /filename\s*=\s*("([^"]*)"|[^;]+)/i.exec(value);
        if (plain) {
            let name = (plain[2] !== undefined ? plain[2] : plain[1]).trim();
            return name || undefined;
        }
        return undefined;
    }

    onTabUpdate(tabId, changeInfo, tab) {
        // The user is browsing, so this is a good moment to notice XDM has been started. Cheap: the
        // connector ignores this outright while it is connected, and rate-limits it when it is not.
        this.connector.tryConnect("tab update");
        // A new document started loading in this tab - a reload, or a navigation to a different
        // page - so anything captured for it no longer belongs to what is on screen. Same-document
        // changes (hash / history.pushState) report no status, so they are left alone.
        // This runs even while monitoring is off: the stale list should still go.
        if (changeInfo.status === 'loading') {
            this.clearTabVideos(tabId, changeInfo.url || (tab && tab.url));
        }
        if (!this.isMonitoringEnabled()) {
            return;
        }
        // XDM names a detected video after the page it came from, so it needs the tab's title -
        // which usually arrives after the media request. Only worth reporting when XDM actually has
        // something captured for this tab to rename. (This used to be gated on a `tabsWatcher` list
        // XDM never sends, so no title ever reached it.)
        if (changeInfo.title && this.videosForTab(tabId + "").length > 0) {
            this.logger.log("Tab changed: " + changeInfo.title + " => " + tab.url);
            try {
                this.connector.postMessage("/tab-update", {
                    tabUrl: tab.url,
                    tabTitle: changeInfo.title
                });
            } catch (ex) {
                console.log(ex);
            }
        }
    }

    clearTabVideos(tabId, tabUrl) {
        if (!this.connector.isConnected()) {
            return;
        }
        this.logger.log("Tab navigated, clearing detected videos for tab " + tabId);
        // Drop them locally too, so the badge doesn't keep counting them until XDM's reply lands.
        this.videoList = (this.videoList || []).filter(vid => vid.tabId != (tabId + ""));
        this.connector.postMessage("/clear-tab", {
            tabId: tabId + "",
            tabUrl: tabUrl
        });
        this.updateActionIcon();
    }

    register() {
        chrome.tabs.onUpdated.addListener(
            this.onTabUpdateCallback
        );
        chrome.runtime.onMessage.addListener(this.onPopupMessage.bind(this));
        chrome.runtime.onInstalled.addListener((details) => {
            if (details && details.reason === "install") {
                chrome.tabs.create({ url: WELCOME_URL });
            }
        });
        this.requestWatcher.register();
        // The tab that is already active at startup never fires onActivated.
        chrome.tabs.query({ active: true, currentWindow: true }, tabs => {
            if (tabs && tabs[0] && this.activeTabId === -1) {
                this.activeTabId = tabs[0].id + "";
                this.updateActionIcon();
            }
        });
        this.attachContextMenu();
        chrome.tabs.onActivated.addListener(this.onTabActivated.bind(this));
        chrome.commands.onCommand.addListener(this.onCommand.bind(this));
    }
    
    isSupportedProtocol(url) {
        if (!url) return false;
        let u = new URL(url);
        return u.protocol === 'http:' || u.protocol === 'https:';
    }

    shouldTakeOver(url, file) {
        let u = new URL(url);
        if (!this.isSupportedProtocol(url)) {
            return false;
        }
        let hostName = u.host;
        if (this.blockedHosts.find(item => hostName.indexOf(item) >= 0)) {
            return false;
        }
        let path = file || u.pathname;
        let upath = path.toUpperCase();
        // fileExts arrive without a leading dot; match on ".EXT" so names that merely
        // end in an extension substring (e.g. "...NEWEXE") don't false-positive.
        if (this.fileExts.find(ext => upath.endsWith("." + ext.toUpperCase()))) {
            return true;
        }
        return false;
    }

    // A video belongs to the given tab when its tabId matches, or when it has
    // no meaningful tabId (untabbed / background capture: missing, "-1" or "0"),
    // in which case it is shown in every tab.
    isVideoForTab(vid, tabId) {
        if (!vid.tabId || vid.tabId == '-1' || vid.tabId == '0') {
            return true;
        }
        return vid.tabId == tabId;
    }

    videosForTab(tabId) {
        if (!this.videoList) {
            return [];
        }
        return this.videoList.filter(vid => this.isVideoForTab(vid, tabId));
    }

    updateActionIcon() {
        chrome.browserAction.setIcon({ path: this.getActionIcon() });
        let vc = "";
        let len = this.videosForTab(this.activeTabId).length;
        if (len > 0) {
            vc = len + "";
        }
        chrome.browserAction.setBadgeText({ text: vc });
        if (!this.connector.isConnected()) {
            this.logger.log("Not connected...");
            chrome.browserAction.setPopup({ popup: "./app/error.html" });
            return;
        }
        if (!this.appEnabled) {
            chrome.browserAction.setPopup({ popup: "./app/disabled.html" });
            return;
        }
        else {
            chrome.browserAction.setPopup({ popup: "./app/popup.html" });
            return;
            // if (this.videoList && this.videoList.length > 0) {
            //     chrome.browserAction.setBadgeText({ text: this.videoList.length + "" });
            // }
        }
    }

    getActionIconName(icon) {
        return this.isMonitoringEnabled() ? icon + ".png" : icon + "-mono.png";
    }

    getActionIcon() {
        return {
            "16": this.getActionIconName("icon16"),
            "48": this.getActionIconName("icon48"),
            "128": this.getActionIconName("icon128")
        }
    }

    // Hands XDM the request the browser actually made - its own headers and the cookies it really
    // sent - together with the name, size and type its response announced.
    sendDownload(observed) {
        let data = {
            url: observed.url,
            cookie: observed.cookie,
            requestHeaders: observed.requestHeaders,
            responseHeaders: observed.responseHeaders,
            filename: observed.filename,
            fileSize: observed.fileSize,
            mimeType: observed.mimeType,
            referer: observed.referer,
            tabUrl: observed.tabUrl,
            tabId: observed.tabId
        };
        this.logger.log(data);
        this.connector.postMessage("/download", data);
    }

    // For the context menu only: those links were never requested by the browser, so there is no
    // observed request to replay and the cookies have to be looked up instead.
    triggerDownload(url, file, referer, size, mime) {
        chrome.cookies.getAll({ "url": url }, cookies => {
            let cookieStr = undefined;
            if (cookies) {
                cookieStr = cookies.map(cookie => cookie.name + "=" + cookie.value).join("; ");
            }
            let requestHeaders = { "User-Agent": [navigator.userAgent] };
            if (referer) {
                requestHeaders["Referer"] = [referer];
            }
            let responseHeaders = {};
            if (size) {
                let fz = +size;
                if (fz > 0) {
                    responseHeaders["Content-Length"] = [fz];
                }
            }
            if (mime) {
                responseHeaders["Content-Type"] = [mime];
            }
            let data = {
                url: url,
                cookie: cookieStr,
                requestHeaders: requestHeaders,
                responseHeaders: responseHeaders,
                filename: file,
                fileSize: size,
                mimeType: mime
            };
            this.logger.log(data);
            this.connector.postMessage("/download", data);
        });
    }
    diconnect() {
        this.onDisconnect();
    }

    onPopupMessage(request, sender, sendResponse) {
        this.logger.log(request.type);
        if (request.type === "stat") {
            // Opening the popup is the clearest "is XDM there?" moment there is, so look before
            // answering rather than reporting what the background page knew last.
            this.connector.tryConnect("popup opened").then(() => {
                // Resolve the active tab fresh: onActivated does not fire for the tab that was
                // already active at startup, or when switching windows.
                chrome.tabs.query({ active: true, currentWindow: true }, tabs => {
                    let tabId = (tabs && tabs[0]) ? tabs[0].id + "" : this.activeTabId;
                    this.activeTabId = tabId;
                    sendResponse({
                        enabled: this.isMonitoringEnabled(),
                        connected: this.connector.isConnected() === true,
                        list: this.videosForTab(tabId)
                    });
                });
            });
            return true; // keep the message channel open for the async response
        }
        else if (request.type === "cmd") {
            this.userDisabled = request.enabled === false;
            this.logger.log("request.enabled:" + request.enabled);
            if (request.enabled && !this.connector.isConnected()) {
                this.connector.launchApp();
                return;
            }
            this.updateActionIcon();
        }
        else if (request.type === "vid") {
            let vid = request.itemId;
            this.connector.postMessage("/vid", {
                vid: vid + "",
            });
        }
        else if (request.type === "clear") {
            this.connector.postMessage("/clear", {});
        }
        else if (request.type === "bulk") {
            this.openBulkPicker(request.tabId, "all");
        }
        else if (request.type === "bulk-status") {
            this.connector.tryConnect("bulk picker opened").then(() => {
                sendResponse({ connected: this.connector.isConnected() === true });
            });
            return true;
        }
        else if (request.type === "batch") {
            this.sendBatch(request).then(sendResponse, err => sendResponse({ ok: false, error: err.message }));
            return true;
        }
    }

    sendLinkToXDM(info, tab) {
        let url = info.linkUrl;
        if (!this.isSupportedProtocol(url)) {
            url = info.srcUrl;
        }
        if (!this.isSupportedProtocol(url)) {
            url = info.pageUrl;
        }
        if (!this.isSupportedProtocol(url)) {
            return;
        }
        this.triggerDownload(url, null, info.pageUrl, null, null);
    }

    sendImageToXDM(info, tab) {
        let url = info.srcUrl;
        if (!this.isSupportedProtocol(url))
            url = info.linkUrl;
        if (!this.isSupportedProtocol(url)) {
            url = info.pageUrl;
        }
        if (!this.isSupportedProtocol(url)) {
            return;
        }
        this.triggerDownload(url, null, info.pageUrl, null, null);
    }

    onMenuClicked(info, tab) {
        if (info.menuItemId == "download-any-link") {
            this.sendLinkToXDM(info, tab);
        }
        if (info.menuItemId == "download-image-link") {
            this.sendImageToXDM(info, tab);
        }
        if (info.menuItemId == "bulk-page" && tab) {
            this.openBulkPicker(tab.id, "all");
        }
        if (info.menuItemId == "bulk-selection" && tab) {
            this.openBulkPicker(tab.id, "selection");
        }
    }

    attachContextMenu() {
        browser.menus.create({
            id: 'download-any-link',
            title: "Download with XDM",
            contexts: ["link", "video", "audio", "all"]
        });

        browser.menus.create({
            id: 'download-image-link',
            title: "Download Image with XDM",
            contexts: ["image"]
        });

        browser.menus.create({
            id: 'bulk-page',
            title: "Download all with XDM…",
            contexts: ["page"]
        });

        browser.menus.create({
            id: 'bulk-selection',
            title: "Download selected links with XDM…",
            contexts: ["selection"]
        });

        browser.menus.onClicked.addListener(this.onMenuClicked.bind(this));
    }

    onCommand(command, tab) {
        if (command !== "open-bulk") {
            return;
        }
        // Older Firefox versions call the listener without the tab.
        if (tab) {
            this.openBulkPicker(tab.id, "all");
            return;
        }
        chrome.tabs.query({ active: true, currentWindow: true }, tabs => {
            if (tabs && tabs[0]) {
                this.openBulkPicker(tabs[0].id, "all");
            }
        });
    }

    /**
     * The picker gets a window of its own: the toolbar popup is too small for a long list, and closes
     * the moment it loses focus. It reads the tab itself, so nothing here has to wait for it.
     */
    openBulkPicker(tabId, mode) {
        if (typeof tabId !== "number" || tabId < 0) {
            return;
        }
        browser.windows.create({
            url: browser.runtime.getURL("app/bulk.html?tab=" + tabId + "&mode=" + encodeURIComponent(mode)),
            type: "popup",
            width: 700,
            height: 600
        });
    }

    /**
     * Hands XDM the links chosen in the bulk picker as one list. None of them were requested by the
     * browser, so - as for the context menu - the cookies are looked up, from the tab's own cookie
     * store so that a private window or a container tab sends its own cookies.
     *
     * A page can offer thousands of links that mostly share a handful of cookie sets, so the list is
     * sent grouped by cookie string, each string once, with the page as the one Referer:
     *
     *   { tabUrl, tabTitle, userAgent, referer,
     *     groups: [ { cookie?: "a=1; b=2", items: [ { url, filename?, mimeType?, fileSize? } ] } ] }
     *
     * A filename is only sent when it is not simply the URL's last segment (a download="" name).
     */
    async sendBatch(request) {
        if (!this.connector.isConnected()) {
            return { ok: false, notRunning: true, error: "XDM isn't running" };
        }
        let items = Array.isArray(request.items) ? request.items : [];
        items = items.filter(item => item && this.isSupportedProtocol(item.url)).slice(0, MAX_BATCH_ITEMS);
        if (items.length === 0) {
            return { ok: false, error: "nothing to send" };
        }
        let storeId = await this.cookieStoreOf(request.tabId);
        let pageHost = "";
        try {
            pageHost = new URL(request.pageUrl).hostname;
        } catch (err) {
            // no page URL: no first-party domain to pick under first-party isolation
        }
        let jar = await this.loadCookies(items.map(item => new URL(item.url)), storeId, pageHost);
        let groups = new Map();
        for (let item of items) {
            let u = new URL(item.url);
            let cookie = this.cookieHeader(jar, u);
            let entry = { url: item.url };
            if (item.filename && item.filename !== this.lastSegment(u)) {
                entry.filename = item.filename;
            }
            if (item.mimeType) {
                entry.mimeType = item.mimeType;
            }
            if (item.fileSize > 0) {
                entry.fileSize = item.fileSize;
            }
            if (!groups.has(cookie)) {
                groups.set(cookie, []);
            }
            groups.get(cookie).push(entry);
        }
        await this.connector.postMessage("/batch", {
            tabUrl: request.pageUrl,
            tabTitle: request.pageTitle,
            userAgent: navigator.userAgent,
            referer: request.pageUrl || undefined,
            groups: Array.from(groups, ([cookie, list]) => cookie ? { cookie: cookie, items: list } : { items: list })
        });
        // A failed post marks the connector disconnected, which is the one failure worth telling apart.
        if (!this.connector.isConnected()) {
            return { ok: false, notRunning: true, error: "XDM isn't running" };
        }
        return { ok: true, count: items.length };
    }

    /** The tab's cookie store: "firefox-default", "firefox-private" or a container's. */
    async cookieStoreOf(tabId) {
        try {
            let tab = await browser.tabs.get(tabId);
            return tab.cookieStoreId;
        } catch (err) {
            return undefined;
        }
    }

    /**
     * Every cookie that could apply to any of the URLs, in one call per base domain rather than one
     * per URL: getAll({domain}) returns the cookies of that domain and all its subdomains, which
     * covers each host and its parent domains. cookieHeader then picks out what applies to each URL.
     *
     * Only unpartitioned cookies are read (no partitionKey), as Chrome reads no CHIPS cookies: the
     * links were found in the top frame. With first-party isolation on, getAll throws unless given a
     * firstPartyDomain; then every isolation key is read and only the page's own is kept - the one
     * whose domain the page's host is, or ends in.
     */
    async loadCookies(urls, storeId, pageHost) {
        let bases = new Set(urls.map(u => this.baseDomainOf(u.hostname)));
        let lists = await Promise.all(Array.from(bases, async base => {
            let query = { domain: base };
            if (storeId) {
                query.storeId = storeId;
            }
            try {
                return await browser.cookies.getAll(query);
            } catch (err) {
                // first-party isolation
            }
            try {
                query.firstPartyDomain = null;
                let all = await browser.cookies.getAll(query);
                return all.filter(cookie => {
                    let fpd = cookie.firstPartyDomain;
                    return !fpd || pageHost === fpd || pageHost.endsWith("." + fpd);
                });
            } catch (err) {
                return [];
            }
        }));
        let jar = new Map();
        for (let cookie of lists.flat()) {
            jar.set(cookie.domain + "\n" + cookie.path + "\n" + cookie.name, cookie);
        }
        return Array.from(jar.values());
    }

    /**
     * The domain to query cookies by: the host's last two labels. Every cookie that can apply to a
     * host is set on the host or one of its parent domains, and all of those end in these two labels,
     * so getAll({domain}) on them never misses one. Under a two-level public suffix ("bbc.co.uk" ->
     * "co.uk") that fetches more than needed; cookieHeader drops the rest. Guessing the registrable
     * domain instead would miss cookies whenever the guess is too narrow ("files.net.ai" when
     * "net.ai" is a site of its own). IP addresses and single labels stay whole.
     */
    baseDomainOf(host) {
        if (/^[\d.]+$/.test(host) || host.indexOf(":") >= 0 || host.indexOf(".") < 0) {
            return host;
        }
        return host.split(".").slice(-2).join(".");
    }

    /** The Cookie header the browser would send to this URL, by the usual domain/path/secure rules. */
    cookieHeader(jar, u) {
        let host = u.hostname;
        let path = u.pathname || "/";
        let secure = u.protocol === "https:" || host === "localhost" || host === "127.0.0.1";
        let matched = jar.filter(cookie => {
            let domain = cookie.domain.replace(/^\./, "");
            if (cookie.hostOnly ? host !== domain : host !== domain && !host.endsWith("." + domain)) {
                return false;
            }
            if (cookie.secure && !secure) {
                return false;
            }
            let cp = cookie.path || "/";
            return path === cp || (path.startsWith(cp) && (cp.endsWith("/") || path.charAt(cp.length) === "/"));
        });
        // Longer paths first, as browsers order them.
        matched.sort((a, b) => (b.path || "").length - (a.path || "").length);
        return matched.map(cookie => cookie.name + "=" + cookie.value).join("; ");
    }

    lastSegment(u) {
        let last = u.pathname.slice(u.pathname.lastIndexOf("/") + 1);
        try {
            return decodeURIComponent(last);
        } catch (err) {
            return last;
        }
    }

    onTabActivated(activeInfo) {
        this.connector.tryConnect("tab activated");
        this.activeTabId = activeInfo.tabId + "";
        this.logger.log("Active tab: " + this.activeTabId);
        this.updateActionIcon();
    }
}
