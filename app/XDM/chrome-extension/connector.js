"use strict";
import Logger from './logger.js';

const APP_BASE_URL = "http://127.0.0.1:8597";

export default class Connector {
    constructor(onMessage, onDisconnect) {
        this.logger = new Logger();
        this.onMessage = onMessage;
        this.onDisconnect = onDisconnect;
        this.connected = undefined;
    }

    connect() {
        for (let i = 0; i < 12; i++) {
            chrome.alarms.create("alerm-" + i, {
                periodInMinutes: 1,
                when: Date.now() + 1000 + ((i + 1) * 5000)
            });
        }
        chrome.alarms.onAlarm.addListener(this.onTimer.bind(this));
    }

    onTimer() {
        fetch(APP_BASE_URL + "/sync")
            .then(this.onResponse.bind(this))
            .catch(err => this.disconnect());
    }

    disconnect() {
        this.connected = false;
        this.onDisconnect();
    }

    isConnected() {
        return this.connected;
    }

    onResponse(res) {
        this.connected = true;
        res.json().then(json => this.onMessage(json)).catch(err => this.disconnect());
    }

    postMessage(url, data) {
        fetch(APP_BASE_URL + url, { method: "POST", body: JSON.stringify(data) })
            .then(this.onResponse.bind(this))
            .catch(err => this.disconnect());
    }

    launchApp() {

    }
}