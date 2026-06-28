package xdm.core.media.muxer.transmux.ts

/** Thrown when a stream uses a codec the native transmuxer can't handle (caller falls back). */
class UnsupportedCodecException(message: String) : Exception(message)
