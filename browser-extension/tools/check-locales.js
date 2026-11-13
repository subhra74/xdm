#!/usr/bin/env node
"use strict";

const fs = require("fs");
const path = require("path");

const localesDir = path.resolve(__dirname, "..", "_locales");
const enFile = path.join(localesDir, "en", "messages.json");

let errors = 0;

function fail(msg) {
    console.error(`ERROR: ${msg}`);
    errors++;
}

if (!fs.existsSync(enFile)) {
    fail(`Base English locale file not found: ${enFile}`);
    process.exit(1);
}

let enMessages;
try {
    enMessages = JSON.parse(fs.readFileSync(enFile, "utf8"));
} catch (e) {
    fail(`Failed to parse en/messages.json: ${e.message}`);
    process.exit(1);
}

const enKeys = Object.keys(enMessages);
console.log(`Loaded base en/messages.json with ${enKeys.length} keys.`);

// Verify key naming and structure in en
for (const [key, val] of Object.entries(enMessages)) {
    if (!/^[a-zA-Z0-9_]+$/.test(key)) {
        fail(`en: invalid key name "${key}". Key names must contain only alphanumeric characters and underscores.`);
    }
    if (!val || typeof val !== "object" || typeof val.message !== "string") {
        fail(`en: key "${key}" must be an object with a string "message" property.`);
    }
}

// Check other locales
const dirs = fs.readdirSync(localesDir, { withFileTypes: true })
    .filter(d => d.isDirectory() && d.name !== "en")
    .map(d => d.name);

for (const lang of dirs) {
    const filePath = path.join(localesDir, lang, "messages.json");
    if (!fs.existsSync(filePath)) {
        fail(`${lang}: missing messages.json`);
        continue;
    }

    let messages;
    try {
        messages = JSON.parse(fs.readFileSync(filePath, "utf8"));
    } catch (e) {
        fail(`${lang}: invalid JSON: ${e.message}`);
        continue;
    }

    const langKeys = Object.keys(messages);
    let keyCount = 0;

    for (const [key, val] of Object.entries(messages)) {
        keyCount++;
        if (!enMessages[key]) {
            fail(`${lang}: key "${key}" does not exist in en/messages.json`);
            continue;
        }
        if (!val || typeof val !== "object" || typeof val.message !== "string") {
            fail(`${lang}: key "${key}" must be an object with a string "message" property.`);
            continue;
        }

        // Verify placeholders consistency
        const enPlaceholderDefs = enMessages[key].placeholders || {};
        const expectedPlaceholders = Object.keys(enPlaceholderDefs).map(p => `$${p.toUpperCase()}$`);

        for (const ph of expectedPlaceholders) {
            if (enMessages[key].message.includes(ph) && !val.message.includes(ph)) {
                fail(`${lang}: key "${key}" is missing placeholder ${ph} present in en`);
            }
        }
    }

    console.log(`Checked ${lang}: ${keyCount}/${enKeys.length} keys translated.`);
}

if (errors > 0) {
    console.error(`Locale validation failed with ${errors} error(s).`);
    process.exit(1);
} else {
    console.log("All locale checks passed successfully.");
}
