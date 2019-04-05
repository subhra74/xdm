package xdman.ui.components;

import javax.swing.table.*;

import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.videoparser.YdlResponse.YdlVideo;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

import java.awt.*;
import static xdman.util.XDMUtils.getScaledInt;

public class VideoItemRenderer implements TableCellRenderer {
	private JPanel panel;
	private JPanel component;
	private JLabel lbl;
	private JLabel lblIcon;
	private JComboBox<String> cmb;
	private DefaultComboBoxModel<String> cmbModel;
	private JLabel lblBorder;
	private JCheckBox chk;
	private MediaImageSource imgSource;
	private ImageIcon ico;

	public VideoItemRenderer(MediaImageSource imgSource) {
		component = new JPanel(new BorderLayout(getScaledInt(5), getScaledInt(5)));
		component.setBorder(new EmptyBorder(0, getScaledInt(5), getScaledInt(5), getScaledInt(5)));
		panel = new JPanel(new BorderLayout());
		lblIcon = new JLabel();
		lblIcon.setOpaque(true);
		lblIcon.setPreferredSize(new Dimension(getScaledInt(119), getScaledInt(92)));
		lblIcon.setMinimumSize(new Dimension(getScaledInt(119), getScaledInt(92)));
		lblIcon.setMaximumSize(new Dimension(getScaledInt(119), getScaledInt(92)));
		lblIcon.setHorizontalAlignment(JLabel.CENTER);

		ico = ImageResource.get("videoplay.png");
		lblIcon.setIcon(ico);
		// lblIcon.setBorder(new EmptyBorder(12, 5, 5, 5));
		lblIcon.setVerticalAlignment(JLabel.CENTER);
		// lblIcon.setPreferredSize(new Dimension(53, 53));

		JPanel p1 = new JPanel(new BorderLayout());
		p1.setOpaque(false);
		p1.add(lblIcon);
		chk = new JCheckBox("");
		chk.setOpaque(false);
		chk.setIcon(ImageResource.get("unchecked.png"));
		chk.setSelectedIcon(ImageResource.get("checked.png"));
		p1.add(chk, BorderLayout.WEST);
		p1.setBorder(new EmptyBorder(getScaledInt(12), 0, getScaledInt(5), getScaledInt(5)));
		component.add(p1, BorderLayout.WEST);
		// component.add(lblIcon, BorderLayout.WEST);
		lbl = new JLabel();
		lbl.setVerticalAlignment(JLabel.CENTER);
		lbl.setVerticalTextPosition(JLabel.CENTER);
		lbl.setFont(FontResource.getBigFont());
		panel.add(lbl);
		cmbModel = new DefaultComboBoxModel<>();
		cmb = new JComboBox<>(cmbModel);
		cmb.setPreferredSize(new Dimension(getScaledInt(200), getScaledInt(30)));
		
		cmb.setOpaque(false);
		cmb.setBorder(null);
		panel.add(cmb, BorderLayout.SOUTH);
		panel.setOpaque(false);
		panel.setBorder(new EmptyBorder(0, 0, getScaledInt(5), getScaledInt(5)));
		component.add(panel);
		lblBorder = new JLabel();
		lblBorder.setPreferredSize(new Dimension(getScaledInt(100), 1));
		lblBorder.setMaximumSize(new Dimension(getScaledInt(100), 1));
		lblBorder.setBackground(ColorResource.getDarkerBgColor());
		component.add(lblBorder, BorderLayout.NORTH);
		component.setOpaque(false);
		this.imgSource = imgSource;
		// component.setBackground(ColorResource.getSelectionColor());
	}

	@Override
	public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
			int row, int column) {
		VideoItemWrapper wrapper = (VideoItemWrapper) value;
		YdlVideo obj = wrapper.videoItem;
		lbl.setText(obj.title);
		// stat.setText(obj.mediaFormats.get(obj.index) + "");
		cmbModel.removeAllElements();
		cmbModel.addElement(obj.mediaFormats.get(obj.index) + "");
		if (row == 0) {
			lblBorder.setOpaque(false);
		} else {
			lblBorder.setOpaque(true);
		}
		// component.setBackground(isSelected ? ColorResource.getSelectionColor() :
		// ColorResource.getDarkestBgColor());
		lblIcon.setIcon(ico);
		chk.setSelected(wrapper.checked);
		if (obj.thumbnail != null) {
			if (imgSource != null) {
				ImageIcon icon = imgSource.getImage(obj.thumbnail);
				if (icon != null) {
					lblIcon.setIcon(icon);
				} else {
					System.out.println("null");
				}
			}
		}

		return component;
	}
}