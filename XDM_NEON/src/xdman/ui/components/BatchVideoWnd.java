package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.Color;
import java.awt.GraphicsDevice.WindowTranslucency;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.LineBorder;

import xdman.Config;
import xdman.DownloadQueue;
import xdman.QueueManager;
import xdman.XDMApp;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.mediaconversion.MediaFormat;
import xdman.mediaconversion.MediaFormats;
import xdman.ui.components.MediaDownloaderWnd.VideoWrapper;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.Logger;
import xdman.util.XDMUtils;

public class BatchVideoWnd extends JDialog implements ActionListener {
	private static final long serialVersionUID = -6712422188220449618L;
	private JTextField txtFile, txtQName;
	private JLabel lblName;
	private JComboBox<String> cmbQueOpts;
	private JComboBox<DownloadQueue> cmbQueues;
	private DefaultComboBoxModel<DownloadQueue> queueModel;
	private SimpleDateFormat dateFormat;
	private JComboBox<MediaFormat> cmbOutFormat;

	private ArrayList<VideoWrapper> items;

	public BatchVideoWnd(ArrayList<VideoWrapper> items) {
		this.items = items;
		initUI();
	}

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

		setIconImage(ImageResource.get("icon.png").getImage());
		setSize(getScaledInt(400), getScaledInt(310));
		setLocationRelativeTo(null);
		setAlwaysOnTop(true);
		getContentPane().setLayout(null);
		getContentPane().setBackground(ColorResource.getDarkestBgColor());

		JPanel titlePanel = new TitlePanel(null, this);
		titlePanel.setOpaque(false);
		titlePanel.setBounds(0, 0, getScaledInt(400), getScaledInt(50));

		JButton closeBtn = new CustomButton();
		closeBtn.setBounds(getScaledInt(365), getScaledInt(5), getScaledInt(30), getScaledInt(30));
		closeBtn.setBackground(ColorResource.getDarkestBgColor());
		closeBtn.setBorderPainted(false);
		closeBtn.setFocusPainted(false);
		closeBtn.setName("CLOSE");

		closeBtn.setIcon(ImageResource.get("title_close.png"));
		closeBtn.addActionListener(this);
		titlePanel.add(closeBtn);

		JLabel titleLbl = new JLabel(StringResource.get("VID_TITLE"));
		titleLbl.setFont(FontResource.getBiggerFont());
		titleLbl.setForeground(ColorResource.getSelectionColor());
		titleLbl.setBounds(getScaledInt(25), getScaledInt(15), getScaledInt(200), getScaledInt(30));
		titlePanel.add(titleLbl);

		JLabel lineLbl = new JLabel();
		lineLbl.setBackground(ColorResource.getSelectionColor());
		lineLbl.setBounds(0, getScaledInt(55), getScaledInt(400), 1);
		lineLbl.setOpaque(true);
		add(lineLbl);

		txtFile = new JTextField();
		txtFile.setBorder(new LineBorder(ColorResource.getSelectionColor(), 1));
		txtFile.setBackground(ColorResource.getDarkestBgColor());
		txtFile.setForeground(Color.WHITE);
		txtFile.setBounds(getScaledInt(97), getScaledInt(80), getScaledInt(241) - getScaledInt(20), getScaledInt(20));
		txtFile.setCaretColor(ColorResource.getSelectionColor());

		add(txtFile);

		txtFile.setText(Config.getInstance().getDownloadFolder());

		JButton browse = new CustomButton("...");
		browse.setName("BROWSE_FOLDER");
		browse.setMargin(new Insets(0, 0, 0, 0));
		browse.setBounds(getScaledInt(325), getScaledInt(80), getScaledInt(40), getScaledInt(20));
		browse.setFocusPainted(false);
		browse.setBackground(ColorResource.getDarkestBgColor());
		browse.setBorder(new LineBorder(ColorResource.getSelectionColor(), 1));
		browse.setForeground(Color.WHITE);
		browse.addActionListener(this);
		browse.setFont(FontResource.getItemFont());
		add(browse);

		add(titlePanel);

