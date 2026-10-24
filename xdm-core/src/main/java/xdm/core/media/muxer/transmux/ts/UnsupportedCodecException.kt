// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0

package xdm.core.media.muxer.transmux.ts

/** Thrown when an audio/video stream uses a codec the native transmuxer can't handle; the mux fails. */
class UnsupportedCodecException(message: String) : Exception(message)
