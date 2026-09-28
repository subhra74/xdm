"use strict";
import Logger from './logger.js';
import RequestWatcher from './request-watcher.js';
import Connector from './connector.js';

const HOST_ORIGINS = ["*://*/*"];

export default class App {
    constructor() {
        this.logger = new Logger();
        this.videoList = [];
        this.blockedHosts = [];
        this.fileExts = [];
        this.requestWatcher = new RequestWatcher(this.onRequestDataReceived.bind(this));
        this.userDisabled = false;
        this.appEnabled = false;
        // Whether the extension can see the network. Unknown until checked, and treated as withheld
        // until then: without it a download cannot be replayed, so it must be left to the browser.
        this.hostAccess = false;
        this.onDownloadCreatedCallback = this.onDownloadCreated.bind(this);
        this.onDeterminingFilenameCallback = this.onDeterminingFilename.bind(this);
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
        this.fileExts = msg.fileExts;
        this.blockedHosts = msg.blockedHosts;
        this.videoList = msg.videoList;
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
        this.logger.log(this.appEnabled + " " + this.userDisabled + " " + this.hostAccess);
        return this.appEnabled === true && this.userDisabled === false && this.hostAccess === true
            && this.connector.isConnected();
    }

    // Chrome's "On click" / "On specific sites" site access withholds host permissions while the
    // downloads API keeps firing. The extension then sees no requests, so it has nothing to replay.
    refreshHostAccess() {
        chrome.permissions.contains({ origins: HOST_ORIGINS }, granted => {
            this.hostAccess = granted === true;
            this.logger.log("host access: " + this.hostAccess);
            this.updateActionIcon();
        });
    }

    onRequestDataReceived(data) {
        //Streaming video data received, send to native messaging application
        this.logger.log("onRequestDataReceived");
        this.logger.log(data);
        this.isMonitoringEnabled() && this.connector.isConnected() && this.connector.postMessage("/media", data);
    }

    // Every path calls suggest() exactly once, and every path that does not take the download
    // leaves it completely alone. Only a GET the extension watched, with its real headers in hand,
    // is taken: that is everything XDM needs to replay it, so the browser copy can go at once.
    onDeterminingFilename(download, suggest) {
        this.logger.log("onDeterminingFilename");
        if (!this.isMonitoringEnabled()) {
            suggest();
            return;
        }
        this.logger.log(download);
        let url = download.finalUrl || download.url;
        let observed = this.requestWatcher.findObserved(download);
        if (!observed) {
            if (this.shouldTakeOver(url, download.filename)) {
                // The failure mode of this design is XDM quietly not capturing, so make it visible.
                this.logger.log("Not intercepting, request was not observed: " + url);
            }
            suggest();
            return;
        }
        if (observed.method !== "GET") {
            this.logger.log("Not intercepting " + observed.method + "-originated download: " + url);
            suggest();
            return;
        }
        if (!this.shouldTakeOver(url, download.filename)) {
            suggest();
            return;
        }
        suggest();
        chrome.downloads.cancel(
            download.id,
            () => chrome.downloads.erase({ id: download.id })
        );
        this.sendDownload(observed, download);
    }

    onDownloadCreated(download) {
        this.logger.log("onDownloadCreated");
        this.logger.log(download);
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
        chrome.downloads.onCreated.addListener(
            this.onDownloadCreatedCallback
        );
        chrome.downloads.onDeterminingFilename.addListener(
            this.onDeterminingFilenameCallback
        );
        chrome.tabs.onUpdated.addListener(
            this.onTabUpdateCallback
        );
        chrome.runtime.onMessage.addListener(this.onPopupMessage.bind(this));
        // Both wake the service worker, and both are moments where XDM may have appeared since the
        // worker last ran.
        chrome.runtime.onStartup.addListener(() => this.connector.tryConnect("browser startup"));
        chrome.runtime.onInstalled.addListener(() => this.connector.tryConnect("extension installed"));
        this.requestWatcher.register();
        chrome.permissions.onAdded.addListener(() => this.refreshHostAccess());
        chrome.permissions.onRemoved.addListener(() => this.refreshHostAccess());
        this.refreshHostAccess();
        this.attachContextMenu();
        chrome.tabs.onActivated.addListener(this.onTabActivated.bind(this));
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
        chrome.action.setIcon({ path: this.getActionIcon() });
        let vc = "";
        let len = this.videosForTab(this.activeTabId).length;
        if (len > 0) {
            vc = len + "";
        }
        chrome.action.setBadgeText({ text: vc });
        // Checked first: without host access the connection to XDM fails too (the fetches become
        // plain cross-origin requests whose replies the extension cannot read), so "not running"
        // would be the wrong thing to tell the user.
        if (!this.hostAccess) {
            chrome.action.setPopup({ popup: "./site-access.html" });
            return;
        }
        if (!this.connector.isConnected()) {
            this.logger.log("Not connected...");
            chrome.action.setPopup({ popup: "./error.html" });
            return;
        }
        if (!this.appEnabled) {
            chrome.action.setPopup({ popup: "./disabled.html" });
            return;
        }
        else {
            chrome.action.setPopup({ popup: "./popup.html" });
            return;
            // if (this.videoList && this.videoList.length > 0) {
            //     chrome.action.setBadgeText({ text: this.videoList.length + "" });
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
    // sent - together with what the downloads API settled on for name, size and type.
    sendDownload(observed, download) {
        let data = {
            url: observed.url,
            cookie: observed.cookie,
            requestHeaders: observed.requestHeaders,
            responseHeaders: observed.responseHeaders,
            filename: download.filename,
            fileSize: download.fileSize > 0 ? download.fileSize : undefined,
            mimeType: download.mime,
            referer: download.referrer || undefined,
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
            // answering rather than reporting what the worker happened to know last.
            this.connector.tryConnect("popup opened").then(() => {
                // Resolve the active tab fresh: the MV3 service worker can be torn
                // down, resetting this.activeTabId, so don't rely on it here.
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
    }

    attachContextMenu() {
        chrome.contextMenus.create({
            id: 'download-any-link',
            title: "Download with XDM",
            contexts: ["link", "video", "audio", "all"]
        });

        chrome.contextMenus.create({
            id: 'download-image-link',
            title: "Download Image with XDM",
            contexts: ["image"]
        });

        chrome.contextMenus.onClicked.addListener(this.onMenuClicked.bind(this));
    }

    onTabActivated(activeInfo) {
        this.connector.tryConnect("tab activated");
        this.activeTabId = activeInfo.tabId + "";
        this.logger.log("Active tab: " + this.activeTabId);
        this.updateActionIcon();
    }
}
