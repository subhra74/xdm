"use strict";

class App {

    constructor() {
        this.logger = new Logger();
        this.videoList = [];
        this.blockedHosts = [];
        this.fileExts = [];
        this.port = undefined;
        this.requestWatcher = new RequestWatcher(this.onRequestDataReceived.bind(this));
        this.tabsWatcher = [];
        this.enabled = true;
        this.appEnabled = false;
        this.onDownloadCreatedCallback = this.onDownloadCreated.bind(this);
        this.onTabUpdateCallback = this.onTabUpdate.bind(this);
    }

    start() {
        this.logger.log("starting...");
        this.startNativeHost();
        this.register();
        this.logger.log("started.");
    }

    startNativeHost() {
        this.port = browser.runtime.connectNative("xdmff.native_host");
        this.port.onMessage.addListener(this.onMessage.bind(this));
        this.port.onDisconnect.addListener(this.onDisconnect.bind(this));
    }

    onMessage(msg) {
        this.logger.log("onMessage...");
        this.logger.log(msg);
        this.appEnabled = msg.enabled === true;
        this.fileExts = msg.fileExts;
        this.blockedHosts = msg.blockedHosts;
        this.tabsWatcher = msg.tabsWatcher;
        this.videoList = msg.videoList;
        this.requestWatcher.updateConfig({
            fileExts: msg.requestFileExts,
            blockedHosts: msg.blockedHosts,
            matchingHosts: msg.matchingHosts,
            mediaTypes: msg.mediaTypes
        });
        this.updateActionIcon();
    }

    onDisconnect(p) {
        this.logger.log("Disconnected from native host!");
        this.logger.log(p);
        this.enabled = false;
        this.port = undefined;
        this.updateActionIcon();
    }

    isMonitoringEnabled() {
        this.logger.log(this.appEnabled + " " + this.enabled);
        return this.appEnabled === true && this.enabled === true;
    }

    onRequestDataReceived(data) {
        //Streaming video data received, send to native messaging application
        this.logger.log("onRequestDataReceived...");
        this.logger.log(data);
        this.isMonitoringEnabled() && this.port && this.port.postMessage({ download_headers: data });
    }

    onDownloadCreated(download) {
        console.log("onDownloadCreated");
        if (!this.isMonitoringEnabled()) {
            return;
        }
        this.logger.log(download);
        let url = download.finalUrl || download.url;
        this.logger.log(url);
        if (this.isMonitoringEnabled() && this.shouldTakeOver(url, download.filename)) {
            chrome.downloads.cancel(
                download.id,
                () => chrome.downloads.erase(download.id)
            );
            this.triggerDownload(url, download.filename,
                download.referrer, download.fileSize, download.mime);
        }
    }

    onTabUpdate(tabId, changeInfo, tab) {
        if (!this.isMonitoringEnabled()) {
            return;
        }
        let nativePort = this.port;
        if (changeInfo.title) {
            if (this.tabsWatcher &&
                this.tabsWatcher.find(t => tab.url.indexOf(t) > 0)) {
                this.logger.log("Tab changed: " + changeInfo.title + " => " + tab.url);
                try {
                    nativePort.postMessage({
                        tab_update: {
                            url: tab.url,
                            title: changeInfo.title
                        }
                    });
                } catch (ex) {
                    console.log(ex);
                }
            }
        }
    }

    register() {
        chrome.downloads.onCreated.addListener(
            this.onDownloadCreatedCallback
        );
        chrome.tabs.onUpdated.addListener(
            this.onTabUpdateCallback
        );
        chrome.runtime.onMessage.addListener(this.onPopupMessage.bind(this));
        this.requestWatcher.register();
    }

    shouldTakeOver(url, file) {
        let u = new URL(url);
        if (!(u.protocol === 'http:' || u.protocol === 'https:')) {
            return false;
        }
        let hostName = u.host;
        if (this.blockedHosts.find(item => hostName.indexOf(item) >= 0)) {
            return false;
        }
        let path = file || u.pathname;
        let upath = path.toUpperCase();
        if (this.fileExts.find(ext => upath.endsWith(ext))) {
            return true;
        }
        return false;
    }

    updateActionIcon() {
        chrome.browserAction.setIcon({ path: this.getActionIcon() });
        chrome.browserAction.setBadgeText({ text: "" });
        if (this.videoList && this.videoList.length > 0) {
            chrome.browserAction.setBadgeText({ text: this.videoList.length + "" });
        }
        if (!this.appEnabled) {
            chrome.browserAction.setPopup({ popup: "./app/disabled.html" });
        }
        else {
            chrome.browserAction.setPopup({ popup: "./app/popup.html" });
            if (this.videoList && this.videoList.length > 0) {
                chrome.browserAction.setBadgeText({ text: this.videoList.length + "" });
            }
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
        let nativePort = this.port;
        chrome.cookies.getAll({ "url": url }, cookies => {
            if (cookies) {
                let cookieStr = cookies.map(cookie => cookie.name + "=" + cookie.value).join("; ");
                let headers = ["User-Agent: " + navigator.userAgent];
                if (referer) {
                    headers.push("Referer: " + referer);
                }
                let data = {
                    url: url,
                    cookie: cookieStr,
                    headers: headers,
                    filename: file,
                    fileSize: size,
                    mimeType: mime,
                    type: "download_data"
                };
                this.logger.log(data);
                nativePort.postMessage(data);
            }
        });
    }

    diconnect() {
        this.port && this.port.disconnect();
        this.onDisconnect();
    }

    onPopupMessage(request, sender, sendResponse) {
        this.logger.log(request.type);
        if (request.type === "stat") {
            let resp = {
                enabled: this.isMonitoringEnabled(),
                list: this.videoList
            };
            sendResponse(resp);
        }
        else if (request.type === "cmd") {
            this.enabled = request.enabled;
            this.logger.log("request.enabled:" + request.enabled);
            if (this.enabled && !this.port) {
                this.startNativeHost();
                return;
            }
            this.updateActionIcon();
        }
        else if (request.type === "vid") {
            let vid = request.itemId;
            this.port.postMessage({
                vid: vid + "",
                type: 'vid'
            });
        }
        else if (request.type === "clear") {
            this.port.postMessage({
                clear: true
            });
        }
    }
}
