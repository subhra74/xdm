"use strict";
xdm.util = {
    parseUrl: function (str) {
        var match = str.match(/^(https?\:)\/\/(([^:\/?#]*)(?:\:([0-9]+))?)([\/]{0,1}[^?#]*)(\?[^#]*|)(#.*|)$/);
        return match && {
            href: str,
            protocol: match[1],
            host: match[2],
            hostname: match[3],
            port: match[4],
            pathname: match[5],
            search: match[6],
            hash: match[7]
        }
    },
    isBlocked: function (url) {
        var blockedHosts = xdm.monitoring.config.blockedHosts;
        for (var i = 0; i < blockedHosts.length; i++) {
            var hostName = this.parseUrl(url).hostname;
            if (hostName.indexOf(blockedHosts[i]) >= 0) {
                return true;
            }
        }
        return false;
    },
    isBlockedMime: function (mimeType) {
        var blockedMimeList = xdm.monitoring.config.blockedMimeList;
        for (var i = 0; i < blockedMimeList.length; i++) {
            if (mimeType && mimeType.indexOf(blockedMimeList[i]) >= 0) {
                return true;
            }
        }
        return false;
    },
    getFileExtension: function (file) {
        if (file) {
            var index = file.lastIndexOf(".");
            if (index > 0) {
                return file.substr(index + 1);
            }
        }
    },
    hasMatchingExtension: function (file) {
        var ext = this.getFileExtension(file);
        if (ext) {
            var fileExts = xdm.monitoring.config.fileExts;
            for (var i = 0; i < fileExts.length; i++) {
                if (fileExts[i] == ext.toUpperCase()) {
                    return true;
                }
            }
        }
        return false;
    },
    getFileFromContentDisposition: function (str) {
        var arr = str.split(";");
        for (var i = 0; i < arr.length; i++) {
            var ln = arr[i].trim();
            if (ln.indexOf("filename=") != -1) {
                var arr2 = ln.split("=");
                return arr2[1].replace(/"/g, '').trim();
            }
        }
    },
    getAttachedFile: function (response) {
        for (var i = 0; i < response.responseHeaders.length; i++) {
            if (response.responseHeaders[i].name.toLowerCase() == 'content-disposition') {
                return this.getFileFromContentDisposition(response.responseHeaders[i].value);
            }
        }
    },
    getFileFromUrl: function (str) {
        return this.parseUrl(str).pathname;
    },
    hasMatchingUrlOrAttachment: function (response) {
        if (!response) {
            return false;
        }
        var file = this.getAttachedFile(response);
        if (file && this.hasMatchingExtension(file)) {
            return true;
        }
        file = this.getFileFromUrl(response.url);
        if (file && this.hasMatchingExtension(file)) {
            return true;
        }
        return false;
    },
    guessFileName: function (response) {
        if (!response) {
            return;
        }
        var file = this.getAttachedFile(response);
        if (file) {
            return file;
        }
        file = this.getFileFromUrl(response.url);
        if (file) {
            return file;
        }
    },
    getResponseMime: function (response) {
        if (!response) return;
        for (var i = 0; i < response.responseHeaders.length; i++) {
            if (response.responseHeaders[i].name.toLowerCase() == "content-type") {
                return response.responseHeaders[i].value.toLocaleLowerCase();
            }
        }
    },
    isVideoMime: function (mimeText) {
        var mimeList = xdm.monitoring.config.mimeList;
        if (!mimeList) {
            return false;
        }
        var mime = mimeText.toLowerCase();
        for (var i = 0; i < mimeList.length; i++) {
            if (mime.indexOf(mimeList[i]) != -1) {
                return true;
            }
        }
        return false;
    },
    isKnownStreamingUrl: function (url) {
        var videoUrls = xdm.monitoring.config.videoUrls;
        if (!videoUrls) {
            return false;
        }
        for (var i = 0; i < videoUrls.length; i++) {
            var arr = videoUrls[i].split("|");
            var matched = true;
            for (var j = 0; j < arr.length; j++) {
                if (url.indexOf(arr[j]) < 0) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return true;
            }
        }
    },
    hasVideoFileExtension: function (url) {
        var vidExts = xdm.monitoring.config.vidExts;
        if (!vidExts) return false;
        if (!url) return false;
        var file = this.getFileFromUrl(url);
        if (!file) return false;
        var ext = this.getFileExtension(file);
        if (!ext) return false;
        ext = ext.toUpperCase();
        for (var i = 0; i < vidExts.length; i++) {
            if (vidExts[i] == ext) {
                return true;
            }
        }
    },
    isStreamingVideo: function (response) {
        var mime = this.getResponseMime(response);
        if (mime && (mime.startsWith("audio/") || mime.startsWith("video/") ||
            mime.indexOf("mpegurl") > 0 || mime.indexOf("f4m") > 0 ||
            this.isVideoMime(mime))) {
            return true;
        }
        if (response.url && (this.isKnownStreamingUrl(response.url) ||
            this.hasVideoFileExtension(response.url))) {
            return true;
        }
        return false;
    },
    addToValueList: function (dict, key, value) {
        var values = dict[key];
        if (values) {
            values.push(value);
        }
        dict[key] = [value];
    },
    arrayBufferToBase64: function (buffer) {
        var binary = '';
        var bytes = new Uint8Array(buffer);
        var len = bytes.byteLength;
        for (var i = 0; i < len; i++) {
            binary += String.fromCharCode(bytes[i]);
        }
        return window.btoa(binary);
    }
};