package xdman.ui.components;

import java.awt.Color;

import javax.swing.Icon;
import javax.swing.JButton;

import xdman.ui.res.ColorResource;

public class CustomButton extends JButton {
	private static final long serialVersionUID = 6378409011977437191L;
	private Color rolloverBackground, rolloverForeground;
	private Color pressedBackground, pressedForeground;

	public CustomButton() {
		init();
	}

	public CustomButton(Icon icon) {
		super(icon);
		init();
	}

	public CustomButton(String text) {
		super(text);
		init();
	}

	private void init() {
		rolloverBackground = ColorResource.getSelectionColor();
		rolloverForeground = Color.WHITE;
		pressedBackground = ColorResource.getDarkBgColor();
		pressedForeground = Color.WHITE;
	}

	@Override
	public Color getForeground() {
		if (model.isRollover()) {
			return rolloverForeground;
		} else if (model.isPressed()) {
			return pressedForeground;
		} else {
			return super.getForeground();
		}
	}

	@Override
	public Color getBackground() {
		if (model.isPressed()) {
			return pressedBackground;
		} else if (model.isRollover()) {
			return rolloverBackground;
		} else {
			return super.getBackground();
		}
	}

	public static final long getSerialversionuid() {
		return serialVersionUID;
	}

	public final Color getRolloverBackground() {
		return rolloverBackground;
	}

	public final void setRolloverBackground(Color rolloverBackground) {
		this.rolloverBackground = rolloverBackground;
	}

	public final Color getRolloverForeground() {
		return rolloverForeground;
	}

	public final void setRolloverForeground(Color rolloverForeground) {
		this.rolloverForeground = rolloverForeground;
	}

	public final Color getPressedBackground() {
		return pressedBackground;
	}

	public final void setPressedBackground(Color pressedBackground) {
		this.pressedBackground = pressedBackground;
	}

	public final Color getPressedForeground() {
		return pressedForeground;
	}

	public final void setPressedForeground(Color pressedForeground) {
		this.pressedForeground = pressedForeground;
	}
}
