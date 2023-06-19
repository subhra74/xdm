"use strict";

const APP_BASE_URL = "http://127.0.0.1:8597";

class Connector {
    constructor(onMessage, onDisconnect) {
        this.logger = new Logger();
        this.onMessage = onMessage;
        this.onDisconnect = onDisconnect;
        this.connected = undefined;
    }

    connect() {
        //This will be replaced with websocket in future versions
        setInterval(this.onTimer.bind(this), 5000);
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