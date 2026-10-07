"use strict";

/**
 * Everything the bulk picker can offer from one page: links and embedded media from the DOM, plus
 * the resources the page actually fetched (Resource Timing).
 *
 * bulk.js injects collectPage with tabs.executeScript (MV2) as source text - "(" + collectPage +
 * ")(options)" - so it runs in the extension's content-script sandbox for the page: it must be
 * completely self-contained - no imports, no references to anything outside its own body. Firefox
 * resolves the Promise it returns. It only runs when the user asks for a bulk download, and only in
 * the top frame.
 *
 * The DOM walk calls getComputedStyle on every element (and its ::before/::after), which is the one
 * expensive part on a huge page, so it runs in time-boxed slices and yields between them rather than
 * freezing the tab.
 */
export function collectPage(options) {
    const MAX_ITEMS = 10000;
    const SLICE_MS = 8;
    const MAX_TEXT = 200;
    /** Image sizes are measured for at most this many items, within this much time in total. */
    const MAX_MEASURE = 1000;
    const MEASURE_BUDGET_MS = 3000;
    const MEASURE_CONCURRENCY = 16;
    const selectionOnly = options && options.mode === "selection";

    const items = new Map();

    function absolute(raw) {
        if (!raw || typeof raw !== "string") {
            return null;
        }
        raw = raw.trim();
        if (!raw || raw.length > 4096) {
            return null;
        }
        try {
            const u = new URL(raw, document.baseURI);
            if (u.protocol !== "http:" && u.protocol !== "https:") {
                return null;
            }
            u.hash = "";
            return u.href;
        } catch (e) {
            return null;
        }
    }

    function clip(text) {
        if (!text) {
            return "";
        }
        text = String(text).replace(/\s+/g, " ").trim();
        return text.length > MAX_TEXT ? text.slice(0, MAX_TEXT) : text;
    }

    /** Adds a URL, or merges what is known about it into the entry already there. */
    function add(raw, source, extra) {
        const url = absolute(raw);
        if (!url) {
            return;
        }
        let item = items.get(url);
        if (!item) {
            if (items.size >= MAX_ITEMS) {
                return;
            }
            item = { url: url, sources: [] };
            items.set(url, item);
        }
        if (item.sources.indexOf(source) < 0) {
            item.sources.push(source);
        }
        if (extra) {
            for (const key of Object.keys(extra)) {
                const value = extra[key];
                if (value && !item[key]) {
                    item[key] = value;
                }
            }
        }
    }

    /**
     * srcset is split by hand: a candidate URL may itself contain commas (image CDNs put resize
     * parameters there, "w_200,h_100"), so a plain split(",") would cut it apart.
     */
    function parseSrcset(value) {
        const out = [];
        if (!value) {
            return out;
        }
        let i = 0;
        const n = value.length;
        while (i < n) {
            while (i < n && /[\s,]/.test(value[i])) i++;
            if (i >= n) break;
            let start = i;
            while (i < n && !/\s/.test(value[i])) i++;
            let url = value.slice(start, i);
            let descriptor = "";
            if (/,$/.test(url)) {
                url = url.replace(/,+$/, "");
            } else {
                start = i;
                while (i < n && value[i] !== ",") i++;
                descriptor = value.slice(start, i).trim();
            }
            const m = /^([\d.]+)([wx])$/i.exec(descriptor);
            out.push({ url: url, score: m ? parseFloat(m[1]) : 1 });
        }
        return out;
    }

    /** Only the largest candidate: the others are the same picture at a lower resolution. */
    function addSrcset(value, extra) {
        const list = parseSrcset(value);
        if (list.length === 0) {
            return;
        }
        list.sort((a, b) => b.score - a.score);
        add(list[0].url, "srcset", extra);
    }

    /** Every url(...) in a computed style value, which browsers serialise as url("..."). */
    function addCssUrls(value, el) {
        if (!value || value.indexOf("url(") < 0) {
            return;
        }
        // The browser has fetched the images of an element that is laid out (backgrounds are never
        // lazy-loaded); a display:none one has fetched nothing. image-set() lists alternatives of
        // which only one is fetched, so none is assumed (Resource Timing still marks that one).
        const rendered = value.indexOf("image-set(") < 0 && el.getClientRects().length > 0;
        const re = /url\(\s*(?:"((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)'|([^)\s]*))\s*\)/g;
        let m;
        while ((m = re.exec(value)) !== null) {
            const raw = (m[1] !== undefined ? m[1] : m[2] !== undefined ? m[2] : m[3]) || "";
            add(raw.replace(/\\(.)/g, "$1"), "css-bg", { loaded: rendered });
        }
    }

    const STYLE_PROPS = ["backgroundImage", "borderImageSource", "maskImage", "webkitMaskImage",
        "listStyleImage", "content"];
    const PSEUDO_PROPS = ["backgroundImage", "content"];

    function scanStyles(el) {
        const style = getComputedStyle(el);
        for (const prop of STYLE_PROPS) {
            addCssUrls(style[prop], el);
        }
        for (const pseudo of ["::before", "::after"]) {
            const ps = getComputedStyle(el, pseudo);
            if (!ps || ps.content === "none" || ps.content === "normal") {
                continue;
            }
            for (const prop of PSEUDO_PROPS) {
                addCssUrls(ps[prop], el);
            }
        }
    }

    const LAZY_ATTRS = ["data-src", "data-original", "data-lazy-src", "data-lazy", "data-url",
        "data-bg", "data-background", "data-background-image", "data-full", "data-full-src",
        "data-hires", "data-large", "data-large-src", "data-zoom-image", "data-image", "data-img"];
    const LAZY_SRCSET_ATTRS = ["data-srcset", "data-lazy-srcset"];
    const XLINK = "http://www.w3.org/1999/xlink";

    /** Absolute URLs tucked into a link's query string (redirectors, "?file=https://...") */
    function addQueryUrls(href) {
        try {
            const u = new URL(href, document.baseURI);
            for (const value of u.searchParams.values()) {
                if (/^https?:\/\//i.test(value)) {
                    add(value, "a-param");
                }
            }
        } catch (e) {
            // not a URL; nothing to dig out
        }
    }

    function addOnclickUrls(code) {
        if (!code || code.length > 4000) {
            return;
        }
        const re = /https?:\/\/[^\s'"<>()]+/gi;
        let m;
        while ((m = re.exec(code)) !== null) {
            add(m[0], "onclick");
        }
    }

    function scanElement(el) {
        const tag = el.localName;
        switch (tag) {
            case "a":
            case "area": {
                const href = el.getAttribute("href");
                if (href) {
                    add(el.href && el.href.baseVal !== undefined ? el.href.baseVal : el.href, "a", {
                        text: clip(el.textContent || el.getAttribute("title") || el.getAttribute("alt")),
                        name: clip(el.getAttribute("download"))
                    });
                    addQueryUrls(href);
                }
                break;
            }
            case "img": {
                const extra = {
                    text: clip(el.alt || el.title),
                    w: el.naturalWidth || 0,
                    h: el.naturalHeight || 0,
                    // A lazy <img> still off screen has not been fetched yet.
                    loaded: el.complete && el.naturalWidth > 0
                };
                add(el.currentSrc || el.getAttribute("src"), "img", extra);
                addSrcset(el.getAttribute("srcset"));
                break;
            }
            case "source":
                addSrcset(el.getAttribute("srcset"));
                add(el.getAttribute("src"), "media");
                break;
            case "video":
                add(el.getAttribute("poster"), "poster");
                add(el.currentSrc || el.getAttribute("src"), "media", { text: clip(el.title) });
                break;
            case "audio":
                add(el.currentSrc || el.getAttribute("src"), "media", { text: clip(el.title) });
                break;
            case "track":
                add(el.getAttribute("src"), "media");
                break;
            case "object":
                add(el.getAttribute("data"), "object");
                break;
            case "embed":
                add(el.getAttribute("src"), "object");
                break;
            case "image": // SVG
                add(el.getAttribute("href") || el.getAttributeNS(XLINK, "href"), "img");
                break;
            case "input":
                if ((el.getAttribute("type") || "").toLowerCase() === "image") {
                    add(el.getAttribute("src"), "img");
                }
                break;
            case "link": {
                const rel = (el.getAttribute("rel") || "").toLowerCase();
                if (/\b(icon|apple-touch-icon|image_src|preload|prefetch)\b/.test(rel)) {
                    add(el.getAttribute("href"), "icon");
                }
                break;
            }
            case "meta": {
                const prop = (el.getAttribute("property") || el.getAttribute("name") || "").toLowerCase();
                if (/^(og:image|og:image:url|og:image:secure_url|og:video|og:video:url|og:audio|twitter:image|twitter:player:stream)$/.test(prop)) {
                    add(el.getAttribute("content"), "og");
                }
                break;
            }
        }
        for (const attr of LAZY_ATTRS) {
            const value = el.getAttribute(attr);
            if (value) {
                add(value, "lazy");
            }
        }
        for (const attr of LAZY_SRCSET_ATTRS) {
            const value = el.getAttribute(attr);
            if (value) {
                const list = parseSrcset(value).sort((a, b) => b.score - a.score);
                if (list.length > 0) {
                    add(list[0].url, "lazy");
                }
            }
        }
        if (el.hasAttribute("onclick")) {
            addOnclickUrls(el.getAttribute("onclick"));
        }
    }

    /**
     * Closed shadow roots are reachable from a content script: through dom.openOrClosedShadowRoot,
     * or Firefox's own element.openOrClosedShadowRoot property.
     */
    function shadowOf(el) {
        try {
            if (typeof browser !== "undefined" && browser.dom && browser.dom.openOrClosedShadowRoot) {
                return browser.dom.openOrClosedShadowRoot(el) || null;
            }
            if (el.openOrClosedShadowRoot !== undefined) {
                return el.openOrClosedShadowRoot || null;
            }
        } catch (e) {
            // fall through to the open root
        }
        return el.shadowRoot || null;
    }

    /**
     * Yields to the page between slices. A MessageChannel rather than setTimeout: timers in a tab
     * that is not in front (the picker window has focus) can be throttled to once a second.
     */
    function yieldToPage() {
        return new Promise(resolve => {
            const channel = new MessageChannel();
            channel.port1.onmessage = () => resolve();
            channel.port2.postMessage(null);
        });
    }

    function selectionRoots() {
        const sel = window.getSelection();
        const roots = [];
        if (!sel) {
            return { sel: null, roots: roots };
        }
        for (let i = 0; i < sel.rangeCount; i++) {
            let node = sel.getRangeAt(i).commonAncestorContainer;
            if (node.nodeType !== Node.ELEMENT_NODE) {
                node = node.parentElement;
            }
            if (!node) {
                continue;
            }
            roots.push(node);
            // Selecting text inside a link selects none of its descendants, but the link itself counts.
            for (let p = node; p; p = p.parentElement) {
                if (p.localName === "a" || p.localName === "area") {
                    scanElement(p);
                }
            }
        }
        return { sel: sel, roots: roots };
    }

    async function walkDom() {
        let sel = null;
        let roots;
        if (selectionOnly) {
            const r = selectionRoots();
            sel = r.sel;
            roots = r.roots;
        } else {
            roots = [document.documentElement];
        }
        const pending = roots.filter(Boolean);
        let sliceStart = performance.now();
        while (pending.length > 0) {
            const root = pending.pop();
            const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
            let el = root.nodeType === Node.ELEMENT_NODE ? root : walker.nextNode();
            while (el) {
                if (!sel || sel.containsNode(el, true)) {
                    try {
                        scanElement(el);
                        if (!selectionOnly) {
                            scanStyles(el);
                        }
                    } catch (e) {
                        // one odd element must not end the scan
                    }
                }
                const shadow = shadowOf(el);
                if (shadow) {
                    pending.push(shadow);
                }
                if (performance.now() - sliceStart > SLICE_MS) {
                    await yieldToPage();
                    sliceStart = performance.now();
                }
                el = walker.nextNode();
            }
        }
    }

    /**
     * What the page fetched. The always-filled buffer holds 250 entries by default; when the user
     * chose "Reload & capture", rt-observer.js ran from document_start and kept the complete list.
     */
    function readResourceTiming() {
        let entries = [];
        try {
            entries = performance.getEntriesByType("resource");
        } catch (e) {
            entries = [];
        }
        const observed = globalThis.__xdmResourceLog instanceof Map ? globalThis.__xdmResourceLog : null;
        const take = (url, initiator, mime, size, status) => {
            if (status >= 400) {
                return;
            }
            add(url, "rt", { initiator: initiator, mime: mime, size: size > 0 ? size : 0, loaded: true });
        };
        for (const e of entries) {
            take(e.name, e.initiatorType, e.contentType || "", e.decodedBodySize || e.encodedBodySize || 0,
                e.responseStatus || 0);
        }
        if (observed) {
            for (const [url, r] of observed) {
                take(url, r.i, r.m, r.s, r.st);
            }
        }
        return {
            count: entries.length,
            observed: observed !== null,
            // The browser stops filling the buffer at its size (250 unless the page raised it) and drops
            // everything after; when it is full the page probably fetched more than this shows.
            full: observed === null && entries.length >= 250
        };
    }

    /**
     * Width and height for the images the page has already fetched but the DOM gave no size for
     * (CSS backgrounds, srcset picks, script-loaded images), so the picker's minimum-size filter
     * works without previews. Loaded here, in the page, they come out of the page's own cache, and
     * reading the size of a cross-origin image is allowed where reading its pixels is not. Items the
     * page never fetched (lazy images still waiting) are left alone: measuring them would be a new
     * request. A response the server marked uncacheable is fetched again; that is the one cost.
     */
    async function measureImages() {
        const IMAGE_EXT = /\.(jpe?g|jfif|png|apng|gif|webp|avif|bmp|ico|svg|tiff?|heic|heif|jxl)$/i;
        const IMAGE_SOURCES = ["img", "srcset", "css-bg", "poster", "icon"];
        const queue = [];
        for (const item of items.values()) {
            if (!item.loaded || item.w > 0) {
                continue;
            }
            let path = "";
            try {
                path = new URL(item.url).pathname;
            } catch (e) {
                continue;
            }
            const imageLike = (item.mime || "").startsWith("image/") || IMAGE_EXT.test(path)
                || item.initiator === "img" || item.sources.some(s => IMAGE_SOURCES.indexOf(s) >= 0);
            if (imageLike) {
                queue.push(item);
                if (queue.length >= MAX_MEASURE) {
                    break;
                }
            }
        }
        const deadline = performance.now() + MEASURE_BUDGET_MS;
        const measure = item => new Promise(resolve => {
            const img = new Image();
            let timer = 0;
            const finish = () => {
                clearTimeout(timer);
                img.onload = img.onerror = null;
                if (img.naturalWidth > 0) {
                    item.w = img.naturalWidth;
                    item.h = img.naturalHeight;
                }
                resolve();
            };
            timer = setTimeout(finish, Math.max(0, deadline - performance.now()));
            img.onload = finish;
            img.onerror = finish;
            img.src = item.url;
        });
        let next = 0;
        const worker = async () => {
            while (next < queue.length && performance.now() < deadline) {
                await measure(queue[next++]);
            }
        };
        const workers = [];
        for (let i = 0; i < MEASURE_CONCURRENCY; i++) {
            workers.push(worker());
        }
        await Promise.all(workers);
    }

    return (async () => {
        let rt = { count: 0, observed: false, full: false };
        if (!selectionOnly) {
            rt = readResourceTiming();
        }
        await walkDom();
        await measureImages();
        return {
            pageUrl: location.href,
            title: document.title,
            rtCount: rt.count,
            rtObserved: rt.observed,
            rtFull: rt.full,
            truncated: items.size >= MAX_ITEMS,
            items: Array.from(items.values())
        };
    })();
}
