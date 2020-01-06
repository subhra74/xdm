package xdman.ui.components;

import java.awt.Color;
import java.awt.GraphicsDevice.WindowTranslucency;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ArrayList;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

import xdman.Config;
import xdman.mediaconversion.FFmpeg;
import xdman.mediaconversion.MediaConversionListener;
import xdman.mediaconversion.MediaFormat;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.FormatUtilities;
import xdman.util.Logger;
import static xdman.util.XDMUtils.getScaledInt;

public class MediaConversionWnd extends JFrame implements ActionListener, MediaConversionListener, Runnable {
	/**
	 * 
	 */
	private static final long serialVersionUID = 2457934978220571669L;
	private JPanel titlePanel, panel;
	private JButton closeBtn, minBtn;
	private JLabel titleLbl;
	private File input, output;
	private MediaFormat format;
	private FFmpeg ffmpeg;
	private Thread thread;
	private JProgressBar prg;
	private JLabel statLbl;
	private int lastProgress;
	private long prevTime;
	private CustomButton btnCN;

	public MediaConversionWnd(File input, File output, MediaFormat format, long sourceDuration) {
		this.input = input;
		this.output = output;
		this.format = format;
		init();
	}

	public void convert() {
		thread = new Thread(this);
		prevTime = System.currentTimeMillis();
		thread.start();
	}

	@Override
	public void progress(int progress) {
		if (progress >= prg.getMinimum() && progress <= prg.getMaximum()) {
			prg.setValue(progress);
		}

		int prgDiff = progress - lastProgress;
		long now = System.currentTimeMillis();
		long timeSpend = now - prevTime;
		if (timeSpend > 0) {
			if (prgDiff > 0) {
				long eta = (timeSpend * (100 - progress) / 1000 * prgDiff);// prgDiff
				lastProgress = progress;
				statLbl.setText("ETA: " + FormatUtilities.hms((int) eta));
			}
			prevTime = now;
		}
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		if (e.getSource() instanceof JComponent) {
			String name = ((JComponent) e.getSource()).getName();
			if (name == null) {
				return;
			}
			if (name.equals("CLOSE")) {
				stop();
			}
			if (name.equals("MIN")) {
				this.setExtendedState(this.getExtendedState() | JFrame.ICONIFIED);
			}
		}
	}

	private void init() {
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

		setIconImage(ImageResource.get("icon.png").getImage());
		setSize(getScaledInt(350), getScaledInt(200));
		setLocationRelativeTo(null);
		setResizable(false);

		getContentPane().setLayout(null);
		getContentPane().setBackground(ColorResource.getDarkestBgColor());

		titlePanel = new TitlePanel(null, this);
		titlePanel.setOpaque(false);
		titlePanel.setBounds(0, 0, getScaledInt(350), getScaledInt(50));

		closeBtn = new CustomButton();
		closeBtn.setBounds(getScaledInt(320), getScaledInt(5), getScaledInt(24), getScaledInt(24));
		closeBtn.setIcon(ImageResource.get("title_close.png"));
		closeBtn.setBackground(ColorResource.getDarkestBgColor());
		closeBtn.setBorderPainted(false);
		closeBtn.setFocusPainted(false);
		closeBtn.setName("CLOSE");
		closeBtn.addActionListener(this);

		minBtn = new CustomButton();
		minBtn.setBounds(getScaledInt(296), getScaledInt(5), getScaledInt(24), getScaledInt(24));
		minBtn.setIcon(ImageResource.get("title_min.png"));
		minBtn.setBackground(ColorResource.getDarkestBgColor());
		minBtn.setBorderPainted(false);
		minBtn.setFocusPainted(false);
		minBtn.setName("MIN");
		minBtn.addActionListener(this);

		titleLbl = new JLabel(StringResource.get("TITLE_CONVERT"));
		titleLbl.setFont(FontResource.getBiggerFont());
		titleLbl.setForeground(ColorResource.getSelectionColor());
		titleLbl.setBounds(getScaledInt(25), getScaledInt(15), getScaledInt(250), getScaledInt(30));

		JLabel lineLbl = new JLabel();
		lineLbl.setBackground(ColorResource.getSelectionColor());
		lineLbl.setBounds(0, getScaledInt(55), getScaledInt(400), 2);
		lineLbl.setOpaque(true);

		prg = new JProgressBar();
		prg.setBounds(getScaledInt(20), getScaledInt(85), getScaledInt(350) - getScaledInt(40), getScaledInt(5));

		statLbl = new JLabel();
		statLbl.setForeground(Color.WHITE);
		statLbl.setBounds(getScaledInt(20), getScaledInt(100), getScaledInt(350) - getScaledInt(40), getScaledInt(25));

		titlePanel.add(titleLbl);
		titlePanel.add(minBtn);
		titlePanel.add(closeBtn);

		add(lineLbl);
		add(titlePanel);
		add(prg);
		add(statLbl);

		panel = new JPanel(null);
		panel.setBounds(getScaledInt(0), getScaledInt(150), getScaledInt(350), getScaledInt(50));
		panel.setBackground(Color.DARK_GRAY);

		btnCN = new CustomButton(StringResource.get("MENU_PAUSE"));
		btnCN.setBounds(0, 1, getScaledInt(350), getScaledInt(50));
		btnCN.setName("CLOSE");
		applyStyle(btnCN);
		panel.add(btnCN);
		add(panel);
	}

	private void applyStyle(CustomButton btn) {
		btn.addActionListener(this);
		btn.setBackground(ColorResource.getDarkestBgColor());
		btn.setForeground(Color.WHITE);
		btn.setPressedBackground(ColorResource.getDarkerBgColor());
		btn.setFont(FontResource.getBigFont());
		btn.setBorderPainted(false);
		btn.setMargin(new Insets(0, 0, 0, 0));
		btn.setFocusPainted(false);
		btn.setFocusPainted(false);
	}

	private void finished(int ret) {
		if (ret == 0) {
			dispose();
		} else {
			prg.setVisible(false);
			statLbl.setText(StringResource.get("LBL_CONV_FAILED"));
			btnCN.setText(StringResource.get("LBL_CLOSE"));
		}
	}

	private void stop() {
		try {
			if (ffmpeg != null) {
				ffmpeg.stop();
				ffmpeg = null;
			}
		} catch (Exception e) {
			Logger.log(e);
		}
		dispose();
	}

	@Override
	public void run() {
		int ret = -1;
		try {
			ArrayList<String> inputFiles = new ArrayList<>();
			inputFiles.add(input.getAbsolutePath());
			this.ffmpeg = new FFmpeg(inputFiles, output.getAbsolutePath(), this, format, false);
			ffmpeg.convert();
			ret = ffmpeg.getFfExitCode();
			Logger.log("FFmpeg exit code: " + ret);
		} catch (Exception e) {
			Logger.log(e);
			ret = -1;
		}
		final int r = ret;
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				finished(r);
			}
		});
	}
}
