package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.border.EmptyBorder;

import xdman.DownloadQueue;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
public class QueueListRenderer extends JLabel implements
		ListCellRenderer<DownloadQueue> {

	/**
	 * 
	 */
	private static final long serialVersionUID = 7780698184295073136L;

	public QueueListRenderer() {
		setForeground(Color.WHITE);
		setFont(FontResource.getNormalFont());
		setOpaque(true);
		setPreferredSize(new Dimension(getScaledInt(100), getScaledInt(30)));
		setBorder(new EmptyBorder(0, getScaledInt(5), 0, 0));
	}

	@Override
	public Component getListCellRendererComponent(
			JList<? extends DownloadQueue> list, DownloadQueue value,
			int index, boolean isSelected, boolean cellHasFocus) {
		if (isSelected) {
			setBackground(ColorResource.getSelectionColor());
		} else {
			setBackground(ColorResource.getDarkerBgColor());
		}
		setText(value.getName());
		return this;
	}

}
