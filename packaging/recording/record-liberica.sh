#!/usr/bin/env bash
#
# Records the BellSoft Liberica list, packaging/cds/liberica.classlist (macOS and Windows builds) on the pinned JDK,
# with the scripted session. Start RecordServer first; see docs/class-list-recording.md section 3.1.
#
#   packaging/recording/record-liberica.sh [--build] [SERVER_URL]
#
RECORD_VENDOR=liberica
source "$(dirname "${BASH_SOURCE[0]}")/record-common.sh"
