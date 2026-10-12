#!/bin/bash
# Records packaging/cds/linux-<arch>.classlist without a Linux desktop: runs inside the Linux build image
# (xdm-linux-build:<arch>, from build-linux.sh), with headless Sway as the Wayland compositor, so XDM uses JBR's
# native Wayland toolkit (WLToolkit) as on a GNOME Wayland desktop. The scripted session (RecordingSession) runs
# against packaging/recording/RecordServer.java on the host. See dist.md, Linux B.
#
# Needs, under the repo (mounted at /src):
#   build/linux-<arch>/xdm-app-<ver>-linux-<arch>.tar.gz   from build-linux.sh: its runtime and launcher options are used
#   xdm-app/target/xdm-app.jar                              from mvn package: it still has xdm/app/recording
#
# Usage (from the repo root on the host):
#   docker run --rm --platform linux/amd64 -v "$PWD:/src" xdm-linux-build:x64 \
#     bash /src/packaging/linux/record-classlist.sh http://<host-LAN-IP>:<port>
# Use the host's LAN address, not host.docker.internal: the session's proxy step makes the server's proxy fetch a
# URL with that host name, and only a name both sides resolve works.
set -u
SERVER="${1:?usage: record-classlist.sh http://<host-LAN-IP>:<port>}"
case "$(uname -m)" in x86_64) ARCH=x64 ;; aarch64) ARCH=arm64 ;; *) echo "unsupported arch $(uname -m)"; exit 2 ;; esac
TARBALL=$(ls /src/build/linux-$ARCH/xdm-app-*-linux-$ARCH.tar.gz 2>/dev/null | head -1)
[ -n "$TARBALL" ] || { echo "!! no build/linux-$ARCH/xdm-app-*-linux-$ARCH.tar.gz - run packaging/build-linux.sh first"; exit 2; }
[ -f /src/xdm-app/target/xdm-app.jar ] || { echo "!! no xdm-app/target/xdm-app.jar - run mvn package first"; exit 2; }
LIST=/src/packaging/cds/linux-$ARCH.classlist

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null && apt-get install -y -qq --no-install-recommends sway fonts-dejavu-core fontconfig curl >/dev/null 2>&1
echo ">> $(sway --version 2>&1), $(basename "$TARBALL")"
useradd -m rec 2>/dev/null

W=/work; rm -rf $W; mkdir -p $W/home
tar -xzf "$TARBALL" -C $W
ROOT=$(ls -d $W/xdm-app-*/ | head -1); APP=${ROOT}lib/app; RT=${ROOT}lib/runtime
cp /src/xdm-app/target/xdm-app.jar $W/rec.jar
# The stamp build-bundle.sh writes and checks: "<java.vendor> <java.runtime.version>".
props=$($RT/bin/java -XshowSettings:properties -version 2>&1)
STAMP="$(sed -n 's/^ *java\.vendor = //p' <<<"$props") $(sed -n 's/^ *java\.runtime\.version = //p' <<<"$props")"
echo ">> runtime: $STAMP"
echo ">> server: $(curl -s -m 5 -o /dev/null -w '%{http_code}' "$SERVER/session.properties")"

# Headless Sway: wlroots always offers a seat, which WLToolkit needs (headless Weston 9 has none). Sway refuses to
# run as root, so it and XDM run as "rec".
export XDG_RUNTIME_DIR=/tmp/xdg; mkdir -p $XDG_RUNTIME_DIR; chown rec $XDG_RUNTIME_DIR; chmod 700 $XDG_RUNTIME_DIR; chown -R rec $W
printf 'output HEADLESS-1 resolution 1600x1000\n' > /tmp/sway.cfg
su rec -c "XDG_RUNTIME_DIR=$XDG_RUNTIME_DIR WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_RENDERER=pixman sway -c /tmp/sway.cfg > $W/sway.log 2>&1 &"
for i in $(seq 1 30); do [ -S $XDG_RUNTIME_DIR/wayland-1 ] && break; sleep 0.5; done
[ -S $XDG_RUNTIME_DIR/wayland-1 ] || { echo "!! sway did not start"; cat $W/sway.log; exit 2; }

# The package's launcher options, as the recording in build-bundle.sh uses them.
mapfile -t OPTS < <(sed -n 's/^java-options=//p' $APP/xdm-app.cfg | grep -v '^-Djpackage' | sed "s#\$APPDIR#$APP#g")
printf '%q ' "$RT/bin/java" "${OPTS[@]}" "-Duser.home=$W/home" "-Dxdm.record.session=$SERVER" \
  "-XX:DumpLoadedClassList=$W/recorded.classlist" -cp "$W/rec.jar" xdm.app.AppMain > $W/cmd.sh
echo ">> recording $(basename $LIST) (Wayland, scripted session; ~3 min native, longer emulated)"
start=$(date +%s)
su rec -c "XDG_RUNTIME_DIR=$XDG_RUNTIME_DIR WAYLAND_DISPLAY=wayland-1 XDG_SESSION_TYPE=wayland bash $W/cmd.sh" > $W/session.log 2>&1
status=$?
echo ">> session exit=$status after $(( $(date +%s) - start )) s"
grep '^RECORD: ' $W/session.log
if [ $status -ne 0 ] || ! grep -q "RECORD: all steps passed" $W/session.log; then
  echo "!! recording failed - $(basename $LIST) not changed"; tail -30 $W/session.log; exit 1
fi
# As build-bundle.sh: stamp first, the session's own classes left out.
{ echo "# xdm-jdk: $STAMP"; grep -v 'xdm/app/recording/' $W/recorded.classlist; } > "$LIST"
echo ">> wrote packaging/cds/$(basename $LIST): $(grep -vc '^[#@]' "$LIST") classes" \
  "($(grep -c '^sun/awt/wl/' "$LIST") Wayland toolkit, $(grep -c '^sun/awt/X11/' "$LIST") X11)"
