package xdm.app.ui.screens;

import xdm.app.AppContext;
import xdm.app.utils.AppUtils;
import xdm.app.utils.PlatformUtils;
import xdm.core.downloaders.Metadata;
import xdm.core.downloaders.http.HttpMetadata;
import xdm.core.util.FileUtils;
import xdm.core.util.StringUtils;
import xdm.core.util.XDMUtils;
import xdman.ui.res.StringResource;
import xdman.util.Logger;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class NewDownloadWindow extends JDialog {
  private JTextField txtUrl;
  private JTextField txtFileName;
  private JComboBox<String> cmbSaveIn;
  private JButton btnDownload;
  private DefaultComboBoxModel<String> modelSaveIn;
  private Metadata metadata;

  public NewDownloadWindow() {
    initUI();
    attachUrlChangeListener();
  }

  private void initUI() {
    setAlwaysOnTop(true);
    setTitle(StringResource.get("ND_TITLE"));
    GridBagLayout gridBagLayout = new GridBagLayout();
    gridBagLayout.columnWidths = new int[] {0, 0, 0, 0, 0, 0, 0};
    gridBagLayout.rowHeights = new int[] {0, 0, 0, 0, 0, 0};
    gridBagLayout.columnWeights = new double[] {0.0, 0.0, 0.0, 1.0, 0.0, 0.0, Double.MIN_VALUE};
    gridBagLayout.rowWeights = new double[] {0.0, 0.0, 0.0, 1.0, 0.0, Double.MIN_VALUE};
    getContentPane().setLayout(gridBagLayout);
    getContentPane().setBackground(UIManager.getColor("Table.background"));

    JLabel lbAddress = new JLabel(StringResource.get("ND_ADDRESS"));
    lbAddress.setHorizontalAlignment(SwingConstants.RIGHT);
    GridBagConstraints gbcLbAddress = new GridBagConstraints();
    gbcLbAddress.anchor = GridBagConstraints.EAST;
    gbcLbAddress.insets = new Insets(15, 20, 5, 10);
    gbcLbAddress.gridx = 0;
    gbcLbAddress.gridy = 0;
    getContentPane().add(lbAddress, gbcLbAddress);

    txtUrl = new JTextField();
    GridBagConstraints gbcTxtUrl = new GridBagConstraints();
    gbcTxtUrl.gridwidth = 4;
    gbcTxtUrl.insets = new Insets(15, 0, 5, 5);
    gbcTxtUrl.fill = GridBagConstraints.HORIZONTAL;
    gbcTxtUrl.gridx = 1;
    gbcTxtUrl.gridy = 0;
    getContentPane().add(txtUrl, gbcTxtUrl);
    txtUrl.setColumns(30);

    JLabel lblFile = new JLabel(StringResource.get("ND_FILE"));
    lblFile.setHorizontalAlignment(SwingConstants.RIGHT);
    GridBagConstraints gbcLblFile = new GridBagConstraints();
    gbcLblFile.anchor = GridBagConstraints.EAST;
    gbcLblFile.insets = new Insets(5, 20, 5, 10);
    gbcLblFile.gridx = 0;
    gbcLblFile.gridy = 1;
    getContentPane().add(lblFile, gbcLblFile);

    txtFileName = new JTextField();
    GridBagConstraints gbcTxtFileName = new GridBagConstraints();
    gbcTxtFileName.gridwidth = 4;
    gbcTxtFileName.weightx = 1.0;
    gbcTxtFileName.insets = new Insets(5, 0, 5, 5);
    gbcTxtFileName.fill = GridBagConstraints.HORIZONTAL;
    gbcTxtFileName.gridx = 1;
    gbcTxtFileName.gridy = 1;
    getContentPane().add(txtFileName, gbcTxtFileName);
    txtFileName.setColumns(10);

    JLabel lblFileInfo = new JLabel();
    lblFileInfo.setIcon(AppUtils.createSVGIcon("file-line.svg", 48, Color.GRAY));
    lblFileInfo.setVerticalTextPosition(SwingConstants.BOTTOM);
    lblFileInfo.setHorizontalTextPosition(SwingConstants.CENTER);
    lblFileInfo.setHorizontalAlignment(SwingConstants.CENTER);
    lblFileInfo.setVerticalAlignment(SwingConstants.CENTER);
    lblFileInfo.setText("---");
    lblFileInfo.setPreferredSize(
        new Dimension(
            lblFileInfo.getPreferredSize().width + 30, lblFileInfo.getPreferredSize().height));
    GridBagConstraints gbcLblFileInfo = new GridBagConstraints();
    gbcLblFileInfo.insets = new Insets(10, 0, 5, 5);
    gbcLblFileInfo.gridheight = 3;
    gbcLblFileInfo.gridx = 5;
    gbcLblFileInfo.gridy = 0;
    getContentPane().add(lblFileInfo, gbcLblFileInfo);

    JLabel lblSaveIn = new JLabel(StringResource.get("LBL_SAVE_IN"));
    lblSaveIn.setHorizontalAlignment(SwingConstants.RIGHT);
    GridBagConstraints gbcLblSaveIn = new GridBagConstraints();
    gbcLblSaveIn.anchor = GridBagConstraints.EAST;
    gbcLblSaveIn.insets = new Insets(5, 20, 5, 10);
    gbcLblSaveIn.gridx = 0;
    gbcLblSaveIn.gridy = 2;
    getContentPane().add(lblSaveIn, gbcLblSaveIn);

    modelSaveIn = new DefaultComboBoxModel<>();
    cmbSaveIn = new JComboBox<>(modelSaveIn);
    GridBagConstraints gbcCmbSaveIn = new GridBagConstraints();
    gbcCmbSaveIn.gridwidth = 3;
    gbcCmbSaveIn.weightx = 1.0;
    gbcCmbSaveIn.insets = new Insets(5, 0, 5, 5);
    gbcCmbSaveIn.fill = GridBagConstraints.HORIZONTAL;
    gbcCmbSaveIn.gridx = 1;
    gbcCmbSaveIn.gridy = 2;
    getContentPane().add(cmbSaveIn, gbcCmbSaveIn);

    JButton btnBrowse = new JButton(AppUtils.createSVGIcon("folder-fill.svg", 16, Color.GRAY));
    GridBagConstraints gbcBtnBrowse = new GridBagConstraints();
    gbcBtnBrowse.insets = new Insets(5, 0, 5, 5);
    gbcBtnBrowse.gridx = 4;
    gbcBtnBrowse.gridy = 2;
    getContentPane().add(btnBrowse, gbcBtnBrowse);

    JLabel lblIgnore = new JLabel(StringResource.get("ND_IGNORE_URL"));
    lblIgnore.setVerticalAlignment(SwingConstants.TOP);
    GridBagConstraints gbcLblIgnore = new GridBagConstraints();
    gbcLblIgnore.weighty = 1.0;
    gbcLblIgnore.fill = GridBagConstraints.VERTICAL;
    gbcLblIgnore.anchor = GridBagConstraints.NORTHWEST;
    gbcLblIgnore.gridwidth = 4;
    gbcLblIgnore.insets = new Insets(10, 0, 5, 5);
    gbcLblIgnore.gridx = 1;
    gbcLblIgnore.gridy = 3;
    getContentPane().add(lblIgnore, gbcLblIgnore);

    JPanel panel = new JPanel();
    panel.setBorder(new EmptyBorder(10, 15, 10, 15));
    GridBagConstraints gcPanel = new GridBagConstraints();
    gcPanel.weightx = 1.0;
    gcPanel.gridwidth = 6;
    gcPanel.fill = GridBagConstraints.HORIZONTAL;
    gcPanel.gridx = 0;
    gcPanel.gridy = 4;
    getContentPane().add(panel, gcPanel);
    panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));

    JButton btnQueue = new JButton(StringResource.get("ND_QUEUE"));
    panel.add(btnQueue);

    panel.add(Box.createHorizontalGlue());
    Component rigidArea1 = Box.createRigidArea(new Dimension(80, 20));
    panel.add(rigidArea1);

    JButton btnCancel = new JButton(StringResource.get("ND_CANCEL"));
    panel.add(btnCancel);

    Component rigidArea = Box.createRigidArea(new Dimension(10, 20));
    panel.add(rigidArea);

    btnDownload = new JButton(StringResource.get("ND_DOWNLOAD"));
    btnDownload.addActionListener(e -> downloadNow());
    panel.add(btnDownload);

    getRootPane().setDefaultButton(btnDownload);

    AppUtils.sameWidth(btnDownload, btnCancel);

    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowActivated(WindowEvent e) {
            btnDownload.requestFocusInWindow();
          }
        });
  }

  private void downloadNow() {
    var url = txtUrl.getText();
    var file = txtFileName.getText();
    if (StringUtils.isNullOrEmptyOrBlank(url)) {
      JOptionPane.showMessageDialog(this, StringResource.get("MSG_NO_URL"));
      return;
    }
    if (!XDMUtils.validateURL(url)) {
      JOptionPane.showMessageDialog(this, StringResource.get("MSG_INVALID_URL"));
      return;
    }
    if (StringUtils.isNullOrEmptyOrBlank(file)) {
      JOptionPane.showMessageDialog(this, StringResource.get("MSG_NO_FILE"));
      return;
    }
    if (this.metadata == null) {
      this.metadata = HttpMetadata.builder().url(url).fileName(file).autoSelectFolder(true).build();
    }
    AppContext.INSTANCE.getDownloadsControllerService().startDownload(metadata, true, -1);
    dispose();
  }

  public void adjustSize() {
    var dim = getPreferredSize();
    dim = new Dimension(Math.max(dim.width, 500), Math.max(dim.height, 270));
    setSize(dim);
  }

  public void showWindow(final HttpMetadata metadata) {
    this.adjustSize();
    this.setLocationRelativeTo(null);
    modelSaveIn.addAll(AppContext.INSTANCE.getConfigService().getRecentFolders());
    if (AppContext.INSTANCE.getConfigService().isAutoSelectFolder()) {
      cmbSaveIn.setSelectedIndex(0);
    } else {
      cmbSaveIn.setSelectedIndex(AppContext.INSTANCE.getConfigService().getFolderIndex() + 1);
    }
    if (metadata == null) {
      var url = PlatformUtils.getClipBoardText();
      if (!StringUtils.isNullOrEmptyOrBlank(url)) {
        txtUrl.setText(url);
      }
    } else {
      this.metadata = metadata;
    }
    this.setVisible(true);
  }

  private void urlUpdated(DocumentEvent e) {
    try {
      var doc = e.getDocument();
      var len = doc.getLength();
      var text = doc.getText(0, len);
      txtFileName.setText(FileUtils.getFileName(text));
    } catch (Exception err) {
      Logger.log(err);
    }
  }

  private void attachUrlChangeListener() {
    txtUrl
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              @Override
              public void insertUpdate(DocumentEvent e) {
                urlUpdated(e);
              }

              @Override
              public void removeUpdate(DocumentEvent e) {
                urlUpdated(e);
              }

              @Override
              public void changedUpdate(DocumentEvent e) {
                urlUpdated(e);
              }
            });
  }
}
