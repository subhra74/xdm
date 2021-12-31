package xdman.ui.components;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.io.File;
import java.io.IOException;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

public class FontIconButton extends JButton {
	private JLabel lblIcon, lblText;
	// private JPanel panel;

	public FontIconButton() {
		this(null);
	}

	public FontIconButton(Font iconFont) {
		setLayout(new BorderLayout());
		lblIcon = new JLabel();
		lblText = new JLabel();
//		panel = new JPanel(new BorderLayout(10, 10));
		add(lblIcon, BorderLayout.WEST);
		add(lblText, BorderLayout.CENTER);
		// panel.setOpaque(false);

		if (iconFont == null) {
			try {
				iconFont = Font
						.createFont(Font.TRUETYPE_FONT, new File(System.getProperty("user.home"), "remixicon.ttf"))
						.deriveFont(16.0f);
			} catch (FontFormatException | IOException e) {
				e.printStackTrace();
			}
		}
		lblIcon.setFont(iconFont);
		// add(panel);
//		lblIcon.setForeground(Color.WHITE);
//		lblText.setForeground(Color.WHITE);
	}

	@Override
	public void setForeground(Color fg) {
		if (lblIcon != null) {
			lblIcon.setForeground(fg);
		}
		if (lblText != null) {
			lblText.setForeground(fg);
		}
	}

	public void setFontIcon(String fontIcon) {
		lblIcon.setText(fontIcon);
	}

	@Override
	public void setText(String text) {
		lblText.setText(text);
	}
}
