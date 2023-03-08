"use strict";
export default class Logger {
    constructor() {
        this.loggingEnabled = true;
        let manifest = null;
        if (chrome && chrome.runtime && chrome.runtime.getManifest) {
            manifest = chrome.runtime.getManifest();
        } else if (runtime && runtime.getManifest) {
            manifest = chrome.getManifest();
        }
        if (manifest) {
            this.loggingEnabled = !manifest.update_url;
        }
    }

    log(content) {
        if (this.loggingEnabled) {
            console.log(content);
        }
    }
}