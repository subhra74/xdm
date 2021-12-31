package xdman.ui.components;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
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
		// setBorder(new MatteBorder(0, 0, 1, 0, Color.black));
		// setBorder(new EmptyBorder(XDMUtils.getScaledInt(10), 0,
		// XDMUtils.getScaledInt(10), 0));
		var searchBox = Box.createHorizontalBox();

		var newButton = new FontIconButton(iconFont);
		newButton.setFontIcon(RemixIconConstants.ADD_LINE);
		newButton.setText("New");
		newButton.setMaximumSize(newButton.getPreferredSize());
		applyToolbarStyle(newButton);

		var delButton = createToolbarButton(iconFont, RemixIconConstants.DELETE_BIN_LINE);
		var pauseButton = createToolbarButton(iconFont, RemixIconConstants.PAUSE_LINE);
		var resumeButton = createToolbarButton(iconFont, RemixIconConstants.PLAY_LINE);
		var openFileButton = createToolbarButton(iconFont, RemixIconConstants.RECYCLE_LINE);// RemixIconConstants.SHARE_BOX_LINE);
		var openFolderButton = createToolbarButton(iconFont, RemixIconConstants.CHECKBOX_MULTIPLE_BLANK_LINE);// RemixIconConstants.FOLDER_SHARE_LINE);
		var sortButton = createToolbarButton(iconFont, RemixIconConstants.SORT_DESC);
		var menuButton = createToolbarButton(iconFont, RemixIconConstants.MORE_2_FILL);
		var searchButton = createToolbarButton(iconFont.deriveFont(16.0f), RemixIconConstants.SEARCH_LINE);

		var searchTextbox = new JTextField(20);
		// searchTextbox.setBackground(Color.WHITE);
		searchTextbox.setBorder(new EmptyBorder(0, XDMUtils.getScaledInt(10), 0, 0));
		searchTextbox.setText("Search");
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
		add(resumeButton);
		add(pauseButton);
		add(delButton);
		add(openFileButton);
		add(openFolderButton);
		add(sortButton);

		add(Box.createHorizontalGlue());

		add(searchBox);
		add(menuButton);

//		delButton.setVisible(false);
//		openFileButton.setVisible(false);
//		openFolderButton.setVisible(false);
//		resumeButton.setVisible(false);
//		pauseButton.setVisible(false);

		// add(leftBox, BorderLayout.CENTER);
		// add(rightBox, BorderLayout.EAST);
	}

	private JButton createToolbarButton(Font iconFont, String text) {
		var button = new JButton();
		// button.setBackground(Color.WHITE);
		button.setMargin(new Insets(XDMUtils.getScaledInt(5), XDMUtils.getScaledInt(10), XDMUtils.getScaledInt(5),
				XDMUtils.getScaledInt(10)));
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
