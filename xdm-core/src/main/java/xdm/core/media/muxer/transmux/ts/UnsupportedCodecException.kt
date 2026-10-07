package xdm.core.media.muxer.transmux.ts

/** Thrown when an audio/video stream uses a codec the native transmuxer can't handle; the mux fails. */
class UnsupportedCodecException(message: String) : Exception(message)
