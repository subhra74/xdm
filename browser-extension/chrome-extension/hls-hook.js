"use strict";

/**
 * Runs in the page's own JavaScript world, in every frame, from document_start (registered by
 * app.js together with hls-relay.js). Players load HLS playlists with fetch() or XMLHttpRequest, and
 * webRequest never shows the extension a response body. Some CDNs sign a playlist link for a single
 * request, so when XDM fetches it again it gets a 403 and never learns the resolutions a master
 * playlist lists. This hands the text the player received to hls-relay.js, which forwards it to the
 * background, where it goes along with the /media message for the same URL.
 *
 * What is read, and how:
 * - only a 2xx response that looks like HLS: ".m3u8" in the path, or an mpegurl Content-Type;
 * - at most MAX_CHARS of it, and only text that starts with #EXTM3U is passed on;
 * - fetch: a clone of the response is read, so the page's own body is untouched, and the page gets
 *   the very promise the browser returned; XHR: the text it has already received, after "load".
 * Requests made from workers are not seen; for those XDM fetches the playlist itself, as before.
 */
(() => {
    const FLAG = "__xdmHlsHook";
    if (window[FLAG]) {
        return;
    }
    try {
        Object.defineProperty(window, FLAG, { value: true });
    } catch (e) {
        return;
    }

    /** Longest playlist passed on; real ones are a few KB, masters with many renditions under 100 KB. */
    const MAX_CHARS = 256 * 1024;
    /** Event hls-relay.js listens for. The detail is a JSON string: plain strings cross worlds intact. */
    const EVENT = "xdm-hls-body";

    // Taken now, so a page that replaces them later changes nothing here.
    const doc = document;
    const dispatch = EventTarget.prototype.dispatchEvent;
    const NativeCustomEvent = CustomEvent;
    const NativeTextDecoder = TextDecoder;
    const NativeURL = URL;
    const stringify = JSON.stringify;
    const M3U8_PATH = /\.m3u8(?:$|[/;])/i;
    const MPEGURL = /mpegurl/i;
    const SIGNATURE = /^﻿?\s*#EXTM3U/;

    function looksHls(url, type) {
        if (type && MPEGURL.test(type)) {
            return true;
        }
        try {
            return M3U8_PATH.test(new NativeURL(url, location.href).pathname);
        } catch (e) {
            return false;
        }
    }

    function report(url, text) {
        if (!url || !text || text.length > MAX_CHARS || !SIGNATURE.test(text.slice(0, 64))) {
            return;
        }
        try {
            dispatch.call(doc, new NativeCustomEvent(EVENT, { detail: stringify({ url: url, body: text }) }));
        } catch (e) {
            // A detached document, or a page that broke the globals before we ran.
        }
    }

    /** The response text, or null once it is clearly not a playlist or runs past MAX_CHARS. */
    async function readCapped(res) {
        const reader = res.body && res.body.getReader();
        if (!reader) {
            return null;
        }
        const decoder = new NativeTextDecoder();
        let text = "";
        for (;;) {
            const { done, value } = await reader.read();
            if (done) {
                break;
            }
            text += decoder.decode(value, { stream: true });
            if (text.length > MAX_CHARS || (text.length >= 64 && !SIGNATURE.test(text.slice(0, 64)))) {
                reader.cancel().catch(() => { });
                return null;
            }
        }
        return text + decoder.decode();
    }

    function onFetchResponse(res) {
        try {
            if (!res || !res.ok || res.bodyUsed || !looksHls(res.url, res.headers.get("content-type"))) {
                return;
            }
            const length = Number(res.headers.get("content-length"));
            if (length > MAX_CHARS) {
                return;
            }
            const url = res.url;
            readCapped(res.clone()).then(text => report(url, text), () => { });
        } catch (e) {
            // Never let a failure here reach the page.
        }
    }

    const nativeFetch = window.fetch;
    if (typeof nativeFetch === "function") {
        window.fetch = function fetch(input, init) {
            const promise = nativeFetch.apply(this, arguments);
            try {
                promise.then(onFetchResponse, () => { });
            } catch (e) {
            }
            return promise;
        };
    }

    function onXhrLoad() {
        try {
            if (this.status < 200 || this.status >= 300) {
                return;
            }
            const url = this.responseURL;
            if (!looksHls(url, this.getResponseHeader("content-type"))) {
                return;
            }
            const type = this.responseType;
            let text = null;
            if (type === "" || type === "text") {
                text = this.responseText;
            } else if (type === "arraybuffer" && this.response && this.response.byteLength <= MAX_CHARS) {
                text = new NativeTextDecoder().decode(this.response);
            }
            report(url, text);
        } catch (e) {
        }
    }

    const xhrProto = window.XMLHttpRequest && window.XMLHttpRequest.prototype;
    const nativeSend = xhrProto && xhrProto.send;
    const addListener = EventTarget.prototype.addEventListener;
    if (typeof nativeSend === "function") {
        xhrProto.send = function send(body) {
            try {
                addListener.call(this, "load", onXhrLoad);
            } catch (e) {
            }
            return nativeSend.apply(this, arguments);
        };
    }
})();
