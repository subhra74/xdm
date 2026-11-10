# Xtreme Download Manager: privacy policy

*Last updated: 8 November 2026*

Xtreme Download Manager ("XDM") is a free, open-source download manager for Windows, macOS, Linux
and Android. This policy explains what the XDM applications send over the network, what they keep on
your device, and what they share. The desktop app is covered first; the Android app has its own
section, [XDM for Android](#xdm-for-android). The companion browser extension has its own policy:
[browser-extension/chrome-store/PRIVACY.md](browser-extension/chrome-store/PRIVACY.md).

## Summary

- XDM has **no accounts, no analytics, no tracking, no advertising and no crash reporting**.
- XDM sends nothing about you or your downloads to the developer or to any third party.
- Everything XDM stores stays **on your computer**, in its own data folder.
- The only network connections XDM makes on its own are the downloads you ask for and a check for
  new versions.

## Network connections

| Connection | When | What is sent |
|---|---|---|
| The servers of the files you download | When you start or resume a download | The request needed to fetch the file: its address, and the headers and cookies the browser used for it (see below) |
| `api.github.com` (GitHub releases API) | Once at startup | A plain request for the latest XDM version number. Nothing about you or your downloads is included; GitHub sees your IP address as with any web request |
| The proxy server you configured | When you set up a proxy | Download traffic goes through it, and proxy credentials when the proxy asks for them |
| A local server on `127.0.0.1:8597` | While XDM runs | Not a connection to the internet: it accepts downloads and detected videos from the XDM browser extension on the same computer only |

When the update check finds a newer version, XDM shows a banner. Clicking it opens
[xtremedownloadmanager.com](https://xtremedownloadmanager.com/) in your browser, where the website's
own privacy policy applies.

## Data from the browser extension

When the extension hands a download to XDM, it passes the file's address, the page it came from,
the request headers (such as `User-Agent` and `Referer`) and the site's cookies for that download.
XDM uses them only to make the same request the browser would, so the server returns the same file.
They are sent only to the server hosting that file.

## What XDM stores on your computer

XDM keeps its state in a data folder that only your user account can read:

- Windows: `%LOCALAPPDATA%\xdm-app`
- macOS: `~/Library/Application Support/xdm-app`
- Linux: `$XDG_DATA_HOME/xdm-app` (usually `~/.local/share/xdm-app`)

It contains:

- Your settings.
- The download list: file names, addresses, sizes, save locations and progress.
- Per-download state needed to resume, including the captured headers and cookies, and the partly
  downloaded data.
- Log files, which can include download addresses and errors, for troubleshooting.

Usernames and passwords you type when a server or proxy asks for them are kept in memory only for the
current session; XDM does not write passwords to disk.

Finished files are saved where you choose. Like a web browser, XDM marks them as downloaded from the
internet (the `Zone.Identifier` stream on Windows, the quarantine attribute on macOS, the origin
attributes on Linux), recording the file's address and the page it came from, so your system's
security checks still apply to them.

Deleting a download from the list removes its stored state. Uninstalling XDM and deleting the data
folder above removes everything XDM kept.

## XDM for Android

The Android app follows the same rules: **no accounts, no analytics, no tracking, no advertising and
no crash reporting**, and nothing about you, your browsing or your downloads is sent to the developer
or to any third party. It has no update check of its own; updates come from the store you installed it
from.

### Network connections

| Connection | When | What is sent |
|---|---|---|
| The websites you open in XDM's browser | When you browse | What any web browser sends: the page request, and the cookies and data those sites set |
| The servers of the files you download | When you start or resume a download | The request needed to fetch the file: its address, and the headers and cookies the page used for it |
| Your search engine (Google by default) | When you type a search in the address bar | Your search words, as with any browser |
| Filter-list sites (for example easylist.to, filters.adtidy.org, ublockorigin.github.io, cdn.jsdelivr.net) | About once a day on Wi-Fi, only for ad-blocking lists you added yourself | A plain request for the list. The built-in lists ship inside the app and are not downloaded |

### What the app stores on your device

Everything stays in the app's private storage, which other apps cannot read:

- Your settings, and the per-site browser choices (ad blocking, pop-ups, JavaScript, desktop mode).
- The download list and the state needed to resume downloads, including the headers and cookies
  captured for them.
- Browser history and bookmarks, and the cookies and site data the WebView keeps, like any browser.
  You can clear history from the Library screen. Private tabs keep no history, and their cookies and
  site data are deleted when they close.
- Certificate exceptions: sites whose certificate you chose to trust after a warning (the site name, the
  certificate's fingerprint and the date). You can review and remove them in Settings.
- Ad-blocking lists you added.

Finished files are saved in the folder you choose (`Download/XDM` by default). Uninstalling the app
removes everything in its private storage; your downloaded files stay where they were saved.

### Permissions

- **Internet and network state:** to browse and download, and to wait for Wi-Fi when you ask it to.
- **Notifications, foreground service, wake lock:** to show download progress and keep downloads
  running while the app is in the background.
- **Storage (Android 7 to 10 only):** to save files in the `Download` folder.

When you switch to the app, it checks the clipboard for a copied link and offers to download it. The
clipboard is read on your device only and is never sent anywhere.

## Data sharing

XDM does not sell, share or transfer your data to anyone. Logs and settings leave your device only
if you choose to attach them to a bug report yourself.

## Children

XDM collects no personal information from anyone, including children.

## Changes

If this policy changes, the new version will be published in this repository with a new date above.

## Contact

Questions about this policy: open an issue at <https://github.com/subhra74/xdm/issues>.
