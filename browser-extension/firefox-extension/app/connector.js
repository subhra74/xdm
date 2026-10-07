"use strict";

const APP_BASE_URL = "http://127.0.0.1:8597";

/** Floor on how fast the long poll may be re-issued after an immediate empty reply. */
const MIN_POLL_INTERVAL_MS = 1000;

/**
 * Floor on how often the extension may reach out to XDM looking for it. Every trigger goes through
 * tryConnect(), so however many events fire, XDM is contacted at most once per this interval - an
 * extension that hammers a local port is something a store reviewer will (rightly) ask about, and
 * nothing here needs to find XDM faster than this.
 */
const MIN_CONNECT_INTERVAL_MS = 5000;

/** How often the idle backstop runs when nothing else is triggering a lookup. */
const WATCHDOG_INTERVAL_MS = 30000;

class Connector {
    constructor(onMessage, onDisconnect) {
        this.logger = new Logger();
        this.onMessage = onMessage;
        this.onDisconnect = onDisconnect;
        this.connected = undefined;
        // State version last applied; sent with each poll and used to drop snapshots that arrive
        // out of order, since poll and /sync replies travel on separate connections. Only comparable
        // within one run of XDM, which is what instanceId identifies.
        this.version = 0;
        this.instanceId = undefined;
        this.polling = false;
        /** When the last request to XDM went out, for the MIN_CONNECT_INTERVAL_MS floor. */
        this.lastAttemptAt = 0;
        // The MV2 background page is persistent, so an in-memory id lasts as long as the extension.
        this.clientId = crypto.randomUUID();
    }

    connect() {
        // The long poll is the live channel and browsing activity is what finds XDM quickly (see the
        // tryConnect callers in app.js); this is just the idle backstop. It used to fire every 5s and
        // fetch unconditionally, which meant a steady trickle of requests even while connected.
        setInterval(this.onTimer.bind(this), WATCHDOG_INTERVAL_MS);
        this.onTimer();
    }

    onTimer() {
        this.tryConnect("watchdog timer");
    }

    disconnect() {
        this.polling = false;
        this.connected = false;
        // Whatever comes next is a different XDM (or the same one restarted): nothing from before
        // is worth comparing against.
        this.version = 0;
        this.instanceId = undefined;
        this.onDisconnect();
    }

    isConnected() {
        return this.connected;
    }

    /**
     * The single place that goes looking for XDM. Anything that hints it may be worth another look
     * calls this - the watchdog, the user browsing, the popup being opened - and it does nothing at
     * all while the long poll is already live, or if it ran within MIN_CONNECT_INTERVAL_MS. So no
     * amount of triggering turns into traffic: while XDM is up this is silent (the parked poll is
     * the channel), and while it is down this is one small request every few seconds at most.
     *
     * Returns a promise that settles once the attempt is over, so a caller that wants to report
     * fresh state (the popup) can wait for it. A refused connection fails immediately, so waiting
     * costs nothing when XDM is down.
     */
    tryConnect(reason) {
        if (this.polling) {
            return Promise.resolve();
        }
        const since = Date.now() - this.lastAttemptAt;
        if (since < MIN_CONNECT_INTERVAL_MS) {
            this.logger.log("Not looking for XDM (" + reason + "): last attempt " + since + "ms ago");
            return Promise.resolve();
        }
        this.lastAttemptAt = Date.now();
        this.logger.log("Looking for XDM (" + reason + ")");
        return fetch(APP_BASE_URL + "/sync")
            .then(this.onResponse.bind(this))
            .catch(err => this.disconnect());
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
        // A version only counts from the start of one XDM run, so a restarted XDM hands out lower
        // numbers than the run before it. Comparing across runs would make every snapshot from the
        // new XDM look stale, and the extension would never notice it had come back up.
        if (json.instanceId && json.instanceId !== this.instanceId) {
            this.logger.log("XDM instance " + json.instanceId + " (was " + this.instanceId + ")");
            this.instanceId = json.instanceId;
            this.version = 0;
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
        this.lastAttemptAt = Date.now();
        fetch(APP_BASE_URL + "/sync")
            .then(this.onResponse.bind(this))
            .catch(err => this.disconnect());
    }

    postMessage(url, data) {
        // A command is a user action: it is never throttled, but it does count as having just
        // reached out, so no lookup follows right behind it.
        this.lastAttemptAt = Date.now();
        return fetch(APP_BASE_URL + url, { method: "POST", body: JSON.stringify(data) })
            .then(this.onResponse.bind(this))
            .catch(err => this.disconnect());
    }

    launchApp() {

    }
}
