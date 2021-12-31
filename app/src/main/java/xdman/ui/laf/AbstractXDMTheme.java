package xdman.ui.laf;

import java.awt.Color;

import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.OceanTheme;

import xdman.ui.res.FontResource;

public class AbstractXDMTheme extends OceanTheme {
	protected FontUIResource fontResource;
	protected ColorUIResource pm1, pm2, pm3, sc1, sc2, sc3;

	public AbstractXDMTheme() {
		fontResource = new FontUIResource(FontResource.getNormalFont());
		pm1 = new ColorUIResource(Color.BLACK);
		pm2 = new ColorUIResource(Color.BLACK);// gray);
		pm3 = new ColorUIResource(Color.BLACK);

		sc1 = new ColorUIResource(Color.BLACK);// BORDER COLOR
		sc2 = new ColorUIResource(Color.BLACK);// BUTTON LOWER
		sc3 = new ColorUIResource(Color.BLACK);
	}

	@Override
	public FontUIResource getControlTextFont() {
		return fontResource;
	}

	@Override
	public FontUIResource getWindowTitleFont() {
		return fontResource;
	}

	@Override
	public FontUIResource getUserTextFont() {
		return fontResource;
	}

	@Override
	public FontUIResource getSystemTextFont() {
		return fontResource;
	}

	@Override
	public FontUIResource getSubTextFont() {
		return fontResource;
	}

	@Override
	public FontUIResource getMenuTextFont() {
		return fontResource;
	}

	@Override
	protected ColorUIResource getPrimary1() {
		return pm1;
	}

	@Override
	protected ColorUIResource getPrimary2() {
		return pm2;
	}

	@Override
	protected ColorUIResource getPrimary3() {
		return pm3;
	}

	@Override
	protected ColorUIResource getSecondary1() {
		return sc1;
	}

	@Override
	protected ColorUIResource getSecondary2() {
		return sc2;
	}

	@Override
	protected ColorUIResource getSecondary3() {
		return sc3;
	}
}
