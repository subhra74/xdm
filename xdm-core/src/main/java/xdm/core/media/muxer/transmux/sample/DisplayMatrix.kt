// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.media.muxer.transmux.sample

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The video display matrix (rotation / flip) in the MP4 `tkhd` layout: nine 32-bit values
 * `a b u / c d v / x y w`, with a b c d x y in 16.16 and u v w in 2.30 fixed point. Matroska carries
 * the same information as `Projection` ProjectionPoseYaw (180 = mirrored) and ProjectionPoseRoll
 * (degrees); the two conversions below are inverses of each other and give the values ffmpeg writes.
 */
object DisplayMatrix {
    private const val ONE = 0x00010000

    val UNITY: IntArray = intArrayOf(ONE, 0, 0, 0, ONE, 0, 0, 0, 0x40000000)

    fun isUnity(m: IntArray): Boolean = m.contentEquals(UNITY)

    /**
     * `[yaw, roll]` in degrees for [m], or null when it is the identity or isn't a rotation, possibly
     * after a horizontal flip (skew, translation-only or projective matrices are not carried).
     */
    fun toPose(m: IntArray): DoubleArray? {
        if (m.size != 9 || isUnity(m)) return null
        if (m[2] != 0 || m[5] != 0) return null            // projective
        if (m[0] == 0 && m[1] == 0) return null            // singular
        val yaw = when {
            m[3] == m[1] && m[0].toLong() == -m[4].toLong() -> 180.0   // mirrored, then rotated
            m[0] == m[4] && m[1].toLong() == -m[3].toLong() -> 0.0
            else -> return null
        }
        val roll = Math.toDegrees(atan2(m[3].toDouble(), m[4].toDouble()))
        return if (yaw == 0.0 && roll == 0.0) null else doubleArrayOf(yaw, roll)
    }

    /** The matrix for a Matroska pose, or null for the identity or a pose that isn't a plain rotation/flip. */
    fun fromPose(yaw: Double, pitch: Double, roll: Double): IntArray? {
        if (pitch != 0.0) return null
        val mirrored = when (yaw) { 0.0 -> false; 180.0, -180.0 -> true; else -> return null }
        val r = Math.toRadians(roll)
        val c = (cos(r) * ONE).roundToInt()
        val s = (sin(r) * ONE).roundToInt()
        val m = if (mirrored) intArrayOf(-c, s, 0, s, c, 0, 0, 0, 0x40000000)
        else intArrayOf(c, -s, 0, s, c, 0, 0, 0, 0x40000000)
        return if (isUnity(m)) null else m
    }
}
