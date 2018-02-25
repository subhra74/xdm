package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.Color;
import java.awt.GraphicsDevice.WindowTranslucency;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import xdman.Config;
import xdman.mediaconversion.ConversionItem;
import xdman.mediaconversion.FFmpeg;
import xdman.mediaconversion.MediaConversionListener;
import xdman.mediaconversion.MediaFormat;
import xdman.mediaconversion.MediaFormatInfo;
import xdman.mediaconversion.MediaFormats;
import xdman.mediaconversion.MediaInfoExtractor;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public class VideoConversionWnd extends JFrame implements ActionListener, Runnable, MediaConversionListener {

	/**
	 * 
	 */
	private static final long serialVersionUID = -4989944222311757015L;
	DefaultListModel<ConversionItem> model;
	JList<ConversionItem> list;
	JButton btnOutFormat;
	JTextField txtOutFolder;
	JProgressBar prgConvert;
	JLabel lblConvertFormat, lblOutputFolder, lblPrg;
	JButton browse, convert, stop;
	JLabel lineLbl2;
	int mode = 0;// 0 - loading, 1 - converting
	ArrayList<String> filesToLoad;
	boolean stopflag;
	Thread t;
	MediaInfoExtractor extractor;
	FFmpeg ffmpeg;
	ConversionItemRender renderer;
	MediaFormatWnd fmtWnd;
	JCheckBox chkHwAccel;
	JLabel lblResolution, lblAudioCodec, lblVideoCodec, lblVolume;
	JSlider slVolume;
	DecimalFormatSymbols symbols;
	DecimalFormat format;
	FormatImageLabel lblImg;

	public VideoConversionWnd(ArrayList<String> files) {
		initUI();
		String str = String.format(StringResource.get("LBL_LOADING"), "");
		showProgressPanel();
		lblPrg.setText(str);
		this.filesToLoad = files;
		mode = 0;
		symbols = new DecimalFormatSymbols();
		symbols.setDecimalSeparator('.');
		format = new DecimalFormat("0.#");
		format.setDecimalFormatSymbols(symbols);
	}

	public void load() {
		t = new Thread(this);
		t.start();
	}

	private int getScaledValue(int input) {
		return getScaledInt(input);
	}

	private void stop() {
		stopflag = true;
		if (mode == 0) {
			if (extractor != null) {
				extractor.stop();
			}
		}
		if (mode == 1) {
			if (ffmpeg != null) {
				System.out.println("stopping ffmpeg");
				ffmpeg.stop();
			}
		}
	}

	private void initUI() {
		model = new DefaultListModel<>();
		list = new JList<>(model);

		list.addListSelectionListener(new ListSelectionListener() {

			@Override
			public void valueChanged(ListSelectionEvent e) {
				ConversionItem item = list.getSelectedValue();
				if (item != null) {
					slVolume.setValue(getVolume(item.volume));
				}
			}

			private int getVolume(String volume) {
				if (volume == null) {
					return 100;
				}
				try {
					float f = format.parse(volume).floatValue();
					return (int) (f * 100);
				} catch (ParseException e) {
					e.printStackTrace();
				}
				return 100;
			}
		});

		renderer = new ConversionItemRender();
		list.setCellRenderer(renderer);
		list.setBackground(ColorResource.getDarkestBgColor());
		list.setBorder(null);

		// list.setOpaque(false);

		setUndecorated(true);

		try {
			if (GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
					.isWindowTranslucencySupported(WindowTranslucency.TRANSLUCENT)) {
				if (!Config.getInstance().isNoTransparency())
					setOpacity(0.85f);
			}
		} catch (Exception e) {
			Logger.log(e);
		}

		setTitle(StringResource.get("MENU_MEDIA_CONVERTER"));
		setIconImage(ImageResource.get("icon.png").getImage());
		setSize(getScaledValue(700), getScaledValue(420));
		setLocationRelativeTo(null);
		getContentPane().setLayout(null);
		getContentPane().setBackground(ColorResource.getDarkestBgColor());

		JPanel titlePanel = new TitlePanel(null, this);
		titlePanel.setOpaque(false);
		titlePanel.setBounds(0, 0, getWidth(), getScaledValue(50));

		JButton closeBtn = new CustomButton();
		closeBtn.setBounds(getWidth() - getScaledValue(35), getScaledValue(5), getScaledValue(30), getScaledValue(30));
		closeBtn.setBackground(ColorResource.getDarkestBgColor());
		closeBtn.setBorderPainted(false);
		closeBtn.setFocusPainted(false);
		closeBtn.setName("CLOSE");

		closeBtn.setIcon(ImageResource.get("title_close.png"));
		closeBtn.addActionListener(this);
		titlePanel.add(closeBtn);

		JLabel titleLbl = new JLabel(StringResource.get("MENU_MEDIA_CONVERTER"));
		titleLbl.setFont(FontResource.getBiggerFont());
		titleLbl.setForeground(ColorResource.getSelectionColor());
		titleLbl.setBounds(getScaledValue(25), getScaledValue(15), getScaledValue(200), getScaledValue(30));
		titlePanel.add(titleLbl);

		MediaFormat[] fmts = MediaFormats.getSupportedFormats();
		MediaFormat[] formats = new MediaFormat[fmts.length - 1];
		for (int i = 1, j = 0; i < fmts.length; i++, j++) {
			formats[j] = fmts[i];
		}

		fmtWnd = new MediaFormatWnd();

		btnOutFormat = new CustomButton(fmtWnd.getFormat().getDescription());
		btnOutFormat.setHorizontalAlignment(JButton.LEFT);
		btnOutFormat.setBackground(ColorResource.getDarkBtnColor());
		btnOutFormat.setBorderPainted(false);
		btnOutFormat.setFocusPainted(false);
		btnOutFormat.setForeground(Color.WHITE);
		btnOutFormat.setFont(FontResource.getNormalFont());
		btnOutFormat.addActionListener(this);
		btnOutFormat.setName("FORMAT_SELECT");
		btnOutFormat.setMargin(new Insets(0, 0, 0, 0));
		btnOutFormat.setOpaque(true);
		btnOutFormat.setBounds(getWidth() - getScaledValue(350), getScaledValue(25), getScaledValue(300),
				getScaledValue(30));
		btnOutFormat.setName("FORMAT_SELECT");
		add(btnOutFormat);

		JLabel lineLbl = new JLabel();
		lineLbl.setBackground(ColorResource.getDarkBtnColor());
		lineLbl.setBounds(0, getScaledValue(55), getWidth(), 1);
		lineLbl.setOpaque(true);
		add(lineLbl);

		add(titlePanel);

		JLabel verticalLine = new JLabel();
		verticalLine.setBackground(ColorResource.getDarkBtnColor());
		verticalLine.setBounds(getScaledValue(500), getScaledValue(55), 1,
				getScaledValue(280 - 30) + getScaledValue(56));
		verticalLine.setOpaque(true);
		add(verticalLine);

		int y = getScaledValue(56);
		int h = getScaledValue(280 - 30);

		JScrollPane jsp = new JScrollPane(list);
		jsp.setBounds(0, y, getWidth() - getScaledValue(200), h + getScaledValue(55));
		jsp.setBorder(null);
		// jsp.setOpaque(false);
		DarkScrollBar scrollBar2 = new DarkScrollBar(JScrollBar.VERTICAL);
		jsp.setVerticalScrollBar(scrollBar2);
		jsp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		jsp.setAutoscrolls(true);

		y += h;
		y += getScaledValue(55);

		lineLbl2 = new JLabel();
		lineLbl2.setBackground(ColorResource.getDarkBgColor());
		lineLbl2.setBounds(0, y, getWidth(), 1);
		lineLbl2.setOpaque(true);
		add(lineLbl2);

		y += getScaledValue(15);

		int prevY = y;

		h = getScaledValue(30);

		// lblConvertFormat = new JLabel(StringResource.get("LBL_CONVERT_TO"));
		// lblConvertFormat.setFont(FontResource.getNormalFont());
		// lblConvertFormat.setHorizontalAlignment(JLabel.RIGHT);
		// lblConvertFormat.setBounds(0, y, getScaledValue(100), h);
		// add(lblConvertFormat);
		//
		//
		// y += h;
		// y += getScaledValue(5);

		chkHwAccel = new JCheckBox(StringResource.get("LBL_HW_ACCEL"));
		chkHwAccel.setBounds(getScaledValue(14), y, getScaledValue(190), getScaledValue(30));
		chkHwAccel.setIcon(ImageResource.get("unchecked.png"));
		chkHwAccel.setSelectedIcon(ImageResource.get("checked.png"));
		chkHwAccel.setOpaque(false);
		chkHwAccel.setFocusPainted(false);
		chkHwAccel.setForeground(Color.WHITE);
		chkHwAccel.setSelected(true);
		add(chkHwAccel);

		lblOutputFolder = new JLabel(StringResource.get("LBL_SAVE_IN"));
		lblOutputFolder.setHorizontalAlignment(JLabel.RIGHT);
		lblOutputFolder.setFont(FontResource.getNormalFont());
		lblOutputFolder.setBounds(getScaledValue(160), y, getScaledValue(100), h);
		add(lblOutputFolder);

		txtOutFolder = new JTextField();
		txtOutFolder.setText(getVideoFolder());
		// txtOutFolder.setBorder(new LineBorder(ColorResource.getSelectionColor(), 1));
		// txtOutFolder.setBackground(ColorResource.getDarkestBgColor());
		// txtOutFolder.setForeground(Color.WHITE);
		// txtOutFolder.setCaretColor(ColorResource.getSelectionColor());
		txtOutFolder.setBounds(getScaledValue(270), y + getScaledValue(5), getScaledValue(200), getScaledValue(20));
		add(txtOutFolder);

		browse = new CustomButton("...");
		browse.setBackground(ColorResource.getDarkBtnColor());
		browse.setBorderPainted(false);
		browse.setFocusPainted(false);
		browse.setForeground(Color.WHITE);
		browse.setFont(FontResource.getNormalFont());
		browse.addActionListener(this);
		browse.setName("BROWSE_FOLDER");
		browse.setMargin(new Insets(0, 0, 0, 0));
		browse.setBounds(getScaledValue(545 - 70), y + getScaledValue(5), getScaledValue(50), getScaledValue(20));
		add(browse);

		convert = new CustomButton(StringResource.get("OPT_CONVERT"));
		convert.setBackground(ColorResource.getDarkBtnColor());
		convert.setBorderPainted(false);
		convert.setFocusPainted(false);
		convert.setForeground(Color.WHITE);
		convert.setFont(FontResource.getNormalFont());
		convert.setName("CONVERT");
		convert.setMargin(new Insets(0, 0, 0, 0));
		convert.setBounds(getScaledValue(610 - 50), y, getScaledValue(120), getScaledValue(30));
		convert.addActionListener(this);
		add(convert);

		add(jsp);

		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				onClosed();
			}
		});

		y = prevY;

		h = getScaledValue(30);
		lblPrg = new JLabel(StringResource.get("TITLE_CONVERT"));
		lblPrg.setBounds(getScaledValue(10), y - getScaledValue(10), getScaledValue(400), h);
		add(lblPrg);

		y += h;
		prgConvert = new JProgressBar();
		prgConvert.setBounds(getScaledValue(10), y - getScaledValue(10), getScaledValue(570), getScaledValue(2));
		prgConvert.setBorder(null);
		prgConvert.setValue(30);
		add(prgConvert);

		y = prevY;

		stop = new CustomButton(StringResource.get("BTN_STOP_PROCESSING"));
		stop.setBackground(ColorResource.getDarkBtnColor());
		stop.setBorderPainted(false);
		stop.setFocusPainted(false);
		stop.setForeground(Color.WHITE);
		stop.setFont(FontResource.getNormalFont());
		stop.addActionListener(this);
		stop.setName("STOP");
		stop.setMargin(new Insets(0, 0, 0, 0));
		stop.setBounds(getScaledValue(590), y, getScaledValue(100), h);
		stop.addActionListener(this);
		add(stop);
		y += h;

		y += getScaledValue(30);

		// showProgressPanel();

		list.setFixedCellWidth(getWidth());
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

		//
		//
		// JMenuItem item1 = new JMenuItem("150%");
		// item1.addActionListener(this);
		// item1.setName("1.5");
		// JMenuItem item2 = new JMenuItem("200%");
		// item2.addActionListener(this);
		// item2.setName("2.0");
		// JMenuItem item3 = new JMenuItem("250%");
		// item3.addActionListener(this);
		// item3.setName("2.5");
		// JMenuItem item4 = new JMenuItem("300%");
		// item4.addActionListener(this);
		// item4.setName("3.0");
		//
		// pop.add(item1);
		// pop.add(item2);
		// pop.add(item3);
		// pop.add(item4);
		// cmbOutFormat.setSelectedIndex(0);

		lblResolution = new JLabel("Resolution: ");
		lblVideoCodec = new JLabel("Video codec: ");
		lblAudioCodec = new JLabel("Audio code: ");
		lblVolume = new JLabel("Volume: ");

		slVolume = new JSlider();
		slVolume.setMaximum(200);
		slVolume.setMinimum(0);
		slVolume.setValue(100);
		slVolume.setOpaque(false);

		y = getScaledValue(55 + 150);
		lblResolution.setBounds(getScaledValue(510), y, getScaledValue(180), getScaledValue(30));
		y += getScaledValue(30);
		lblVideoCodec.setBounds(getScaledValue(510), y, getScaledValue(180), getScaledValue(30));
		y += getScaledValue(30);
		lblAudioCodec.setBounds(getScaledValue(510), y, getScaledValue(180), getScaledValue(30));
		y += getScaledValue(30);
		lblVolume.setBounds(getScaledValue(510), y, getScaledValue(180), getScaledValue(30));
		y += getScaledValue(30);
		slVolume.setBounds(getScaledValue(510), y, getScaledValue(180), getScaledValue(30));
		y += getScaledValue(30);

		add(lblResolution);
		add(lblVideoCodec);
		add(lblAudioCodec);
		add(lblVolume);
		add(slVolume);

		lblImg = new FormatImageLabel(1, ImageResource.get("covert_video.png"));
		lblImg.setFont(FontResource.getBigBoldFont());
		// lblImg.setHorizontalAlignment(JLabel.CENTER);
		// lblImg.setVerticalAlignment(JLabel.CENTER);
		lblImg.setBounds(getScaledValue(501), getScaledValue(56), getScaledValue(200), getScaledValue(160));
		add(lblImg);
		MediaFormat f = fmtWnd.getFormat();
		setDetails(f.getResolution(), f.getVideo_codec(), f.getAudio_codec());
		lblImg.setFormat(f.getFormat());
		slVolume.addChangeListener(new ChangeListener() {

			@Override
			public void stateChanged(ChangeEvent e) {
				ConversionItem item = list.getSelectedValue();
				if (item != null) {
					if (!slVolume.getValueIsAdjusting()) {
						item.volume = String.format("%.1f", slVolume.getValue() / 100f);
						System.out.println(item.volume);
					}
				}

			}
		});
	}

	private void setDetails(String resolution, String videoCodec, String audioCodec) {
		this.lblResolution
				.setText("Resolution: " + (StringUtils.isNullOrEmptyOrBlank(resolution) ? "Original" : resolution));
		this.lblVideoCodec
				.setText("Video codec: " + (StringUtils.isNullOrEmptyOrBlank(videoCodec) ? "None" : videoCodec));
		this.lblAudioCodec
				.setText("Audio codec: " + (StringUtils.isNullOrEmptyOrBlank(audioCodec) ? "None" : audioCodec));
	}

	private void showProgressPanel() {
		lblOutputFolder.setVisible(false);
		// lblConvertFormat.setVisible(false);
		btnOutFormat.setVisible(false);
		txtOutFolder.setVisible(false);
		browse.setVisible(false);
		convert.setVisible(false);
		lblPrg.setVisible(true);
		prgConvert.setVisible(true);
		chkHwAccel.setVisible(false);
		stop.setVisible(true);
	}

	private void showFormatPanel() {
		lblOutputFolder.setVisible(true);
		// lblConvertFormat.setVisible(true);
		btnOutFormat.setVisible(true);
		txtOutFolder.setVisible(true);
		browse.setVisible(true);
		convert.setVisible(true);
		lblPrg.setVisible(false);
		prgConvert.setVisible(false);
		chkHwAccel.setVisible(true);
		stop.setVisible(false);
		if (!model.isEmpty()) {
			list.setSelectedIndex(0);
		}
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		if (e.getSource() instanceof JComponent) {
			JComponent btn = (JComponent) e.getSource();
			String name = btn.getName();

			switch (name) {
			case "CLOSE":
				onClosed();
				break;
			case "FORMAT_SELECT":
				fmtWnd.setVisible(true);
				if (fmtWnd.isApproveOption()) {
					MediaFormat fmt = fmtWnd.getFormat();
					btnOutFormat.setText(fmt.getDescription());
					setDetails(fmt.getResolution(), fmt.getVideo_codec(), fmt.getAudio_codec());
					lblImg.setFormat(fmt.getFormat());
				}
				break;
			case "STOP":
				stop();
				break;
			case "CONVERT":
				for (int i = 0; i < model.size(); i++) {
					ConversionItem item = model.getElementAt(i);
					String file = XDMUtils.getFileNameWithoutExtension(item.inputFileName);
					String ext = fmtWnd.getFormat().getFormat();
					item.outFileName = file + "." + ext;
				}
				System.out.println("starting convert");
				mode = 1;
				t = new Thread(this);
				t.start();
				break;
			case "BROWSE_FOLDER":
				JFileChooser jfc = new JFileChooser();
				jfc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
				if (jfc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
					txtOutFolder.setText(jfc.getSelectedFile().getAbsolutePath());
				}
				break;
			}
		}

	}

	void onClosed() {
		stop();
		dispose();
	}

	@Override
	public void run() {
		stopflag = false;
		if (mode == 0) {
			loadFiles();
		} else if (mode == 1) {
			System.out.println("starting conversion");
			convertFiles();
		}
	}

	private String getVideoFolder() {
		File f = new File(System.getProperty("user.home"), "Videos");
		if (!f.exists()) {
			f.mkdirs();
		}
		return f.getAbsolutePath();
	}

	private void convertFiles() {
		SwingUtilities.invokeLater(new Runnable() {

			@Override
			public void run() {
				showProgressPanel();
			}
		});
		try {
			if (filesToLoad == null || filesToLoad.size() < 1)
				return;
			for (int i = 0; i < model.size(); i++) {
				if (stopflag) {
					return;
				}

				ConversionItem item = model.getElementAt(i);
				System.out.println("item: " + item);

				String file = item.inputFile;

				File outFile = new File(txtOutFolder.getText(), item.outFileName);
				System.out.println(outFile);

				MediaFormat fmt = fmtWnd.getFormat();// MediaFormats.getSupportedFormats()[cmbOutFormat.getSelectedIndex()
														// + 1];
				System.out.println("format: " + fmt.getFormat());

				this.ffmpeg = new FFmpeg(Arrays.asList(new String[] { file }), outFile.getAbsolutePath(), this, fmt,
						false);
				if (item.volume != null) {
					this.ffmpeg.setVolume(item.volume);
				}

				this.ffmpeg.setUseHwAccel(chkHwAccel.isSelected());

				SwingUtilities.invokeLater(new Runnable() {

					@Override
					public void run() {
						String str = String.format(StringResource.get("LBL_FILE_CONVERT_PRG"), item.inputFileName);
						lblPrg.setText(str);
					}
				});

				int ret = ffmpeg.convert();
				if (ret == 0) {
					item.conversionState = 1;
				} else {
					item.conversionState = 2;
				}
				model.setElementAt(item, i);
				Logger.log("FFmpeg exit code: " + ret);
			}
		} finally {
			SwingUtilities.invokeLater(new Runnable() {

				@Override
				public void run() {
					showFormatPanel();
				}
			});
		}

	}

	private void loadFiles() {
		try {
			if (filesToLoad == null || filesToLoad.size() < 1)
				return;
			for (int i = 0; i < filesToLoad.size(); i++) {
				if (stopflag) {
					return;
				}
				String file = filesToLoad.get(i);
				extractor = new MediaInfoExtractor();
				MediaFormatInfo info = extractor.getInfo(file);
				ConversionItem item = new ConversionItem();
				item.inputFile = file;
				item.info = info;
				item.inputFileName = new File(file).getName();
				final int count = i;
				SwingUtilities.invokeLater(new Runnable() {
					@Override
					public void run() {
						model.addElement(item);
						prgConvert.setValue((count * 100) / filesToLoad.size());
					}
				});
				extractor = null;
			}
		} finally {
			SwingUtilities.invokeLater(new Runnable() {

				@Override
				public void run() {
					showFormatPanel();
				}
			});
		}
	}

	long lastTime = 0;

	@Override
	public void progress(int progress) {
		long currentTime = System.currentTimeMillis();
		if (currentTime - lastTime > 1000) {
			lastTime = currentTime;
			SwingUtilities.invokeLater(new Runnable() {

				@Override
				public void run() {
					prgConvert.setValue(progress);
				}
			});
		}
	}
}