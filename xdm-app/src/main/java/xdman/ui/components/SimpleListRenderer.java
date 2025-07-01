package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.border.EmptyBorder;

import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;

public class SimpleListRenderer extends JLabel implements ListCellRenderer<Object> {

	/**
	 * 
	 */
	private static final long serialVersionUID = 2719764994839662332L;

	public SimpleListRenderer() {
		setForeground(Color.WHITE);
		setFont(FontResource.getNormalFont());
		setOpaque(true);
		setPreferredSize(new Dimension(getScaledInt(100), getScaledInt(30)));
		setBorder(new EmptyBorder(getScaledInt(0), getScaledInt(5), 0, 0));
	}

	@Override
	public Component getListCellRendererComponent(JList<? extends Object> list, Object value, int index,
			boolean isSelected, boolean cellHasFocus) {
		if (isSelected) {
			setBackground(ColorResource.getSelectionColor());
		} else {
			setBackground(ColorResource.getDarkerBgColor());
		}
		setText(value == null ? "" : value.toString());
		return this;
	}

}
