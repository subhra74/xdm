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

import java.awt.AWTException;
import java.awt.CheckboxMenuItem;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.JOptionPane;

import org.tinylog.Logger;

import xdman.Config;
import xdman.MonitoringListener;
import xdman.XDMApp;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.XDMUtils;

public class TrayHandler {
	static ActionListener act;

	public static void createTray() {
		if (!SystemTray.isSupported()) {
			Logger.warn("SystemTray is not supported");
			return;
		}

		Image img = null;

		if (XDMUtils.detectOS() == XDMUtils.LINUX) {
			if (Config.getInstance().isHideTray())
				return;
			else {
				img = ImageResource.getImage("icon_linux.png");
			}
		} else {
			img = ImageResource.getImage("icon.png");
		}

		final PopupMenu popup = new PopupMenu();
		final TrayIcon trayIcon = new TrayIcon(img);
		trayIcon.setImageAutoSize(true);
		final SystemTray tray = SystemTray.getSystemTray();

		act = new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				MenuItem c = (MenuItem) e.getSource();
				String name = c.getName();
				if ("ADD_URL".equals(name)) {
					XDMApp.getInstance().addDownload(null, null);
				} else if ("RESTORE".equals(name)) {
					XDMApp.getInstance().showMainWindow();
				} else if ("EXIT".equals(name)) {
					XDMApp.getInstance().exit();
				} else if ("THROTTLE".equals(name)) {
					int ret = SpeedLimiter.getSpeedLimit();
					if (ret >= 0) {
						Config.getInstance().setSpeedLimit(ret);
					}
				} else if ("ADD_VID".equals(name)) {
					MediaDownloaderWnd wnd = new MediaDownloaderWnd();
					wnd.setVisible(true);
				} else if ("THROTTLE".equals(name)) {
					int ret = SpeedLimiter.getSpeedLimit();
					if (ret >= 0) {
						Config.getInstance().setSpeedLimit(ret);
					}
				} else if ("ADD_BAT".equals(name)) {
					new BatchPatternDialog().setVisible(true);
				} else if ("ADD_CLIP".equals(name)) {
					List<String> urlList = BatchDownloadWnd.getUrls();
					if (urlList.size() > 0) {
						new BatchDownloadWnd(XDMUtils.toMetadata(urlList)).setVisible(true);
					} else {
						JOptionPane.showMessageDialog(null, StringResource.get("LBL_BATCH_EMPTY_CLIPBOARD"));
					}
				}
			}
		};

		MenuItem addUrlItem = new MenuItem(StringResource.get("MENU_ADD_URL"));
		addUrlItem.setFont(FontResource.getBigFont());
		addUrlItem.addActionListener(act);
		addUrlItem.setName("ADD_URL");
		MenuItem addVidItem = new MenuItem(StringResource.get("MENU_VIDEO_DWN"));
		addVidItem.setFont(FontResource.getBigFont());
		addVidItem.addActionListener(act);
		addVidItem.setName("ADD_VID");
		MenuItem addBatchItem = new MenuItem(StringResource.get("MENU_BATCH_DOWNLOAD"));
		addBatchItem.setFont(FontResource.getBigFont());
		addBatchItem.addActionListener(act);
		addBatchItem.setName("ADD_BAT");
		MenuItem addClipItem = new MenuItem(StringResource.get("MENU_CLIP_ADD_MENU"));
		addClipItem.setFont(FontResource.getBigFont());
		addClipItem.addActionListener(act);
		addClipItem.setName("ADD_CLIP");
		MenuItem restoreItem = new MenuItem(StringResource.get("MSG_RESTORE"));
		restoreItem.setFont(FontResource.getBigFont());
		restoreItem.addActionListener(act);
		restoreItem.setName("RESTORE");
		CheckboxMenuItem monitoringItem = new CheckboxMenuItem(StringResource.get("BROWSER_MONITORING"));
		monitoringItem.addItemListener(new ItemListener() {

			@Override
			public void itemStateChanged(ItemEvent e) {
				Logger.info("monitoring change");
				Config.getInstance().enableMonitoring(!Config.getInstance().isBrowserMonitoringEnabled());

			}
		});
		monitoringItem.setFont(FontResource.getBigFont());
		monitoringItem.setState(Config.getInstance().isBrowserMonitoringEnabled());
		monitoringItem.addActionListener(act);
		monitoringItem.setName("MONITORING");
		MenuItem throttleItem = new MenuItem(StringResource.get("MENU_SPEED_LIMITER"));
		throttleItem.setFont(FontResource.getBigFont());
		throttleItem.addActionListener(act);
		throttleItem.setName("THROTTLE");
		MenuItem exitItem = new MenuItem(StringResource.get("MENU_EXIT"));
		exitItem.setFont(FontResource.getBigFont());
		exitItem.addActionListener(act);
		exitItem.setName("EXIT");

		popup.add(addUrlItem);
		popup.add(addVidItem);
		popup.add(addBatchItem);
		popup.add(addClipItem);
		popup.add(monitoringItem);
		popup.add(restoreItem);
		popup.add(throttleItem);
		popup.add(exitItem);
		trayIcon.setToolTip(XDMApp.XDM_WINDOW_TITLE);
		trayIcon.setPopupMenu(popup);

		trayIcon.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getButton() == MouseEvent.BUTTON1 || e.getClickCount() == 2) {
					XDMApp.getInstance().showMainWindow();
				}
			}
		});

		try {
			tray.add(trayIcon);
		} catch (AWTException e) {
			Logger.warn("TrayIcon could not be added.");
			Logger.error(e);
		}

		Config.getInstance().addConfigListener(new MonitoringListener() {

			@Override
			public void configChanged() {
				monitoringItem.setState(Config.getInstance().isBrowserMonitoringEnabled());
			}
		});

	}
}