		JLabel lblFile = new JLabel(StringResource.get("LBL_SAVE_IN"), JLabel.RIGHT);
		lblFile.setFont(FontResource.getNormalFont());
		lblFile.setForeground(Color.WHITE);
		lblFile.setBounds(getScaledInt(10), getScaledInt(80), getScaledInt(81), getScaledInt(23));
		add(lblFile);

		JLabel lblFmt = new JLabel(StringResource.get("LBL_CONVERT_TO"), JLabel.RIGHT);
		lblFmt.setFont(FontResource.getNormalFont());
		lblFmt.setForeground(Color.WHITE);
		lblFmt.setBounds(getScaledInt(10), getScaledInt(120), getScaledInt(81), getScaledInt(23));
		add(lblFmt);

		cmbOutFormat = new JComboBox<>(MediaFormats.getSupportedFormats());
		cmbOutFormat.setFont(FontResource.getNormalFont());
		cmbOutFormat.setName("FORMAT_SELECT");
		cmbOutFormat.setBounds(getScaledInt(97), getScaledInt(120), getScaledInt(200) - getScaledInt(20),
				getScaledInt(23));
		cmbOutFormat.addActionListener(this);
		add(cmbOutFormat);

		JLabel lblQueue = new JLabel(StringResource.get("LBL_QUEUE_USE"), JLabel.RIGHT);
		lblQueue.setFont(FontResource.getNormalFont());
		lblQueue.setForeground(Color.WHITE);
		lblQueue.setBounds(getScaledInt(10), getScaledInt(160), getScaledInt(81), getScaledInt(23));
		add(lblQueue);

		cmbQueOpts = new JComboBox<>(new String[] { StringResource.get("LBL_QUEUE_OPT1"),
				StringResource.get("LBL_QUEUE_OPT2"), StringResource.get("LBL_QUEUE_OPT3") });

		cmbQueOpts.setFont(FontResource.getNormalFont());
		cmbQueOpts.setForeground(Color.WHITE);
		cmbQueOpts.setName("QUEUE_OPTS");
		cmbQueOpts.setBounds(getScaledInt(97), getScaledInt(160), getScaledInt(200) - getScaledInt(20),
				getScaledInt(23));
		cmbQueOpts.addActionListener(this);
		// cmbQueOpts.addItemListener(new ItemListener() {
		//
		// @Override
		// public void itemStateChanged(ItemEvent e) {
		// queueOptionUpdated();
		//
		// }
		// });
		add(cmbQueOpts);

		lblName = new JLabel(StringResource.get("LBL_NEW_QUEUE"), JLabel.RIGHT);
		lblName.setFont(FontResource.getNormalFont());
		lblName.setForeground(Color.WHITE);
		lblName.setBounds(getScaledInt(10), getScaledInt(200), getScaledInt(81), getScaledInt(23));
		add(lblName);

		txtQName = new JTextField();
		txtQName.setBorder(new LineBorder(ColorResource.getSelectionColor(), 1));
		txtQName.setBackground(ColorResource.getDarkestBgColor());
		txtQName.setForeground(Color.WHITE);
		txtQName.setBounds(getScaledInt(97), getScaledInt(200), getScaledInt(241) - getScaledInt(20), getScaledInt(20));
		txtQName.setCaretColor(ColorResource.getSelectionColor());
		txtQName.setText(getNewQueName());
		add(txtQName);

		queueModel = new DefaultComboBoxModel<DownloadQueue>();
		ArrayList<DownloadQueue> qlist = QueueManager.getInstance().getQueueList();
		for (int i = 0; i < qlist.size(); i++) {
			queueModel.addElement(qlist.get(i));
		}
		cmbQueues = new JComboBox<>(queueModel);
		cmbQueues.setRenderer(new QueueListRenderer());
		cmbQueues.setBounds(getScaledInt(97), getScaledInt(200), getScaledInt(241) - getScaledInt(20),
				getScaledInt(20));
		cmbQueues.setVisible(false);
		add(cmbQueues);

