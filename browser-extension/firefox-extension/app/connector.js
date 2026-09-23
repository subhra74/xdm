"use strict";

const APP_BASE_URL = "http://127.0.0.1:8597";

/** Floor on how fast the long poll may be re-issued after an immediate empty reply. */
const MIN_POLL_INTERVAL_MS = 1000;

class Connector {
    constructor(onMessage, onDisconnect) {
        this.logger = new Logger();
        this.onMessage = onMessage;
        this.onDisconnect = onDisconnect;
        this.connected = undefined;
        // State version last applied; sent with each poll and used to drop snapshots that arrive
        // out of order, since poll and /sync replies travel on separate connections.
        this.version = 0;
        this.polling = false;
        // The MV2 background page is persistent, so an in-memory id lasts as long as the extension.
        this.clientId = crypto.randomUUID();
    }

    connect() {
        // The long poll is the live channel; this stays as the watchdog that finds XDM again after
        // it is restarted, and re-arms the poll if it ever stops.
        setInterval(this.onTimer.bind(this), 5000);
        this.onTimer();
    }

    onTimer() {
        fetch(APP_BASE_URL + "/sync")
            .then(this.onResponse.bind(this))
            .catch(err => this.disconnect());
    }

    disconnect() {
        this.polling = false;
        this.connected = false;
        this.onDisconnect();
    }

    isConnected() {
        return this.connected;
    }

    onResponse(res) {
        this.connected = true;
        this.startLongPoll();
        return res.json().then(json => this.applyMessage(json)).catch(err => this.disconnect());
    }

    applyMessage(json) {
        if (!json) {
            return;
        }
        if (typeof json.version === "number") {
            if (json.version < this.version) {
                this.logger.log("Ignoring stale state v" + json.version + " (have v" + this.version + ")");
                return;
            }
            this.version = json.version;
        }
        this.onMessage(json);
    }

    startLongPoll() {
        if (this.polling) {
            return;
        }
        this.polling = true;
        this.pollOnce();
    }

    /**
     * Holds one request open against XDM. It comes back early with new state, empty when nothing
     * happened, or - the point of all this - it fails the moment XDM's process goes away and takes
     * the connection with it.
     */
    async pollOnce() {
        if (!this.polling) {
            return;
        }
        const startedAt = Date.now();
        try {
            const res = await fetch(APP_BASE_URL + "/poll", {
                method: "POST",
                body: JSON.stringify({ clientId: this.clientId, version: this.version })
            });
            this.connected = true;
            if (res.status === 200) {
                const json = await res.json();
                if (json && json.bye === true) {
                    this.logger.log("XDM is shutting down");
                    this.disconnect();
                    return;
                }
                this.applyMessage(json);
                this.pollOnce();
                return;
            }
            // 204: nothing to report, or this poll was superseded / turned away. Re-issue, but never
            // in a hot loop.
            const elapsed = Date.now() - startedAt;
            if (elapsed < MIN_POLL_INTERVAL_MS) {
                setTimeout(() => this.pollOnce(), MIN_POLL_INTERVAL_MS - elapsed);
            } else {
                this.pollOnce();
            }
        } catch (err) {
            this.polling = false;
            this.verifyConnection();
        }
    }

    verifyConnection() {
        fetch(APP_BASE_URL + "/sync")
            .then(this.onResponse.bind(this))
            .catch(err => this.disconnect());
    }

    postMessage(url, data) {
        fetch(APP_BASE_URL + url, { method: "POST", body: JSON.stringify(data) })
            .then(this.onResponse.bind(this))
            .catch(err => this.disconnect());
    }

    launchApp() {

    }
}
