"use strict";
export default class RequestWatcher {
    constructor() {
        console.log("RequestWatcher initializing...");
        this.requests = {};
        this.preRequests = {};
    }
}