		JPanel panel = new JPanel(null);
		panel.setBounds(0, getScaledInt(260), getScaledInt(400), getScaledInt(50));
		panel.setBackground(Color.DARK_GRAY);
		add(panel);

		CustomButton downloadBtn = new CustomButton(StringResource.get("ND_DOWNLOAD_NOW"));
		styleButton(downloadBtn);
		downloadBtn.setName("DOWNLOAD");
		downloadBtn.setBounds(getScaledInt(201), 1, getScaledInt(200), getScaledInt(50));
		panel.add(downloadBtn);
		CustomButton cancelBtn = new CustomButton(StringResource.get("ND_CANCEL"));
		styleButton(cancelBtn);
		cancelBtn.setName("CANCEL");
		cancelBtn.setBounds(0, 1, getScaledInt(200), getScaledInt(50));
		panel.add(cancelBtn);
	}

	private void styleButton(CustomButton btn) {
		btn.setBackground(ColorResource.getDarkestBgColor());
		btn.setPressedBackground(ColorResource.getDarkerBgColor());
		btn.setForeground(Color.WHITE);
		btn.setFont(FontResource.getBigFont());
		btn.setBorderPainted(false);
		btn.setMargin(new Insets(0, 0, 0, 0));
		btn.setFocusPainted(false);
		btn.addActionListener(this);
	}

	// private JButton createButton(String name) {
	// JButton btn = new CustomButton(StringResource.get(name));
	// btn.setBackground(ColorResource.getDarkBtnColor());
	// btn.setBorderPainted(false);
	// btn.setFocusPainted(false);
	// btn.setForeground(Color.WHITE);
	// btn.setFont(FontResource.getNormalFont());
	// btn.addActionListener(this);
	// return btn;
	// }

	@Override
	public void actionPerformed(ActionEvent e) {
		String name = ((JComponent) e.getSource()).getName();
		if ("BROWSE_FOLDER".equals(name)) {
			JFileChooser jfc = XDMFileChooser.getFileChooser(JFileChooser.DIRECTORIES_ONLY,
					new File(txtFile.getText()));
			if (jfc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
				txtFile.setText(jfc.getSelectedFile().getAbsolutePath());
			}
		} else if ("DOWNLOAD".equals(name)) {
			download();
			dispose();
		} else if ("CLOSE".equals(name)) {
			dispose();
		} else if ("QUEUE_OPTS".equals(name)) {
			queueOptionUpdated();
		}
	}

	private void queueOptionUpdated() {
		System.out.println("called");
		if (cmbQueOpts.getSelectedIndex() == 0) {
			lblName.setVisible(true);
			txtQName.setVisible(true);
			cmbQueues.setVisible(false);
			txtQName.setText(getNewQueName());
		} else {
			lblName.setVisible(false);
			txtQName.setVisible(false);
			cmbQueues.setVisible((cmbQueOpts.getSelectedIndex() == 1));
		}
	}

	private String getNewQueName() {
		if (dateFormat == null) {
			dateFormat = new SimpleDateFormat("d MMM HH:mm");
		}

		return StringResource.get("Q_WORD") + " " + dateFormat.format(new Date());
	}

	private void createDownload(DownloadQueue q) {
		String folder = txtFile.getText();
		for (VideoWrapper vw : this.items) {
			String file = XDMUtils.createSafeFileName(vw.file);
			HttpMetadata metadata = vw.md;
			XDMApp.getInstance().createDownload(file, folder, metadata, q == null, q == null ? "" : q.getQueueId(), 0,
					0);
		}
	}

	private void download() {
		int opt = cmbQueOpts.getSelectedIndex();

		switch (opt) {
		case 0:
			DownloadQueue q = QueueManager.getInstance().createNewQueue();
			q.setName(txtQName.getText());
			QueueManager.getInstance().saveQueues();
			createDownload(q);
			q.start();
			break;
		case 1:
			DownloadQueue queue = (DownloadQueue) cmbQueues.getSelectedItem();
			if (queue != null) {
				createDownload(queue);
				queue.start();
				break;
			}
			break;
		case 2:
			createDownload(null);
			break;
		}

	}
}