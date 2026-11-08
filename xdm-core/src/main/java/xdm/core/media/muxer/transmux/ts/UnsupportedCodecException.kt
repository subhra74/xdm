// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.ts

/** Thrown when an audio/video stream uses a codec the native transmuxer can't handle; the mux fails. */
class UnsupportedCodecException(message: String) : Exception(message)
