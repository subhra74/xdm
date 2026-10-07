/*
 * Copyright (c)  Subhra Das Gupta
 *
 * This file is part of Xtream Download Manager.
 *
 * Xtream Download Manager is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Xtream Download Manager is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with Xtream Download Manager; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
package xdm.app.ui.components

import xdm.app.utils.px
import xdm.core.downloaders.web.SegmentProgress
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.UIManager

class SegmentPanel : JComponent() {
    private var segDet: Collection<SegmentProgress> = listOf()
    private var totalSize: Long = 0

    fun setValues(segDet: Collection<SegmentProgress>) {
        this.segDet = segDet
        this.totalSize = segDet.sumOf { it.length }
        repaint()
    }

    public override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.paint = UIManager.getColor("ProgressBar.background")
        g2.fillRect(0, 0, getWidth(), getHeight())
        val originalClip = g2.clip
        val clipShape = RoundRectangle2D.Double(0.0, 0.0, getWidth().toDouble(), getHeight().toDouble(), 5f.px.toDouble(), 5f.px.toDouble())
        g2.clip = clipShape
        try {
//            g2.paint = Color.GRAY
            g2.fillRect(0, 0, getWidth(), getHeight())
            if (segDet.isEmpty() || totalSize <= 0) {
                return
            }

            g2.paint = UIManager.getColor("ProgressBar.foreground")

            // Pixel edges are rounded from absolute offsets so adjacent filled segments meet without
            // gaps; an untouched segment draws nothing (a streaming download has hundreds of them,
            // and a 1px minimum each would paint the bar full before anything is downloaded).
            val r = getWidth().toDouble() / totalSize
            for (info in segDet) {
                val downloaded = info.downloaded.coerceAtMost(info.length)
                if (downloaded <= 0) continue
                val x0 = (info.start * r).toInt()
                val x1 = Math.ceil((info.start + downloaded) * r).toInt()
                g2.fillRect(x0, 0, maxOf(1, x1 - x0), getHeight())
            }
        } finally {
            g2.clip = originalClip
        }
    }
}
