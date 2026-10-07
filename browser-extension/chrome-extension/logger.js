"use strict";
export default class Logger {
    constructor() {
        this.loggingEnabled = false;
    }

    log(content) {
        if (this.loggingEnabled) {
            console.log(content);
        }
    }
}