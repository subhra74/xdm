// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.es

import xdm.core.media.muxer.transmux.io.ParsableBitArray
import xdm.core.media.muxer.transmux.sample.Track

/**
 * The start of the video usability information, shared by H.264 (E.1.1) and H.265 (E.2.1):
 * sample aspect ratio and colour description, which ffmpeg writes as the MP4 pasp/colr boxes.
 */
internal object Vui {
    /** Table E-1, aspect_ratio_idc 1..16. */
    private val SAR = arrayOf(
        1 to 1, 12 to 11, 10 to 11, 16 to 11, 40 to 33, 24 to 11, 20 to 11, 32 to 11,
        80 to 33, 18 to 11, 15 to 11, 64 to 33, 160 to 99, 4 to 3, 3 to 2, 2 to 1
    )

    /** Reads from vui_parameters() up to the colour description into [t]. */
    fun read(b: ParsableBitArray, t: Track) {
        if (b.readBit()) {                              // aspect_ratio_info_present_flag
            val idc = b.readBits(8)
            if (idc == 255) {                           // Extended_SAR
                val w = b.readBits(16); val h = b.readBits(16)
                if (w > 0 && h > 0) { t.sarNum = w; t.sarDen = h }
            } else if (idc in 1..SAR.size) {
                t.sarNum = SAR[idc - 1].first; t.sarDen = SAR[idc - 1].second
            }
        }
        if (b.readBit()) b.skipBits(1)                  // overscan_info_present: overscan_appropriate
        if (b.readBit()) {                              // video_signal_type_present_flag
            b.skipBits(3)                               // video_format
            t.fullRange = b.readBit()
            if (b.readBit()) {                          // colour_description_present_flag
                t.colourPrimaries = b.readBits(8)
                t.transferCharacteristics = b.readBits(8)
                t.matrixCoefficients = b.readBits(8)
            }
        }
    }
}
