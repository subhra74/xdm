#!/bin/sh
# Installing over a running XDM: end it first, for every user (PACKAGING.md 7.4). Autostart brings it
# back at the next login.
pkill -x xdm-app >/dev/null 2>&1 || true
exit 0
