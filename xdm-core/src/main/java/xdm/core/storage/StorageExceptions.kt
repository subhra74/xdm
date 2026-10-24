// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.storage

import java.io.IOException

/**
 * A rename or move target exists and replacing it was not asked for; the source is untouched.
 * Core's own type rather than `java.nio.file.FileAlreadyExistsException`, which Android 7 lacks.
 */
class TargetExistsException(path: String) : IOException(path)

/**
 * Source and target are on different file systems, so a rename cannot work and a copy can.
 * Core's own type rather than `java.nio.file.AtomicMoveNotSupportedException`, which Android 7 lacks.
 */
class CrossDeviceException(from: String, to: String) : IOException("$from -> $to")
