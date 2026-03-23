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
        val clipShape = RoundRectangle2D.Double(0.0, 0.0, getWidth().toDouble(), getHeight().toDouble(), 5.0, 5.0)
        g2.clip = clipShape
        try {
//            g2.paint = Color.GRAY
            g2.fillRect(0, 0, getWidth(), getHeight())
            if (segDet.isEmpty() || totalSize < 0) {
                return
            }

            g2.paint = UIManager.getColor("ProgressBar.foreground")

            val r = getWidth().toFloat() / totalSize
            for (info in segDet) {
                val start = (info.start * r).toInt()
                val length = (info.length * r).toInt()
                var downloaded = (info.downloaded * r).toInt()
                if (downloaded > length) downloaded = length
                g2.fillRect(start, 0, downloaded + 1, getHeight())
            }
        } finally {
            g2.clip = originalClip
        }
    }
}
