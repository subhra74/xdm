"use strict";
import Logger from './logger.js';
import RequestWatcher from './request-watcher.js';

export default class App {
    constructor() {
        this.logger = new Logger();
        this.videoList = [];
        this.blockedHosts = [];
        this.fileExts = [];
        this.port = undefined;
        this.requestWatcher = new RequestWatcher(this.onRequestDataReceived.bind(this));
        this.tabsWatcher = [];
        this.registered = false;
        this.enabled = true;
        this.appEnabled = false;
        this.onDownloadCreatedCallback = this.onDownloadCreated.bind(this);
        this.onDeterminingFilenameCallback = this.onDeterminingFilename.bind(this);
        this.onTabUpdateCallback = this.onTabUpdate.bind(this);
    }

    start() {
        this.logger.log("starting...");
        this.startNativeHost();
        this.register();
        this.logger.log("started.");
    }

    startNativeHost() {
        this.port = chrome.runtime.connectNative("xdm_chrome.native_host");
        this.port.onMessage.addListener(this.onMessage.bind(this));
        this.port.onDisconnect.addListener(this.onDisconnect.bind(this));
    }

    onMessage(msg) {
        this.logger.log(msg);
        this.registered = true;
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
        this.logger.log(this.registered + " " + this.appEnabled + " " + this.enabled);
        return this.registered === true && this.appEnabled === true && this.enabled === true;
    }

    onRequestDataReceived(data) {
        //Streaming video data received, send to native messaging application
        this.logger.log("onRequestDataReceived");
        this.logger.log(data);
        this.isMonitoringEnabled() && this.port && this.port.postMessage({ download_headers: data });
    }

    onDeterminingFilename(download, suggest) {
        this.logger.log("onDeterminingFilename");
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

    onDownloadCreated(download) {
        this.logger.log("onDownloadCreated");
        this.logger.log(download);
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
        chrome.downloads.onDeterminingFilename.addListener(
            this.onDeterminingFilenameCallback
        );
        chrome.tabs.onUpdated.addListener(
            this.onTabUpdateCallback
        );
        chrome.runtime.onMessage.addListener(this.onPopupMessage.bind(this));
        this.requestWatcher.register();
        this.attachContextMenu();
    }

    isSupportedProtocol(url) {
        if (!url) return false;
        let u = new URL(url);
        return u.protocol === 'http:' || u.protocol === 'https:';
    }

    shouldTakeOver(url, file) {
        let u = new URL(url);
        if (!isSupportedProtocol(url)) {
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
        chrome.action.setIcon({ path: this.getActionIcon() });
        chrome.action.setBadgeText({ text: "" });
        if (this.videoList && this.videoList.length > 0) {
            chrome.action.setBadgeText({ text: this.videoList.length + "" });
        }
        if (!this.registered) {
            this.logger.log("not registered")
            chrome.action.setPopup({ popup: "./error.html" });
            return;
        }
        if (!this.appEnabled) {
            chrome.action.setPopup({ popup: "./disabled.html" });
        }
        else {
            chrome.action.setPopup({ popup: "./popup.html" });
            if (this.videoList && this.videoList.length > 0) {
                chrome.action.setBadgeText({ text: this.videoList.length + "" });
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
}
