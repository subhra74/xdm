package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.Color;
import java.awt.GraphicsDevice.WindowTranslucency;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import xdman.Config;
import xdman.mediaconversion.Format;
import xdman.mediaconversion.FormatGroup;
import xdman.mediaconversion.FormatLoader;
import xdman.mediaconversion.MediaFormat;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.Logger;
import xdman.util.StringUtils;

public class MediaFormatWnd extends JDialog implements ActionListener {
	/**
	 * 
	 */
	private static final long serialVersionUID = -8292378738777760999L;

	public MediaFormatWnd() {
		initUI();
	}

	boolean selected;

	MediaFormat fmt;
	String text;
	JList<Format> listFormat;
	JComboBox<String> cmbSize, cmbAc, cmbAbr, cmbAsr, cmbVBR, cmbFrameRate, cmbResolution, cmbAudioCodec, cmbVideoCodec;

	private void initUI() {
		setUndecorated(true);

		try {
			if (GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
					.isWindowTranslucencySupported(WindowTranslucency.TRANSLUCENT)) {
				if (!Config.getInstance().isNoTransparency()) {
					setOpacity(0.85f);
				}
			}
		} catch (Exception e) {
			Logger.log(e);
		}
		setModal(true);
		setTitle(StringResource.get("LBL_CONVERT_TO"));
		setIconImage(ImageResource.get("icon.png").getImage());
		setSize(getScaledInt(600), getScaledInt(450));
		setLocationRelativeTo(null);
		getContentPane().setLayout(null);
		getContentPane().setBackground(ColorResource.getDarkestBgColor());

		JPanel titlePanel = new TitlePanel(null, this);
		titlePanel.setOpaque(false);
		titlePanel.setBounds(0, 0, getWidth(), getScaledInt(50));

		JButton closeBtn = new CustomButton();
		closeBtn.setBounds(getWidth() - getScaledInt(35), getScaledInt(5), getScaledInt(30), getScaledInt(30));
		closeBtn.setBackground(ColorResource.getDarkestBgColor());
		closeBtn.setBorderPainted(false);
		closeBtn.setFocusPainted(false);
		closeBtn.setName("CLOSE");

		closeBtn.setIcon(ImageResource.get("title_close.png"));
		closeBtn.addActionListener(this);
		titlePanel.add(closeBtn);

		JLabel titleLbl = new JLabel(StringResource.get("LBL_CONVERT_TO"));
		titleLbl.setFont(FontResource.getBiggerFont());
		titleLbl.setForeground(ColorResource.getSelectionColor());
		titleLbl.setBounds(getScaledInt(25), getScaledInt(15), getScaledInt(200), getScaledInt(30));
		titlePanel.add(titleLbl);

		JLabel lineLbl = new JLabel();
		lineLbl.setBackground(ColorResource.getSelectionColor());
		lineLbl.setBounds(getScaledInt(0), getScaledInt(55), getWidth(), 1);
		lineLbl.setOpaque(true);
		add(lineLbl);

		add(titlePanel);

		List<FormatGroup> list = FormatLoader.load();
		FormatGroup[] fgArr = new FormatGroup[list.size()];
		fgArr = list.toArray(fgArr);

		int y = getScaledInt(56);
		y += getScaledInt(10);

		DefaultComboBoxModel<FormatGroup> model = new DefaultComboBoxModel<>();
		for (FormatGroup fg : list) {
			model.addElement(fg);
		}
		JComboBox<FormatGroup> cmbFormatGroup = new JComboBox<>(model);
		cmbFormatGroup.setRenderer(new SimpleListRenderer());
		cmbFormatGroup.setBounds(getScaledInt(15), y, getWidth() - getScaledInt(30), getScaledInt(30));
		add(cmbFormatGroup);

		y += getScaledInt(40);

		DefaultListModel<Format> listModel = new DefaultListModel<>();

		listFormat = new JList<>(listModel);
		listFormat.setCellRenderer(new MediaFormatRender());
		listFormat.setFont(FontResource.getItemFont());
		listFormat.setBackground(ColorResource.getDarkerBgColor());
		// listFormat.setFixedCellHeight(56);
		listFormat.setBorder(null);

		JScrollPane jsp = new JScrollPane(listFormat);
		jsp.setBounds(getScaledInt(15), y, getWidth() - getScaledInt(30), getScaledInt(150));
		jsp.setBorder(null);
		jsp.setBackground(ColorResource.getDarkerBgColor());
		add(jsp);

		y += getScaledInt(160);

		DarkScrollBar scrollBar = new DarkScrollBar(JScrollBar.VERTICAL);
		jsp.setVerticalScrollBar(scrollBar);
		jsp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		jsp.getVerticalScrollBar().setUnitIncrement(getScaledInt(10));
		jsp.getVerticalScrollBar().setBlockIncrement(getScaledInt(25));

		cmbFormatGroup.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				int index = cmbFormatGroup.getSelectedIndex();
				if (index < 0)
					return;
				FormatGroup fg = list.get(index);
				List<Format> formats = fg.getFormats();
				System.out.println(formats.size());
				listModel.removeAllElements();
				for (Format format : formats) {
					listModel.addElement(format);
				}

				if (listModel.getSize() > 0) {
					listFormat.setSelectedIndex(0);
				}
			}
		});

		y += getScaledInt(10);

		int x = getScaledInt(15);
		JLabel lblVideoCodec = new JLabel("Video codec");
		lblVideoCodec.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		add(lblVideoCodec);
		x += getScaledInt(115);

		JLabel lblAudioCodec = new JLabel("Audio codec");
		lblAudioCodec.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		add(lblAudioCodec);
		x += getScaledInt(115);

		JLabel lblResolution = new JLabel("Resolution");
		lblResolution.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		add(lblResolution);
		x += getScaledInt(115);

		JLabel lblFramerate = new JLabel("Frame rate");
		lblFramerate.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		add(lblFramerate);
		x += getScaledInt(115);

		JLabel lblVideoBitrate = new JLabel("VBR");
		lblVideoBitrate.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		add(lblVideoBitrate);
		x += getScaledInt(110);

		y += getScaledInt(25);
		x = getScaledInt(15);

		DefaultComboBoxModel<String> modelVideoCodec = new DefaultComboBoxModel<>();
		cmbVideoCodec = new JComboBox<>(modelVideoCodec);
		cmbVideoCodec.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		add(cmbVideoCodec);
		x += getScaledInt(115);

		DefaultComboBoxModel<String> modelAudioCodec = new DefaultComboBoxModel<>();
		cmbAudioCodec = new JComboBox<>(modelAudioCodec);
		cmbAudioCodec.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		add(cmbAudioCodec);
		x += getScaledInt(115);

		DefaultComboBoxModel<String> modelResolution = new DefaultComboBoxModel<>();
		cmbResolution = new JComboBox<>(modelResolution);
		cmbResolution.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		add(cmbResolution);
		x += getScaledInt(115);

		DefaultComboBoxModel<String> modelFrameRate = new DefaultComboBoxModel<>();
		cmbFrameRate = new JComboBox<>(modelFrameRate);
		cmbFrameRate.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		add(cmbFrameRate);
		x += getScaledInt(115);

		DefaultComboBoxModel<String> modelVBR = new DefaultComboBoxModel<>();
		cmbVBR = new JComboBox<>(modelVBR);
		cmbVBR.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		add(cmbVBR);
		x += getScaledInt(115);

		y += getScaledInt(30);
		x = getScaledInt(15);

		JLabel lblAsr = new JLabel("Sample rate");
		lblAsr.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		add(lblAsr);
		x += getScaledInt(115);

		JLabel lblAbr = new JLabel("ABR");
		lblAbr.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		add(lblAbr);
		x += getScaledInt(115);

		JLabel lblAC = new JLabel("Channel");
		lblAC.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		add(lblAC);
		x += getScaledInt(115);

		JLabel lblSize = new JLabel("Aspect ratio");
		lblSize.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		add(lblSize);
		x += getScaledInt(115);

		y += getScaledInt(25);
		x = getScaledInt(15);

		DefaultComboBoxModel<String> modelAsr = new DefaultComboBoxModel<>();
		cmbAsr = new JComboBox<>(modelAsr);
		cmbAsr.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		add(cmbAsr);
		x += getScaledInt(115);

		DefaultComboBoxModel<String> modelAbr = new DefaultComboBoxModel<>();
		cmbAbr = new JComboBox<>(modelAbr);
		cmbAbr.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		add(cmbAbr);
		x += getScaledInt(115);

		DefaultComboBoxModel<String> modelAc = new DefaultComboBoxModel<>();
		cmbAc = new JComboBox<>(modelAc);
		cmbAc.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		add(cmbAc);
		x += getScaledInt(115);

		DefaultComboBoxModel<String> modelSize = new DefaultComboBoxModel<>();
		cmbSize = new JComboBox<>(modelSize);
		cmbSize.setBounds(x, y, getScaledInt(100), getScaledInt(25));
		cmbSize.setOpaque(false);
		add(cmbSize);
		x += getScaledInt(115);

		y += getScaledInt(50);

		JButton btn = createButton2(StringResource.get("ND_CANCEL"));
		btn.setName("CLOSE");
		btn.addActionListener(this);
		btn.setBounds(getBounds().width - getScaledInt(100) - getScaledInt(20), getHeight() - getScaledInt(45), getScaledInt(100), getScaledInt(30));
		add(btn);

		JButton btn2 = createButton2(StringResource.get("MSG_OK"));
		btn2.setBounds(getBounds().width - getScaledInt(100) - getScaledInt(20) - getScaledInt(100) - getScaledInt(10), getHeight() - getScaledInt(45), getScaledInt(100), getScaledInt(30));
		btn2.setName("BTN_OK");
		btn2.addActionListener(this);
		add(btn2);

		listFormat.addListSelectionListener(new ListSelectionListener() {

			@Override
			public void valueChanged(ListSelectionEvent e) {

				int index = listFormat.getSelectedIndex();
				System.out.println("List selected " + index);
				if (index < 0)
					return;
				Format fmt = listModel.get(index);
				addToModel(modelVideoCodec, fmt.getVideoCodecs(),
						fmt.getDefautValue(fmt.getVideoCodecs(), fmt.getDefautVideoCodec()), cmbVideoCodec);
				addToModel(modelAudioCodec, fmt.getAudioCodecs(),
						fmt.getDefautValue(fmt.getAudioCodecs(), fmt.getDefautAudioCodec()), cmbAudioCodec);
				addToModel(modelResolution, fmt.getResolutions(),
						fmt.getDefautValue(fmt.getResolutions(), fmt.getDefaultResolution()), cmbResolution);
				addToModel(modelFrameRate, fmt.getFrameRate(),
						fmt.getDefautValue(fmt.getFrameRate(), fmt.getDefaultFrameRate()), cmbFrameRate);
				addToModel(modelVBR, fmt.getVideoBitrate(),
						fmt.getDefautValue(fmt.getVideoBitrate(), fmt.getDefaultVideoBitrate()), cmbVBR);
				addToModel(modelAsr, fmt.getAudioSampleRate(),
						fmt.getDefautValue(fmt.getAudioSampleRate(), fmt.getDefaultAudioSampleRate()), cmbAsr);
				addToModel(modelAbr, fmt.getAudioBitrate(),
						fmt.getDefautValue(fmt.getAudioBitrate(), fmt.getDefaultAudioBitrate()), cmbAbr);
				addToModel(modelSize, fmt.getAspectRatio(),
						fmt.getDefautValue(fmt.getAspectRatio(), fmt.getDefaultAspectRatio()), cmbSize);
				addToModel(modelAc, fmt.getAudioChannel(),
						fmt.getDefautValue(fmt.getAudioChannel(), fmt.getDefaultAudioChannel()), cmbAc);
				updateFormat();
			}
		});

		if (model.getSize() > 0) {
			cmbFormatGroup.setSelectedIndex(0);
		}
	}

	private void addToModel(DefaultComboBoxModel<String> model, List<String> list, String defaultValue,
			JComboBox<String> cmb) {
		model.removeAllElements();
		if (list == null) {
			return;
		}
		for (String s : list) {
			model.addElement(s);
		}
		if (!StringUtils.isNullOrEmptyOrBlank(defaultValue)) {
			cmb.setSelectedItem(defaultValue);
		}
	}

	private JButton createButton2(String text) {
		JButton btn = new CustomButton(text);
		btn.setBackground(ColorResource.getDarkBtnColor());
		btn.setBorderPainted(false);
		btn.setFocusPainted(false);
		btn.setForeground(Color.WHITE);
		btn.setFont(FontResource.getNormalFont());
		return btn;
	}

	public MediaFormat getFormat() {
		return fmt;
	}

	private String getCmbVal(JComboBox<String> cmb) {
		String val = (String) cmb.getSelectedItem();
		return StringUtils.isNullOrEmptyOrBlank(val) ? null : val;
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		if (e.getSource() instanceof JComponent) {
			String name = ((JComponent) e.getSource()).getName();
			if ("BTN_OK".equals(name)) {
				updateFormat();
				selected = true;
				setVisible(false);
			}
			if ("CLOSE".equals(name)) {
				setVisible(false);
				selected = false;
			}
		}
	}

	public boolean isApproveOption() {
		return selected;
	}

	private void updateFormat() {
		fmt = new MediaFormat();
		Format format = listFormat.getSelectedValue();
		fmt.setFormat(format.getExt());
		fmt.setDescription(format.getDesc());
		fmt.setResolution(Format.getSize((String) cmbResolution.getSelectedItem()));
		fmt.setVideo_codec((Format.getCodecName((String) cmbVideoCodec.getSelectedItem())));
		fmt.setVideo_bitrate(Format.getBitRate(getCmbVal(cmbVBR)));
		String fr = getCmbVal(cmbFrameRate);
		fmt.setFramerate(fr);
		fmt.setAspectRatio(Format.getAspec(getCmbVal(cmbSize)));
		fmt.setAudio_codec(Format.getCodecName(getCmbVal(cmbAudioCodec)));
		fmt.setAudio_bitrate(Format.getBitRate(getCmbVal(cmbAbr)));
		fmt.setSamplerate(getCmbVal(cmbAsr));
		fmt.setAudio_channel(getCmbVal(cmbAc));
		fmt.setVideo_param_extra(format.getVidExtra());
	}

}
