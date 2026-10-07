#!/usr/bin/env bash
#
# Builds the Windows build kit on macOS or Linux: the app jar plus everything
# build-bundle.ps1 needs, so a Windows machine without Maven or this repo can
# record the class list, link the runtime and build the MSI (PACKAGING.md §0).
#
#   packaging/make-windows-kit.sh               # mvn package, then the kit
#   packaging/make-windows-kit.sh --skip-build  # reuse xdm-app/target/xdm-app.jar
#
# Output: build/windows-kit/xdm-windows-kit/ and build/windows-kit/xdm-windows-kit-<ver>.zip.
# Copy either to the Windows machine and follow its README.txt.
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

SKIP_MAVEN=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--skip-build) SKIP_MAVEN=1; shift ;;
    -h|--help) sed -n '3,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

JAR="xdm-app/target/xdm-app.jar"
if [[ $SKIP_MAVEN -eq 0 ]]; then
  echo ">> mvn package"
  mvn -q -pl xdm-app -am package -DskipTests
fi
[[ -f "$JAR" ]] || { echo "Missing $JAR - run without --skip-build" >&2; exit 1; }

# The version build-bundle.ps1 will use: the one inside the jar.
VERSION="$(unzip -p "$JAR" app-version.json | sed -n 's/.*"currentVersion" *: *"\([^"]*\)".*/\1/p')"
[[ -n "$VERSION" ]] || { echo "No currentVersion in $JAR!/app-version.json" >&2; exit 1; }

OUT_ROOT="$PROJECT_ROOT/build/windows-kit"
KIT="$OUT_ROOT/xdm-windows-kit"
rm -rf "$KIT" "$OUT_ROOT"/xdm-windows-kit-*.zip
mkdir -p "$KIT/packaging/cds" "$KIT/packaging/icons" "$KIT/packaging/windows"

# Same relative layout as the repo, so build-bundle.ps1 finds its files from $PSScriptRoot.
cp "$JAR"                                   "$KIT/xdm-app.jar"
cp packaging/build-bundle.ps1               "$KIT/packaging/"
cp packaging/setup-windows.ps1              "$KIT/packaging/"
cp packaging/jit-directives.json            "$KIT/packaging/"
cp packaging/icons/xdm.ico                  "$KIT/packaging/icons/"
cp packaging/windows/xdm.wxs packaging/windows/gpl-3.0.rtf "$KIT/packaging/windows/"
# Only the Windows lists, one per architecture (recorded on that architecture's runtime).
MISSING=()
for a in arm64 x64; do
  if [[ -s "packaging/cds/windows-$a.classlist" ]]; then
    cp "packaging/cds/windows-$a.classlist" "$KIT/packaging/cds/"
  else
    MISSING+=("$a")
  fi
done
if [[ ${#MISSING[@]} -eq 0 ]]; then
  CDS_NOTE="Class lists for arm64 and x64 are included: -RecordServer is optional (re-records them)."
else
  CDS_NOTE="No class list yet for: ${MISSING[*]}. Build those with -RecordServer (or -RecordClasses)."
fi

# CRLF so Notepad shows it properly.
sed 's/$/\r/' > "$KIT/README.txt" <<EOF
XDM $VERSION - Windows build kit (see PACKAGING.md section 0 in the repo)

Open PowerShell in this folder. Scripts copied from another machine are not
signed, so run them through -ExecutionPolicy Bypass as shown.

1. Once per machine: check (and optionally install) the tools.

   powershell -ExecutionPolicy Bypass -File packaging\\setup-windows.ps1
   powershell -ExecutionPolicy Bypass -File packaging\\setup-windows.ps1 -InstallJdk -InstallWix

   One JDK 25 per target architecture; on an ARM64 machine it checks arm64 and
   x64. -InstallJdk unpacks Liberica JDK 25 zips into %USERPROFILE%\\jdks for
   the missing ones (an installed Liberica is used as is). -InstallWix installs
   the .NET SDK and the WiX 7 CLI.

2. Quit any running XDM, then build each architecture. Each one has its own
   class list (packaging\\cds\\windows-<arch>.classlist). To record it, start
   the recording server on the Mac (java packaging/recording/RecordServer.java,
   PACKAGING.md "Recording the class list") and add its URL:

   powershell -ExecutionPolicy Bypass -File packaging\\build-bundle.ps1 -Arch arm64 -Type msi -RecordServer http://<mac>:8780
   powershell -ExecutionPolicy Bypass -File packaging\\build-bundle.ps1 -Arch x64 -Type msi -RecordServer http://<mac>:8780

   XDM starts from the fresh runtime on a throwaway profile, runs the scripted
   session (about two minutes) and exits; the build then continues: AppCDS
   archive, app image, MSI. Leave -RecordServer out to reuse the lists as they
   are; the build warns when a list has gone stale (new JDK or app changes).

   $CDS_NOTE

3. Output: build\\dist\\windows-arm64\\xdm-$VERSION-arm64.msi
           build\\dist\\windows-x64\\xdm-$VERSION-x64.msi
   If you recorded, copy packaging\\cds\\windows-*.classlist back to the repo
   and commit them.
EOF

(cd "$OUT_ROOT" && zip -qr "xdm-windows-kit-$VERSION.zip" xdm-windows-kit)

echo ">> kit:  $KIT"
echo ">> zip:  $OUT_ROOT/xdm-windows-kit-$VERSION.zip"
echo ">> $CDS_NOTE"
