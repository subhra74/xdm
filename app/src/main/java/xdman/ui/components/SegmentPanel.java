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

package xdman.ui.components;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;

import javax.swing.JComponent;

import xdman.downloaders.SegmentDetails;
import xdman.downloaders.SegmentInfo;
import xdman.ui.res.ColorResource;
public class SegmentPanel extends JComponent {
	private static final long serialVersionUID = -6537879808121349569L;
	private SegmentDetails segDet;
	private long length;

	public void setValues(SegmentDetails segDet, long length) {
		this.segDet = segDet;
		this.length = length;
		repaint();
	}

	public void paintComponent(Graphics g) {
		if (g == null)
			return;

		Graphics2D g2 = (Graphics2D) g;
		g2.setPaint(Color.GRAY);
		g2.fillRect(0, 0, getWidth(), getHeight());
		if (segDet == null || segDet.getChunkCount() < 1 || length < 0) {
			return;
		}

		g2.setPaint(ColorResource.getSelectionColor());

		float r = (float) getWidth() / length;
		
		// g2.setPaint(low);// g.setColor(Color.BLACK);
		// g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
		ArrayList<SegmentInfo> list = segDet.getChunkUpdates();
		// Logger.log(list.size()+" r: "+r+" width: "+getWidth()+" length: "+length);
		for (int i = 0; i < segDet.getChunkCount(); i++) {
			SegmentInfo info = list.get(i);
			int _start = (int) (info.getStart() * r);
			int _length = (int) (info.getLength() * r);
			int _dwnld = (int) (info.getDownloaded() * r);
			if (_dwnld > _length)
				_dwnld = _length;
			// g2.drawRect(_start, 0, _length, getHeight() - 1);
			g2.fillRect(_start, 0, _dwnld + 1, getHeight());
			
			// g2.setPaint(low);
			// g2.fillRect(_start, getHeight() / 2, _dwnld + 1, getHeight() -
			// 1);
			// g.setColor(Color.RED);
			// g.drawLine(_start, 0, _start, getHeight() - 1);
			// g.setColor(Color.BLACK);
		}
		// g2.setColor(Color.GRAY);
		// g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
	}
}
