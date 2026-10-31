#!/usr/bin/env bash
#
# Records the JetBrains Runtime list, packaging/cds/jbr.classlist (Linux builds) on the pinned JDK,
# with the scripted session. Start RecordServer first; see docs/class-list-recording.md section 3.1.
#
#   packaging/recording/record-jbr.sh [--build] [SERVER_URL]
#
RECORD_VENDOR=jbr
source "$(dirname "${BASH_SOURCE[0]}")/record-common.sh"
