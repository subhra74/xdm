"use strict";

class App {

    constructor() {
        this.logger = new Logger();
        this.videoList = [];
        this.blockedHosts = [];
        this.fileExts = [];
        this.requestWatcher = new RequestWatcher(this.onRequestDataReceived.bind(this), this.isMonitoringEnabled.bind(this));
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
        this.fileExts = msg.fileExts;
        this.blockedHosts = msg.blockedHosts;
        this.videoList = msg.videoList;
        this.requestWatcher.updateConfig({
            blockedHosts: msg.blockedHosts,
            fileExts: msg.fileExts,
            mediaExts: msg.requestFileExts,
            matchingHosts: msg.matchingHosts,
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
        if (this.isMonitoringEnabled() && this.connector.isConnected()) {
            if (data.download) {
                this.connector.postMessage("/download", data);
            } else {
                this.connector.postMessage("/media", data);
            }
        }
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
        this.requestWatcher.register();
        this.attachContextMenu();
        chrome.tabs.onActivated.addListener(this.onTabActivated.bind(this));
    }
    
    isSupportedProtocol(url) {
        if (!url) return false;
        let u = new URL(url);
        return u.protocol === 'http:' || u.protocol === 'https:';
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
                sendResponse({
                    enabled: this.isMonitoringEnabled(),
                    connected: this.connector.isConnected() === true,
                    list: this.videosForTab(this.activeTabId)
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

        browser.menus.onClicked.addListener(this.onMenuClicked.bind(this));
    }

    onTabActivated(activeInfo) {
        this.connector.tryConnect("tab activated");
        this.activeTabId = activeInfo.tabId + "";
        this.logger.log("Active tab: " + this.activeTabId);
        this.updateActionIcon();
    }
}
