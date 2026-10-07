"use strict";

/**
 * Registered only for "Reload & capture" in the bulk picker: runs at document_start in the top
 * frame of the reloaded page, then is unregistered again. Nothing else ever injects it.
 *
 * The browser keeps just 250 Resource Timing entries per document and silently drops the rest; an
 * observer started this early sees every entry regardless. The list stays in this page's isolated
 * world, where collectPage (collector.js) reads it, and goes away with the page.
 */
(() => {
    if (globalThis.__xdmResourceLog instanceof Map) {
        return;
    }
    const MAX_ENTRIES = 5000;
    // Scripts and stylesheets are never offered, so there is no point holding on to them.
    const SKIP_EXT = /\.(m?js|cjs|css|map)(?:$|[?#])/i;
    const log = new Map();
    globalThis.__xdmResourceLog = log;

    function record(list) {
        for (const e of list.getEntries()) {
            if (log.size >= MAX_ENTRIES) {
                return;
            }
            if (e.initiatorType === "script" || SKIP_EXT.test(e.name) || log.has(e.name)) {
                continue;
            }
            log.set(e.name, {
                i: e.initiatorType,
                m: e.contentType || "",
                s: e.decodedBodySize || e.encodedBodySize || 0,
                st: e.responseStatus || 0
            });
        }
    }

    try {
        new PerformanceObserver(record).observe({ type: "resource", buffered: true });
    } catch (e) {
        // No observer support: the picker falls back to the regular buffer.
    }
})();
