"use strict";
xdm.monitoring = {
    //member variable
    lastIcon: '',
    lastPopup: '',
    videoList: [],

    //configurations for network request inspection
    config: {
        blockedHosts: ["update.microsoft.com", "windowsupdate.com", "thawte.com"],
        videoUrls: [".facebook.com|pagelet", "player.vimeo.com/", "instagram.com/p/"],
        fileExts: ["3GP", "7Z", "AVI", "BZ2", "DEB", "DOC", "DOCX", "EXE", "GZ", "ISO",
            "MSI", "PDF", "PPT", "PPTX", "RAR", "RPM", "XLS", "XLSX", "SIT", "SITX", "TAR", "JAR", "ZIP", "XZ"],
        vidExts: ["MP4", "M3U8", "F4M", "WEBM", "OGG", "MP3", "AAC", "FLV", "MKV", "DIVX",
            "MOV", "MPG", "MPEG", "OPUS"],
        blockedMimeList: ["text/javascript", "application/javascript", "text/css", "text/html"],
        mimeList: [],
        videoUrlsWithPostReq: ["ubei/v1/player?key=", "ubei/v1/next?key="]
    },

    //extension state
    state: {
        isXDMUp: true,
        monitoring: true,
        disabled: false
    },

    run: function () {
        xdm.messaging.connectWithApp(
            this.onSync.bind(this),
            this.onDisconnet.bind(this));
        xdm.requestWatcher.attach({
            isMatchingDownload: this.isMatchingDownload.bind(this),
            onDownload: this.onDownload.bind(this),
            onResponse: this.onResponse.bind(this)
        });
        this.setupMenuAndHotkey();
    },

    onSync: function (data) {
        this.state.monitoring = data.enabled;
        this.state.isXDMUp = true;
        this.config.blockedHosts = data.blockedHosts;
        this.config.videoUrls = data.videoUrls;
        this.config.fileExts = data.fileExts;
        this.config.vidExts = data.vidExts;
        this.videoList = data.vidList;
        if (data.mimeList) {
            this.config.mimeList = data.mimeList;
        }
        if (data.blockedMimeList) {
            this.config.blockedMimeList = data.blockedMimeList;
        }
        this.updateBrowserAction();
    },

    onDisconnet: function () {
        this.state.isXDMUp = false;
        this.updateBrowserAction();
    },

    isMatchingDownload: function (download, response) {
        if (xdm.util.isBlocked(download.finalUrl||download.url) || download.method === "POST") {
            xdm.log("blocked: " + (download.finalUrl||download.url));
            return false;
        }
        if (download.filename && xdm.util.hasMatchingExtension(download.filename)) {
            return true;
        }
        if (xdm.util.hasMatchingUrlOrAttachment(response)) {
            return true;
        }
        xdm.log("skip as extension is not maching: " + download.filename);
        return false;
    },

    onDownload: function (download, request, response) {
        var filename = download.filename;
        if (!filename) {
            filename = xdm.util.guessFileName(response);
        }
        xdm.messaging.sendToXDM(request, response, filename, false, download.referrer);
    },

    onResponse: function (request, response) {
        if (this.isMonitoring()) {
            this.detectVideoStream(request, response);
        }
    },

    detectVideoStream: function (request, response) {
        if (!request) return;
        if (xdm.util.isStreamingVideo(response)) {
            if (request.tabId != -1) {
                chrome.tabs.get(
                    request.tabId,
                    function (tab) {
                        xdm.messaging.sendToXDM(request, response, tab.title, true, tab.url);
                    }
                );
                return;
            }
            xdm.messaging.sendToXDM(request, response, xdm.util.guessFileName(response), true);
        }
    },

    isMonitoring: function () {
        return this.state.isXDMUp === true &&
            xdm.monitoring.state.monitoring === true &&
            xdm.monitoring.state.disabled === false;
    },

    updateBrowserAction: function () {
        if (!xdm.monitoring.state.isXDMUp) {
            xdm.monitoring.setBrowserActionPopUp("fatal.html");
            xdm.monitoring.setBrowserActionIcon("icon_blocked.png");
            return;
        }
        xdm.monitoring.setBrowserActionPopUp(xdm.monitoring.state.monitoring ?
            "status.html" : "disabled.html");
        xdm.monitoring.setBrowserActionIcon(xdm.monitoring.state.monitoring &&
            !xdm.monitoring.state.disabled ? "icon.png" : "icon_disabled.png");

        if (xdm.monitoring.videoList && xdm.monitoring.videoList.length > 0) {
            chrome.browserAction.setBadgeText({ text: xdm.monitoring.videoList.length + "" });
        } else {
            chrome.browserAction.setBadgeText({ text: "" });
        }
    },

    setBrowserActionIcon: function (icon) {
        if (xdm.monitoring.lastIcon == icon) {
            return;
        }
        chrome.browserAction.setIcon({ path: icon });
        xdm.monitoring.lastIcon = icon;
    },

    setBrowserActionPopUp: function (pop) {
        if (xdm.monitoring.lastPopup == pop) {
            return;
        }
        chrome.browserAction.setPopup({ popup: pop });
        xdm.monitoring.lastPopup = pop;
    },

    runContentScript: function (info, tab) {
        log("running content script");
        chrome.tabs.executeScript({
            file: 'contentscript.js'
        });
    },

    setupMenuAndHotkey: function () {
        chrome.commands.onCommand.addListener(function (command) {
            if (xdm.monitoring.state.isXDMUp && xdm.monitoring.state.monitoring) {
                xdm.monitoring.state.disabled = !xdm.monitoring.state.disabled;
            }
        });

        chrome.contextMenus.create({
            title: "Download with XDM",
            contexts: ["link", "video", "audio"],
            onclick: this.sendLinkToXDM.bind(this),
        });

        chrome.contextMenus.create({
            title: "Download Image with XDM",
            contexts: ["image"],
            onclick: this.sendImageToXDM.bind(this),
        });

        chrome.contextMenus.create({
            title: "Download all links",
            contexts: ["all"],
            onclick: this.runContentScript,
        });
    },

    sendImageToXDM: function (info, tab) {
        if (info.mediaType && "image" == info.mediaType && info.srcUrl) {
            url = info.srcUrl;
        }
        if (!url) {
            url = info.linkUrl;
        }
        if (!url) {
            url = info.pageUrl;
        }
        if (!url) {
            return;
        }
        xdm.messaging.sendUrlToXDM(url);
    },

    sendLinkToXDM: function (info, tab) {
        var url = info.linkUrl;
        if (!url && info.mediaType && ("video" == info.mediaType || "audio" == info.mediaType) && info.srcUrl) {
            url = info.srcUrl;
        }
        if (!url) {
            url = info.pageUrl;
        }
        if (!url) {
            return;
        }
        xdm.messaging.sendUrlToXDM(url);
    },

    runContentScript: function (info, tab) {
        xdm.log("running content script");
        chrome.tabs.executeScript({
            file: 'contentscript.js'
        });
    }
};

xdm.debug = true;
xdm.monitoring.run();