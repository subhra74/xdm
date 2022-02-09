"use strict";
import RequestWatcher from './lib/request-watcher.js';
import Logger from './lib/logger.js';

const logger=new Logger();

const xdmNS = {
    cancelCallback: function () {
        logger.log("download cancelled");
    },
    onDownloadCreated: function (downloadItem) {
        logger.log("onDownloadCreated");
        logger.log(downloadItem);
        chrome.downloads.cancel(
            downloadItem.id,
            this.cancelCallback
        );
    },
    onDeterminingFilename: function (downloadItem, suggest) {
        logger.log("onDeterminingFilename");
        logger.log(downloadItem);
        suggest();
    },
    init: function () {
        logger.log("extension initialized...");
        let requestWatcher = new RequestWatcher();
        chrome.downloads.onCreated.addListener(
            this.onDownloadCreated
        );
        chrome.downloads.onDeterminingFilename.addListener(
            this.onDeterminingFilename
        );
    }
};

xdmNS.init();
