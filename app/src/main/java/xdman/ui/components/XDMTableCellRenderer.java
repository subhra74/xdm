package xdman.ui.components;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

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
	private Font iconFont;
	private Map<String, Icon> iconMap = new HashMap<String, Icon>();

	public XDMTableCellRenderer() {
		titleLbl = new JLabel("This is sample title text");
		// titleLbl.setForeground(Color.BLACK);
		iconLbl = new JLabel();
		// iconLbl.setForeground(Color.BLACK);
		statLbl = new JLabel("This is sample status text");
		// statLbl.setForeground(Color.BLACK);
		dateLbl = new JLabel("Yesterday");
		// dateLbl.setForeground(Color.BLACK);
		lineLbl = new JLabel();

		try {
			iconFont = Font.createFont(Font.TRUETYPE_FONT, new File(System.getProperty("user.home"), "remixicon.ttf"))
					.deriveFont(24.0f);
			iconLbl.setFont(iconFont);
			iconLbl.setText(RemixIconConstants.FILE_FILL);
		} catch (FontFormatException | IOException e) {
			e.printStackTrace();
		}
		iconLbl.setOpaque(false);
		iconLbl.setBorder(new EmptyBorder(XDMUtils.getScaledInt(5),XDMUtils.getScaledInt(10), XDMUtils.getScaledInt(5), XDMUtils.getScaledInt(5)));
		// iconLbl.setPreferredSize(new Dimension(XDMUtils.getScaledInt(56),
		// XDMUtils.getScaledInt(56)));

		iconMap.put("document.png", ImageResource.getIcon("document.png", 48, 48));
		iconMap.put("compressed.png", ImageResource.getIcon("compressed.png", 48, 48));
		iconMap.put("program.png", ImageResource.getIcon("program.png", 48, 48));
		iconMap.put("music.png", ImageResource.getIcon("music.png", 48, 48));
		iconMap.put("video.png", ImageResource.getIcon("video.png", 48, 48));
		iconMap.put("other.png", ImageResource.getIcon("other.png", 48, 48));

		// iconLbl.setIcon(iconMap.get("document.png"));
		// iconLbl.setBorder(new EmptyBorder(5,5,5,5));

		// titleLbl.setBackground(Color.WHITE);
		titleLbl.setFont(FontResource.getNormalFont());
		titleLbl.setOpaque(false);
		// title.setPreferredSize(new Dimension(64, 64));

		// statLbl.setBackground(Color.WHITE);
		statLbl.setFont(FontResource.getSmallFont());
		statLbl.setOpaque(false);
		// status.setPreferredSize(new Dimension(64, 64));

		// dateLbl.setBackground(Color.WHITE);
		dateLbl.setOpaque(false);
		dateLbl.setFont(FontResource.getSmallFont());
		// date.setPreferredSize(new Dimension(64, 64));

		lineLbl = new JLabel();
		// lineLbl.setBackground(ColorResource.getWhite());
		lineLbl.setOpaque(true);
		lineLbl.setMinimumSize(new Dimension(10, 1));
		lineLbl.setMaximumSize(new Dimension(lineLbl.getMaximumSize().width, 1));
		lineLbl.setPreferredSize(new Dimension(lineLbl.getPreferredSize().width, 1));

		pcell = new JPanel(new BorderLayout(5, 5));
		// pcell.setBackground(Color.WHITE);

		pcell.add(iconLbl, BorderLayout.WEST);

		Box box = Box.createHorizontalBox();
		box.add(statLbl);
		box.add(Box.createHorizontalGlue());
		box.add(dateLbl);
		box.setBorder(new EmptyBorder(0, 0, XDMUtils.getScaledInt(3), XDMUtils.getScaledInt(5)));

		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.add(titleLbl);
		p.add(box, BorderLayout.SOUTH);
		p.setBorder(new EmptyBorder(XDMUtils.getScaledInt(5), 0, XDMUtils.getScaledInt(0), XDMUtils.getScaledInt(10)));

		pcell.add(p);
		pcell.add(lineLbl, BorderLayout.SOUTH);
		pcell.setBorder(new EmptyBorder(XDMUtils.getScaledInt(5), XDMUtils.getScaledInt(0), XDMUtils.getScaledInt(0),
				XDMUtils.getScaledInt(0)));
	}

	public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
			int row, int column) {
		DownloadEntry ent = (DownloadEntry) value;
		titleLbl.setText(ent.getFile());
		dateLbl.setText(ent.getDateStr());
		statLbl.setText(FormatUtilities.getFormattedStatus(ent));
		if (isSelected) {
			pcell.setBackground(table.getSelectionBackground());
			lineLbl.setOpaque(false);
			titleLbl.setForeground(table.getSelectionForeground());
			dateLbl.setForeground(table.getSelectionForeground());
			statLbl.setForeground(table.getSelectionForeground());
			iconLbl.setForeground(table.getSelectionForeground());
		} else {
			pcell.setBackground(table.getBackground());
			lineLbl.setOpaque(true);
			titleLbl.setForeground(table.getForeground());
			dateLbl.setForeground(table.getForeground());
			statLbl.setForeground(table.getForeground());
			iconLbl.setForeground(table.getForeground());
		}
//		switch (ent.getCategory()) {
//		case XDMConstants.DOCUMENTS:
//			iconLbl.setIcon(iconMap.get("document.png"));
//			break;
//		case XDMConstants.COMPRESSED:
//			iconLbl.setIcon(iconMap.get("compressed.png"));
//			break;
//		case XDMConstants.PROGRAMS:
//			iconLbl.setIcon(iconMap.get("program.png"));
//			break;
//		case XDMConstants.MUSIC:
//			iconLbl.setIcon(iconMap.get("music.png"));
//			break;
//		case XDMConstants.VIDEO:
//			iconLbl.setIcon(iconMap.get("video.png"));
//			break;
//		default:
//			iconLbl.setIcon(iconMap.get("other.png"));
//			break;
//		}
		return pcell;
	}

	public JComponent getComponent() {
		return pcell;
	}
}
