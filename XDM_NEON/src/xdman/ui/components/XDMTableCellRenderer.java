package xdman.ui.components;

import java.awt.*;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;

import xdman.ui.res.*;
import xdman.util.FormatUtilities;
import xdman.util.XDMUtils;
import xdman.*;

public class XDMTableCellRenderer implements TableCellRenderer {

	JLabel iconLbl, titleLbl, statLbl, dateLbl, lineLbl;
	JPanel pcell;

	public XDMTableCellRenderer() {
		titleLbl = new JLabel("This is sample title text");
		titleLbl.setForeground(Color.BLACK);
		iconLbl = new JLabel();
		iconLbl.setForeground(Color.BLACK);
		statLbl = new JLabel("This is sample status text");
		statLbl.setForeground(Color.BLACK);
		dateLbl = new JLabel("Yesterday");
		dateLbl.setForeground(Color.BLACK);
		lineLbl = new JLabel();

		iconLbl.setOpaque(false);
		iconLbl.setPreferredSize(new Dimension(XDMUtils.getScaledInt(56), XDMUtils.getScaledInt(56)));
		iconLbl.setIcon(ImageResource.get("document.png"));
		// iconLbl.setBorder(new EmptyBorder(5,5,5,5));

		titleLbl.setBackground(Color.WHITE);
		titleLbl.setFont(FontResource.getItemFont());
		titleLbl.setOpaque(false);
		// title.setPreferredSize(new Dimension(64, 64));

		statLbl.setBackground(Color.WHITE);
		statLbl.setFont(FontResource.getNormalFont());
		statLbl.setOpaque(false);
		// status.setPreferredSize(new Dimension(64, 64));

		dateLbl.setBackground(Color.WHITE);
		dateLbl.setOpaque(false);
		dateLbl.setFont(FontResource.getNormalFont());
		// date.setPreferredSize(new Dimension(64, 64));

		lineLbl = new JLabel();
		lineLbl.setBackground(ColorResource.getWhite());
		lineLbl.setOpaque(true);
		lineLbl.setMinimumSize(new Dimension(10, 1));
		lineLbl.setMaximumSize(new Dimension(lineLbl.getMaximumSize().width, 1));
		lineLbl.setPreferredSize(new Dimension(lineLbl.getPreferredSize().width, 1));

		pcell = new JPanel(new BorderLayout());
		pcell.setBackground(Color.WHITE);

		pcell.add(iconLbl, BorderLayout.WEST);

		Box box = Box.createHorizontalBox();
		box.add(statLbl);
		box.add(Box.createHorizontalGlue());
		box.add(dateLbl);
		box.setBorder(new EmptyBorder(0, 0, XDMUtils.getScaledInt(10), 0));

		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.add(titleLbl);
		p.add(box, BorderLayout.SOUTH);
		p.setBorder(new EmptyBorder(XDMUtils.getScaledInt(5), 0, XDMUtils.getScaledInt(5), XDMUtils.getScaledInt(5)));

		pcell.add(p);
		pcell.add(lineLbl, BorderLayout.SOUTH);
		pcell.setBorder(new EmptyBorder(0, XDMUtils.getScaledInt(15), 0, XDMUtils.getScaledInt(15)));
	}

	public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
			int row, int column) {
		DownloadEntry ent = (DownloadEntry) value;
		titleLbl.setText(ent.getFile());
		dateLbl.setText(ent.getDateStr());
		statLbl.setText(FormatUtilities.getFormattedStatus(ent));
		if (isSelected) {
			pcell.setBackground(ColorResource.getSelectionColor());
			lineLbl.setOpaque(false);
			titleLbl.setForeground(Color.WHITE);
			dateLbl.setForeground(Color.WHITE);
			statLbl.setForeground(Color.WHITE);
		} else {
			pcell.setBackground(Color.WHITE);
			lineLbl.setOpaque(true);
			titleLbl.setForeground(Color.BLACK);
			dateLbl.setForeground(Color.BLACK);
			statLbl.setForeground(Color.BLACK);
		}
		switch (ent.getCategory()) {
		case XDMConstants.DOCUMENTS:
			iconLbl.setIcon(ImageResource.get("document.png"));
			break;
		case XDMConstants.COMPRESSED:
			iconLbl.setIcon(ImageResource.get("compressed.png"));
			break;
		case XDMConstants.PROGRAMS:
			iconLbl.setIcon(ImageResource.get("program.png"));
			break;
		case XDMConstants.MUSIC:
			iconLbl.setIcon(ImageResource.get("music.png"));
			break;
		case XDMConstants.VIDEO:
			iconLbl.setIcon(ImageResource.get("video.png"));
			break;
		default:
			iconLbl.setIcon(ImageResource.get("other.png"));
			break;
		}
		return pcell;
	}
}
