#!/usr/bin/env node
"use strict";

const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const src = path.join(root, "_locales");
const targets = [
    path.join(root, "chrome-extension", "_locales"),
    path.join(root, "firefox-extension", "_locales")
];

if (!fs.existsSync(src)) {
    console.error("sync-locales: _locales directory not found at " + src);
    process.exit(1);
}

for (const target of targets) {
    fs.rmSync(target, { recursive: true, force: true });
    fs.cpSync(src, target, { recursive: true });
    console.log(`sync-locales: copied _locales -> ${path.relative(root, target)}`);
}
