package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.BorderLayout;
import java.awt.Component;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.border.EmptyBorder;

import xdman.mediaconversion.Format;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.util.StringUtils;
public class MediaFormatRender implements ListCellRenderer<Format> {
	//private JPanel panel;
	private JPanel component;
	private JLabel lbl;
	private JLabel lblVideoDet;
	//private JLabel lblBorder;

	public MediaFormatRender() {
		component = new JPanel(new BorderLayout());
		component.setBackground(ColorResource.getDarkerBgColor());
		component.setBorder(new EmptyBorder(getScaledInt(10), getScaledInt(10), getScaledInt(10), getScaledInt(10)));
		//panel = new JPanel(new BorderLayout());

		lbl = new JLabel();
		lbl.setFont(FontResource.getBigFont());
		component.add(lbl);

		lblVideoDet = new JLabel();
		lblVideoDet.setOpaque(false);

		component.add(lblVideoDet, BorderLayout.SOUTH);
	}

	@Override
	public Component getListCellRendererComponent(JList<? extends Format> list, Format value, int index,
			boolean isSelected, boolean cellHasFocus) {
		if (isSelected) {
			component.setBackground(ColorResource.getSelectionColor());
		} else {
			component.setBackground(ColorResource.getDarkerBgColor());
		}
		lbl.setText(value.getDesc().trim());
		StringBuilder buf = new StringBuilder();
		String vcodec = value.getDefautValue(value.getVideoCodecs(), value.getDefautVideoCodec());
		String acodec = value.getDefautValue(value.getAudioCodecs(), value.getDefautAudioCodec());
		String resolution = value.getDefautValue(value.getResolutions(), value.getDefaultResolution());

		if (!StringUtils.isNullOrEmptyOrBlank(resolution)) {
			buf.append(resolution);
		}
		if (!StringUtils.isNullOrEmptyOrBlank(vcodec)) {
			buf.append(buf.length() > 0 ? " / " : "");
			buf.append(vcodec);
		}
		if (!StringUtils.isNullOrEmptyOrBlank(acodec)) {
			buf.append(buf.length() > 0 ? " - " : "");
			buf.append(acodec);
		}
		if (buf.length() > 0) {
			lblVideoDet.setText(buf.toString());
		}
		return component;
	}
}
