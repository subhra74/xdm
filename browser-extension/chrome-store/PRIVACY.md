# XDM Integration Module: privacy policy

*Last updated: 7 October 2026*

XDM Integration Module ("the extension") connects Google Chrome to Xtreme Download Manager ("XDM"),
a desktop download manager that runs on the same computer. This policy explains what the extension
reads, where that data goes, and what it keeps.

## Summary

- The extension sends data to **one place only: the XDM application on your own computer**, at
  `http://127.0.0.1:8597`. That address never leaves your machine.
- The extension has **no server**. It sends nothing to the developer or to any third party, and
  includes no analytics, tracking, advertising or crash reporting.
- Nothing is sold, shared or used for any purpose other than downloading the files you choose.
- On first install only, the extension opens a welcome page on xtremedownloadmanager.com with the
  next setup steps. It sends no data to that page; the website's own privacy policy applies there.

## What the extension reads, and why

| Data | When | Why |
|---|---|---|
| Web addresses (URLs) of downloads and media files, and of the tab they came from | While browser monitoring is on | To hand the download or video to XDM and name it after the page |
| Request and response headers of those requests (e.g. `Content-Type`, `Content-Length`, `Referer`, `User-Agent`) | Same | XDM repeats the exact request the browser made, so the server returns the same file |
| Cookies for the site of a download | Same, or when you choose "Download with XDM" or the bulk picker | Many sites only serve a file to a signed-in visitor; XDM needs the same cookies to fetch it |
| Links, images and media addresses on a page, and the page title | Only when you open the bulk picker ("Download all with XDM…") on that page | To list them so you can choose which ones to download |

The extension does not read form contents, passwords, keystrokes, or the text of the pages you visit.
On YouTube (youtube.com, youtu.be and related domains) the extension captures nothing and sends nothing to XDM.

## Where the data goes

All of the data above is sent only to the XDM application running on your computer, through the
local address `127.0.0.1`. XDM uses it to download the files you asked for, and stores those
downloads and their details on your computer. XDM itself does not send this data anywhere else.

## What the extension stores

- In Chrome's local extension storage: your filter preferences for the bulk picker (selected file
  types, minimum image size, and toggles). No URLs or page content.
- In Chrome's session storage, cleared when Chrome closes: whether you turned browser monitoring off.

Uninstalling the extension removes both.

## Your choices

- Turn **Browser monitoring** off in the extension's popup to stop it taking downloads and detecting
  videos. Downloads then stay with Chrome.
- Limit the extension's **Site access** on `chrome://extensions`. Downloads on sites it cannot access
  are left to Chrome.
- Quit XDM: with XDM not running, the extension does nothing except periodically check whether XDM has started.

## Changes

If this policy changes, the new version will be published at this address with a new date above.

## Contact

Questions about this policy: open an issue at <https://github.com/subhra74/xdm/issues>.
