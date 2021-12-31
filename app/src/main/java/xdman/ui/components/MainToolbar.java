package xdman.ui.components;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;

import xdman.util.XDMUtils;

public class MainToolbar extends JPanel {
	public MainToolbar(Font iconFont) {
		super(null);
		setLayout(new BoxLayout(this, BoxLayout.LINE_AXIS));
		//setBorder(new MatteBorder(0, 0, 1, 0, Color.black));
		// setBorder(new EmptyBorder(XDMUtils.getScaledInt(10), 0,
		// XDMUtils.getScaledInt(10), 0));
		var searchBox = Box.createHorizontalBox();

		var newButton = new FontIconButton(iconFont);
		newButton.setFontIcon("\uea13");
		newButton.setText("New");
		newButton.setMaximumSize(newButton.getPreferredSize());
		applyToolbarStyle(newButton);

		var delButton = createToolbarButton(iconFont, "\uec2a");
		var pauseButton = createToolbarButton(iconFont, "\uec2a");
		var resumeButton = createToolbarButton(iconFont, "\uec2a");
		var openFileButton = createToolbarButton(iconFont, "\uec2a");
		var openFolderButton = createToolbarButton(iconFont, "\uec2a");
		var menuButton = createToolbarButton(iconFont, "\uec2a");
		var searchButton = createToolbarButton(iconFont.deriveFont(12.0f), "\uec2a");

		var searchTextbox = new JTextField(20);
		// searchTextbox.setBackground(Color.WHITE);
		searchTextbox.setBorder(new EmptyBorder(0, 0, 0, 0));
		searchButton.setBackground(searchTextbox.getBackground());
//		searchTextbox.setPreferredSize(new Dimension(150, 30));
//		searchTextbox.setMaximumSize(new Dimension(150, 30));
//		searchTextbox.setMinimumSize(new Dimension(150, 30));

		searchBox.add(searchTextbox);
		searchBox.setOpaque(true);
		searchBox.setBackground(searchTextbox.getBackground());
		searchBox.add(searchButton);
		searchBox.setBorder(new MatteBorder(XDMUtils.getScaledInt(10), XDMUtils.getScaledInt(3),
				XDMUtils.getScaledInt(10), XDMUtils.getScaledInt(3), this.getBackground()));
		var w = 150;
		searchBox.setMaximumSize(new Dimension(w, XDMUtils.getScaledInt(50)));
		searchBox.setPreferredSize(new Dimension(w, XDMUtils.getScaledInt(50)));
		searchBox.setMinimumSize(new Dimension(10, XDMUtils.getScaledInt(50)));

		add(newButton);
		add(delButton);
		add(pauseButton);
		add(resumeButton);
		add(openFileButton);
		add(openFolderButton);
		add(Box.createHorizontalGlue());

		add(searchBox);
		add(Box.createHorizontalStrut(3));
		add(menuButton);

		// add(leftBox, BorderLayout.CENTER);
		// add(rightBox, BorderLayout.EAST);
	}

	private JButton createToolbarButton(Font iconFont, String text) {
		var button = new JButton();
		// button.setBackground(Color.WHITE);
		button.setMargin(new Insets(5, 5, 5, 5));
//		button.setForeground(Color.WHITE);
		applyToolbarStyle(button);
		button.setFont(iconFont);
		button.setText(text);
		return button;
	}

	private void applyToolbarStyle(JButton button) {
		button.setContentAreaFilled(false);
		button.setBorderPainted(false);
		button.setFocusPainted(false);
	}
}
