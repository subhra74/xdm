/*
 * Copyright (c)  Subhra Das Gupta
 *
 * This file is part of Xtreme Download Manager.
 *
 * Xtreme Download Manager is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Xtreme Download Manager is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with Xtream Download Manager; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 * 
 */

package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.border.EmptyBorder;

import org.tinylog.Logger;
import org.tukaani.xz.XZInputStream;

import xdman.Config;
import xdman.DownloadListener;
import xdman.downloaders.http.HttpDownloader;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.util.FFExtractCallback;
import xdman.util.IOUtils;
import xdman.util.XDMUtils;

@SuppressWarnings({"unused", "ResultOfMethodCallIgnored"})
public class ComponentInstaller extends JDialog implements DownloadListener, FFExtractCallback {

	private static final long serialVersionUID = -7332687839394110921L;
	private JLabel prgLabel;
	private JProgressBar prg;
	private HttpDownloader d;
	private String url = "http://xdman.sourceforge.net/components/";
	private final String tmpFile;
	private boolean stop;

	public ComponentInstaller() {
		initUI();
		if (XDMUtils.detectOS() == XDMUtils.WINDOWS) {
			if (XDMUtils.below7()) {
				url += "xp.zip.xz";
			} else {
				url += "win.zip.xz";
			}
		} else if (XDMUtils.detectOS() == XDMUtils.MAC) {
			url += "mac.zip.xz";
		} else if (XDMUtils.detectOS() == XDMUtils.LINUX) {
			if (XDMUtils.getOsArch() == 32) {
				url += "linux86.zip.xz";
			} else {
				url += "linux64.zip.xz";
			}
		}
		tmpFile = UUID.randomUUID().toString();
		start();
	}

	private void start() {
		HttpMetadata metadata = new HttpMetadata();
		metadata.setUrl(url);
		Logger.info(url);
		d = new HttpDownloader(metadata.getId(), Config.getInstance().getTemporaryFolder(), metadata);
		d.registerListener(this);
		d.start();
	}

	@Override
	public void downloadFinished(String id) {
		extractFFmpeg();
		prgLabel.setText("Installing...");
		setVisible(false);
	}

	@Override
	public void downloadFailed(String id) {
		deleteTmpFiles(id);
		JOptionPane.showMessageDialog(this, "Failed to download components");
		setVisible(false);
	}

	@Override
	public void downloadStopped(String id) {
		deleteTmpFiles(id);
		setVisible(false);
	}

	@Override
	public void downloadConfirmed(String id) {

	}

	@Override
	public void downloadUpdated(String id) {
		int val = d.getProgress();
		prg.setValue(val);
	}

	@Override
	public String getOutputFolder(String id) {
		return Config.getInstance().getTemporaryFolder();
	}

	@Override
	public String getOutputFile(String id, boolean update) {
		return tmpFile;
	}

	private void deleteTmpFiles(String id) {
		Logger.info("Deleting metadata for " + id);
		File mf = new File(Config.getInstance().getMetadataFolder(), id);
		boolean deleted = mf.delete();
		Logger.info("Deleted manifest " + id + " " + deleted);
		File df = new File(Config.getInstance().getTemporaryFolder(), id);
		File[] files = df.listFiles();
		if (files != null && files.length > 0) {
			for (File f : files) {
				deleted = f.delete();
				Logger.info("Deleted tmp file " + id + " " + deleted);
			}
		}
		deleted = df.delete();
		Logger.info("Deleted tmp folder " + id + " " + deleted);
	}

	private void extractFFmpeg() {
		ZipInputStream zipIn = null;
		OutputStream out = null;
		try {
			File input = new File(Config.getInstance().getTemporaryFolder(), tmpFile);
			zipIn = new ZipInputStream(new XZInputStream(new FileInputStream(input)));

			while (true) {
				ZipEntry ent = zipIn.getNextEntry();
				if (ent == null)
					break;
				String name = ent.getName();
				File outFile = new File(Config.getInstance().getDataFolder(), name);
				out = new FileOutputStream(outFile);
				byte[] buf = new byte[8192];
				while (true) {
					int x = zipIn.read(buf);
					if (x == -1)
						break;
					out.write(buf, 0, x);
				}
				IOUtils.closeFlow(out);
				out = null;
				outFile.setExecutable(true);
			}
			input.delete();
		} catch (Exception e) {
			Logger.error(e);
			JOptionPane.showMessageDialog(this, "Component installation failed");
		} finally {
			IOUtils.closeFlow(zipIn);
			IOUtils.closeFlow(out);
		}
	}

	public void stop() {
		cancel();
	}

	private void initUI() {
		setSize(getScaledInt(400), getScaledInt(300));
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		setLocationRelativeTo(null);
		setModal(true);
		setAlwaysOnTop(true);
		setResizable(false);
		setTitle("Installing XDM");
		setLayout(null);

		JPanel titlePanel = new JPanel(new BorderLayout());
		titlePanel.setBounds(0, 0, getScaledInt(400), getScaledInt(60));
		titlePanel.setBackground(Color.WHITE);
		JLabel titleLabel = new JLabel("Installing FFmpeg multimedia library");
		titleLabel.setBorder(new EmptyBorder(getScaledInt(10), getScaledInt(10), getScaledInt(10), getScaledInt(10)));
		titleLabel.setFont(FontResource.getBigBoldFont());
		titleLabel.setForeground(Color.BLACK);
		titlePanel.add(titleLabel);
		add(titlePanel);

		prgLabel = new JLabel("Downloading...");
		prgLabel.setBounds(getScaledInt(20), getScaledInt(80), getScaledInt(360), getScaledInt(30));
		add(prgLabel);

		prg = new JProgressBar();
		prg.setBounds(getScaledInt(20), getScaledInt(110), getScaledInt(350), getScaledInt(10));
		add(prg);

		JButton btn = createButton2();
		btn.setBounds(getBounds().width - getScaledInt(80) - getScaledInt(20), getBounds().height - getScaledInt(80),
				getScaledInt(80), getScaledInt(30));
		add(btn);

		btn.addActionListener(e -> cancel());

		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				cancel();
			}
		});

	}

	private JButton createButton2() {
		JButton btn = new CustomButton("Cancel");
		btn.setBackground(ColorResource.getDarkBtnColor());
		btn.setBorderPainted(false);
		btn.setFocusPainted(false);
		btn.setForeground(Color.WHITE);
		btn.setFont(FontResource.getNormalFont());
		return btn;
	}

	private void cancel() {
		if (JOptionPane.showConfirmDialog(this, "Are you sure you want to cancel component downloads?", "Confirm",
				JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
			d.stop();
		}
	}

}
