# Xtreme Download Manager: privacy policy

*Last updated: 17 October 2026*

Xtreme Download Manager ("XDM") is a free, open-source desktop download manager for Windows, macOS
and Linux. This policy explains what the XDM application sends over the network, what it keeps on
your computer, and what it shares. The companion browser extension has its own policy:
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

## Data sharing

XDM does not sell, share or transfer your data to anyone. Logs and settings leave your computer only
if you choose to attach them to a bug report yourself.

## Children

XDM collects no personal information from anyone, including children.

## Changes

If this policy changes, the new version will be published in this repository with a new date above.

## Contact

Questions about this policy: open an issue at <https://github.com/subhra74/xdm/issues>.
