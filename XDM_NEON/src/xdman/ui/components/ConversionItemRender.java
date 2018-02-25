package xdman.ui.components;

import xdman.mediaconversion.ConversionItem;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.StringUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

import java.awt.*;

import static xdman.util.XDMUtils.getScaledInt;
public class ConversionItemRender implements ListCellRenderer<ConversionItem> {
	private JPanel panel;
	private JPanel component;
	private JLabel lbl;
	private JLabel lblIcon;
	private JLabel lblVideoDet;
	private JLabel lblBorder;
	private ImageIcon ico;

	public ConversionItemRender() {
		component = new JPanel(new BorderLayout(getScaledInt(5), getScaledInt(5)));
		component.setBackground(ColorResource.getDarkestBgColor());
		component.setBorder(new EmptyBorder(0, getScaledInt(5), getScaledInt(5), getScaledInt(5)));
		panel = new JPanel(new BorderLayout());
		lblIcon = new JLabel();
		lblIcon.setOpaque(true);
		lblIcon.setPreferredSize(new Dimension(getScaledInt(64), getScaledInt(64)));
		lblIcon.setMinimumSize(new Dimension(getScaledInt(64), getScaledInt(64)));
		lblIcon.setMaximumSize(new Dimension(getScaledInt(64), getScaledInt(64)));
		lblIcon.setHorizontalAlignment(JLabel.CENTER);

		ico = ImageResource.get("video.png");
		lblIcon.setIcon(ico);
		// lblIcon.setBorder(new EmptyBorder(12, 5, 5, 5));
		lblIcon.setVerticalAlignment(JLabel.CENTER);
		// lblIcon.setPreferredSize(new Dimension(53, 53));

		JPanel p1 = new JPanel(new BorderLayout());
		p1.setOpaque(false);
		p1.add(lblIcon);
		// chk = new JCheckBox("");
		// chk.setOpaque(false);
		// chk.setIcon(ImageResource.get("unchecked.png"));
		// chk.setSelectedIcon(ImageResource.get("checked.png"));
		// p1.add(chk, BorderLayout.WEST);
		p1.setBorder(new EmptyBorder(getScaledInt(12), getScaledInt(5), getScaledInt(5), getScaledInt(5)));
		component.add(p1, BorderLayout.WEST);
		// component.add(lblIcon, BorderLayout.WEST);
		lbl = new JLabel();
		lbl.setFont(FontResource.getItemFont());
		lbl.setBorder(new EmptyBorder(0, 0, 0, getScaledInt(5)));
		lbl.setVerticalAlignment(JLabel.CENTER);
		panel.add(lbl);
		lblVideoDet = new JLabel();
		lblVideoDet.setPreferredSize(new Dimension(getScaledInt(200), getScaledInt(30)));
		lblVideoDet.setOpaque(false);
		lblVideoDet.setVerticalAlignment(JLabel.TOP);

		panel.add(lblVideoDet, BorderLayout.SOUTH);
		panel.setOpaque(false);
		panel.setBorder(new EmptyBorder(getScaledInt(5), 0, getScaledInt(7), getScaledInt(5)));

		component.add(panel);
		lblBorder = new JLabel();
		lblBorder.setPreferredSize(new Dimension(getScaledInt(100), 1));
		lblBorder.setMaximumSize(new Dimension(getScaledInt(100), 1));
		lblBorder.setBackground(ColorResource.getDarkestBgColor());
		component.add(lblBorder, BorderLayout.NORTH);
		component.setOpaque(true);
	}

	@Override
	public Component getListCellRendererComponent(JList<? extends ConversionItem> list, ConversionItem value, int index,
			boolean isSelected, boolean cellHasFocus) {
		if (isSelected) {
			component.setBackground(ColorResource.getSelectionColor());
		} else {
			component.setBackground(ColorResource.getDarkestBgColor());
		}
		lbl.setText(value.inputFileName);
		StringBuilder buf = new StringBuilder();
		if (value.info != null) {
			if (value.info.thumbnail != null) {
				lblIcon.setIcon(value.info.thumbnail);
			}
			if (!StringUtils.isNullOrEmptyOrBlank(value.info.duration)) {
				buf.append("[" + value.info.duration + "]");
			}
			if (!StringUtils.isNullOrEmptyOrBlank(value.info.resolution)) {
				buf.append(buf.length() > 0 ? " " : "");
				buf.append(value.info.resolution);
			}
			if (value.conversionState == 1) {
				if (buf.length() > 0) {
					buf.append(" - ");
				}
				buf.append(StringResource.get("LBL_CONV_SUCCESS"));
			} else if (value.conversionState == 2) {
				if (buf.length() > 0) {
					buf.append(" - ");
				}
				buf.append(StringResource.get("LBL_CONV_FAILED"));
			}
			if (buf.length() > 0) {
				lblVideoDet.setText(buf.toString());
			}
		}
		lbl.setText(value.inputFileName);
		if (index == 0) {
			lblBorder.setOpaque(false);
		} else {
			lblBorder.setOpaque(true);
		}
		return component;
	}
}
