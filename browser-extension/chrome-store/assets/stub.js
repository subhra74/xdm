"use strict";
/**
 * A stand-in for the chrome.* API, so the real popup.html and bulk.html can be rendered outside the
 * browser for store screenshots. render.sh copies the extension into a temp folder and loads this
 * ahead of the page's own scripts. Only what the two pages call is here, answering with demo data.
 */
(function () {
    // ?yt=1 on popup.html or bulk.html renders the YouTube (blocked-sites.js) state, for checking it.
    const YT = /[?&]yt=1/.test(location.search);
    const DEMO_PAGE = "https://example.org/open-media/";
    const TAB_URL = YT ? "https://www.youtube.com/watch?v=aqz-KE-bpKQ" : DEMO_PAGE;

    const VIDEOS = [
        { id: 1, text: "Big Buck Bunny (2008) – Blender Open Movie.mp4", info: "[mp4] 1080p · 263.4 MB" },
        { id: 2, text: "Big Buck Bunny (2008) – Blender Open Movie.mp4", info: "[mp4] 720p · 151.8 MB" },
        { id: 3, text: "Sintel – Third Open Movie by Blender Foundation", info: "[webm] 1080p · 418.0 MB" },
        { id: 4, text: "Tears of Steel – audio track", info: "[m4a] 128 kbps · 11.2 MB" }
    ];

    const file = (path, text, extra) => Object.assign({ url: DEMO_PAGE + path, sources: ["a"], text: text }, extra || {});
    const LINKS = [
        file("releases/open-movies-collection-2026.zip", "Full collection (zip)", { size: 2147483648 }),
        file("releases/big-buck-bunny-1080p.mp4", "Big Buck Bunny – 1080p"),
        file("releases/big-buck-bunny-4k.mp4", "Big Buck Bunny – 4K"),
        file("releases/sintel-1080p.mkv", "Sintel – 1080p"),
        file("releases/tears-of-steel-1080p.mov", "Tears of Steel – 1080p"),
        file("releases/elephants-dream.avi", "Elephants Dream"),
        file("soundtracks/sintel-ost.flac", "Sintel soundtrack (FLAC)"),
        file("soundtracks/big-buck-bunny-theme.mp3", "Big Buck Bunny theme"),
        file("docs/production-notes.pdf", "Production notes (PDF)"),
        file("docs/licence-cc-by.txt", "Licence"),
        file("tools/blender-4.2-windows-x64.msi", "Blender 4.2 for Windows"),
        file("tools/blender-4.2-macos-arm64.dmg", "Blender 4.2 for macOS"),
        file("tools/blender-4.2-linux-x64.tar.xz", "Blender 4.2 for Linux"),
        file("posters/big-buck-bunny-poster.jpg", "Poster", { sources: ["a", "img"], w: 1920, h: 1080 }),
        file("posters/sintel-poster.png", "Poster", { sources: ["a", "img"], w: 1920, h: 1080 })
    ];

    const lastError = undefined;
    const done = (cb, value) => { if (cb) setTimeout(() => cb(value), 0); return Promise.resolve(value); };

    window.chrome = {
        runtime: {
            lastError: lastError,
            getURL: p => p,
            sendMessage(msg, cb) {
                if (msg.type === "stat") return done(cb, { enabled: true, connected: true, blockedSite: YT, list: YT ? [] : VIDEOS });
                if (msg.type === "bulk-status") return done(cb, { connected: true, batch: true });
                return done(cb, { ok: true, count: 0 });
            }
        },
        storage: {
            local: { get: (k, cb) => done(cb, {}), set: (v, cb) => done(cb) },
            session: { get: (k, cb) => done(cb, {}), set: (v, cb) => done(cb) }
        },
        tabs: {
            get: (id, cb) => done(cb, { id: id, title: "Open Movies – free downloads", url: TAB_URL }),
            query: (q, cb) => done(cb, [{ id: 7, url: TAB_URL }]),
            reload: (id, opts, cb) => done(cb),
            onUpdated: { addListener() {}, removeListener() {} },
            onRemoved: { addListener() {}, removeListener() {} }
        },
        scripting: {
            executeScript: () => Promise.resolve([{ result: {
                pageUrl: DEMO_PAGE, title: "Open Movies – free downloads",
                rtCount: 0, rtObserved: false, rtFull: false, truncated: false,
                items: LINKS.map(item => JSON.parse(JSON.stringify(item)))
            } }]),
            registerContentScripts: () => Promise.resolve(),
            unregisterContentScripts: () => Promise.resolve()
        },
        permissions: { contains: () => Promise.resolve(true), request: (p, cb) => done(cb, true) }
    };
    window.close = function () {};

    // Headless capture runs on virtual time, which can freeze a transition (the toggle) half-way.
    const still = document.createElement("style");
    still.textContent = "*, *::before, *::after { transition: none !important; animation: none !important; }";
    document.head.appendChild(still);
})();
