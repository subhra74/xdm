#!/usr/bin/env bash
#
# Builds the Windows build kit on macOS or Linux: the app jar plus everything
# build-bundle.ps1 needs, so a Windows machine without Maven or this repo can
# link the runtime and build the MSI (PACKAGING.md §0). The AppCDS class list
# comes from the Mac (packaging/cds/liberica.classlist).
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
mkdir -p "$KIT/packaging/cds" "$KIT/packaging/icons" "$KIT/packaging/module" "$KIT/packaging/windows"

# Same relative layout as the repo, so build-bundle.ps1 finds its files from $PSScriptRoot.
cp "$JAR"                                   "$KIT/xdm-app.jar"
cp packaging/build-bundle.ps1               "$KIT/packaging/"
cp packaging/ModularJar.java                "$KIT/packaging/"
cp packaging/module/module-info.java        "$KIT/packaging/module/"
cp packaging/setup-windows.ps1              "$KIT/packaging/"
cp packaging/jit-directives.json            "$KIT/packaging/"
cp packaging/jdk.properties                 "$KIT/packaging/"
cp packaging/icons/xdm.ico                  "$KIT/packaging/icons/"
cp packaging/windows/xdm.wxs packaging/windows/gpl-3.0.rtf "$KIT/packaging/windows/"
# The Liberica class list, recorded on the Mac; both architectures dump their archive from it.
if [[ -s packaging/cds/liberica.classlist ]]; then
  cp packaging/cds/liberica.classlist "$KIT/packaging/cds/"
  CDS_NOTE="The AppCDS class list (packaging\\cds\\liberica.classlist, recorded on the Mac) is included."
else
  CDS_NOTE="No packaging/cds/liberica.classlist in the repo: the MSIs are built without an AppCDS archive."
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

2. Build each architecture:

   powershell -ExecutionPolicy Bypass -File packaging\\build-bundle.ps1 -Arch arm64 -Type msi
   powershell -ExecutionPolicy Bypass -File packaging\\build-bundle.ps1 -Arch x64 -Type msi

   The AppCDS archive is dumped from the class list recorded on the Mac;
   nothing is recorded here.

   $CDS_NOTE

3. Output: build\\dist\\windows-arm64\\xdm-$VERSION-arm64.msi
           build\\dist\\windows-x64\\xdm-$VERSION-x64.msi
EOF

(cd "$OUT_ROOT" && zip -qr "xdm-windows-kit-$VERSION.zip" xdm-windows-kit)

echo ">> kit:  $KIT"
echo ">> zip:  $OUT_ROOT/xdm-windows-kit-$VERSION.zip"
echo ">> $CDS_NOTE"
