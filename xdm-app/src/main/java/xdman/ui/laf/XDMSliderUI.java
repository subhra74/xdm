package xdman.ui.laf;

import java.awt.Color;
import java.awt.Graphics;

import javax.swing.JComponent;
import javax.swing.JSlider;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicSliderUI;

import xdman.ui.res.ColorResource;
import static xdman.util.XDMUtils.getScaledInt;
public class XDMSliderUI extends BasicSliderUI {

	public XDMSliderUI(JSlider b) {
		super(b);
	}

	public static ComponentUI createUI(JComponent c) {
		return new XDMSliderUI((JSlider) c);
	}

	@Override
	public void paintTrack(Graphics g) {
		g.setColor(ColorResource.getDarkBgColor());
		g.fillRect(trackRect.x, trackRect.height / 2 - getScaledInt(2), super.trackRect.width, getScaledInt(4));
		g.setColor(ColorResource.getSelectionColor());
		g.fillRect(trackRect.x, trackRect.height / 2 - getScaledInt(2), super.thumbRect.x, getScaledInt(4));
	}

	@Override
	public void paintFocus(Graphics g) {
	}

	@Override
	public void paintThumb(Graphics g) {
		g.setColor(Color.WHITE);
		//g.fillRect(thumbRect.x, thumbRect.y, thumbRect.width, thumbRect.height);
		g.fillRect(thumbRect.x, trackRect.height / 2 - getScaledInt(2), super.thumbRect.width, getScaledInt(4));
	}

}
