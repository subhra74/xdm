using System;
using System.Drawing;
using System.Drawing.Text;
using System.Windows.Forms;
using Translations;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;
using XDM.WinForm.UI.FormHelper;

#if !(NET472_OR_GREATER || NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif
namespace XDM.WinForm.UI
{
    public partial class NewVideoDownloadDialogView : Form, INewVideoDownloadDialog
    {
        private PrivateFontCollection fontCollection;
        private Font fontAwesomeFont;
        private int previousIndex = 0;

        public AuthenticationInfo? Authentication { get => authentication; set => authentication = value; }
        public ProxyInfo? Proxy { get => proxy; set => proxy = value; }
        public int SpeedLimit { get => speedLimit; set => speedLimit = value; }
        public bool EnableSpeedLimit { get => enableSpeedLimit; set => enableSpeedLimit = value; }

        private AuthenticationInfo? authentication;
        private ProxyInfo? proxy = Config.Instance.Proxy;
        private int speedLimit = Config.Instance.DefaltDownloadSpeed;
        private bool enableSpeedLimit = Config.Instance.EnableSpeedLimit;

        private IFormColors colors;
        private ComboBox comboBox1;

        public DownloadSchedule? DownloadSchedule { get; set; }

        public NewVideoDownloadDialogView(PrivateFontCollection fc)
        {
            InitializeComponent();

            comboBox1 = new ComboBox(); /*AppWinPeer.AppsUseLightTheme ? new ComboBox() : new SkinnableComboBox();*/
            this.tableLayoutPanel2.SetColumnSpan(this.comboBox1, 2);
            comboBox1.SelectedIndexChanged += comboBox1_SelectedIndexChanged;
            comboBox1.DropDownStyle = ComboBoxStyle.DropDownList;
            comboBox1.Dock = DockStyle.Fill;
            this.tableLayoutPanel2.Controls.Add(this.comboBox1, 1, 1);

            txtFileName.Margin = new Padding(5);
            comboBox1.Margin = new Padding(5);

            fontCollection = fc;
            fontAwesomeFont = new Font(fontCollection.Families[0], 32);
            lblFileIcon.Font = fontAwesomeFont;
            lblFileIcon.Text = ((char)Int32.Parse("ecd9"/*"ec28"*//*"eb99"*/,
                System.Globalization.NumberStyles.HexNumber)).ToString();

            if (!AppWinPeer.AppsUseLightTheme)
            {
                colors = new FormColorsDark();
                if (!this.IsHandleCreated)
                {
                    this.CreateHandle();
                }

                DarkModeHelper.UseImmersiveDarkMode(this.Handle, true);
                tableLayoutPanel2.BackColor = colors.BackColor;
                tableLayoutPanel1.BackColor = colors.ButtonColor;
                DarkModeHelper.EnabledDarkMode(comboBox1, colors.TextBackColor, colors.TextForeColor);
                //comboBox1.BackColor = colors.TextBackColor;
                //comboBox1.ForeColor = colors.ToolbarButtonForeColor;
                //((SkinnableComboBox)comboBox1).BorderColor = colors.ToolbarBackColor;
                //((SkinnableComboBox)comboBox1).ButtonColor = colors.BorderColor;
                DarkModeHelper.StyleFlatTextBox(txtFileName, colors);
                //DarkModeHelper.StyleFlatTextBox(textBox2);

                DarkModeHelper.StyleFlatButton(btnCancel, colors);
                DarkModeHelper.StyleFlatButton(btnDownload, colors);
                DarkModeHelper.StyleFlatButton(btnLater, colors);

                lblAddress.ForeColor = lblFile.ForeColor =
                    lblFileIcon.ForeColor = lblFileSize.ForeColor =
                     colors.TextForeColor;

               // label5.ForeColor = Color.FromArgb(50, 50, 50);
            }
            LoadTexts();
        }

        public event EventHandler DownloadClicked;
        public event EventHandler<DownloadLaterEventArgs> DownloadLaterClicked;
        public event EventHandler CancelClicked, DestroyEvent, QueueSchedulerClicked;
        public event EventHandler<FileBrowsedEventArgs> DropdownSelectionChangedEvent;
        public event EventHandler<FileBrowsedEventArgs> FileBrowsedEvent;

        public string SelectedFileName { get => txtFileName.Text; set => txtFileName.Text = value; }

        public string FileSize { get => lblFileSize.Text; set => lblFileSize.Text = value; }

        //public FolderSelectionMode FolderSelectionMode
        //{
        //    get
        //    {
        //        return comboBox1.SelectedIndex == 0 ? FolderSelectionMode.Auto : FolderSelectionMode.Manual;
        //    }
        //    set
        //    {
        //        comboBox1.Items.Clear();
        //        comboBox1.Items.AddRange(new string[] { AutoSelectText, BrowseText, Config.Instance.DefaultDownloadFolder });
        //        comboBox1.Items.AddRange(Config.Instance.RecentFolders.ToArray());

        //        if (value == FolderSelectionMode.Auto)
        //        {
        //            comboBox1.SelectedIndex = 0;
        //        }
        //        else
        //        {
        //            comboBox1.SelectedIndex = 2;
        //        }
        //    }
        //}

        private void comboBox1_SelectedIndexChanged(object sender, EventArgs e)
        {
            if (comboBox1.SelectedIndex == 1)
            {
                comboBox1.SelectedIndex = previousIndex;
                using var fc = new SaveFileDialog();
                fc.Filter = "All files (*.*)|*.*";
                fc.FileName = txtFileName.Text;
                if (fc.ShowDialog(this) == DialogResult.OK)
                {
                    this.FileBrowsedEvent?.Invoke(this, new FileBrowsedEventArgs(fc.FileName));
                }
            }
            else
            {
                previousIndex = comboBox1.SelectedIndex;
                this.DropdownSelectionChangedEvent?.Invoke(this, new FileBrowsedEventArgs(comboBox1.Text));
            }
        }

        public void DisposeWindow()
        {
            this.Dispose();
        }

        public void Invoke(Action callback)
        {
            if (this.InvokeRequired)
            {
                this.BeginInvoke(callback);
            }
            else
            {
                callback();
            }
        }

        //public string SelectFile()
        //{
        //    using var fc = new SaveFileDialog();
        //    fc.FileName = txtFileName.Text;
        //    if (fc.ShowDialog(this) == DialogResult.OK)
        //    {
        //        return fc.FileName;
        //    }
        //    else
        //    {
        //        comboBox1.SelectedIndex = previousIndex;
        //    }
        //    return null;
        //}

        //[STAThread]
        public void ShowWindow()
        {
            this.Show();
            //var t = new Thread(() =>
            //{
            //    this.Load += (_, _) =>
            //    {
            //        this.TopMost = true;
            //    };
            //    Application.Run(this);
            //});
            //t.SetApartmentState(ApartmentState.STA);
            //t.Start();
        }

        private void btnDownload_Click(object sender, EventArgs e)
        {
            DownloadClicked?.Invoke(sender, e);
        }

        private void btnDownloadLater_Click(object sender, EventArgs e)
        {
            DownloadLaterMenuHelper.PopulateMenuAndAttachEvents(
                contextMenuStrip1,
                this.DownloadLaterClicked,
                doNotAddToQueueToolStripMenuItem,
                manageQueueAndSchedulerToolStripMenuItem,
                btnLater,
                this);

            //contextMenuStrip1.Items.Clear();
            //foreach (var queue in QueueManager.Queues)
            //{
            //    var menuItem = new ToolStripMenuItem
            //    {
            //        Name = queue.ID,
            //        Text = queue.Name
            //    };
            //    menuItem.Click += (s, _) =>
            //    {
            //        ToolStripMenuItem m = (ToolStripMenuItem)s;
            //        var args = new DownloadLaterEventArgs(m.Name);
            //        this.DownloadLaterClicked?.Invoke(this, args);
            //    };
            //    contextMenuStrip1.Items.Add(menuItem);
            //}
            //contextMenuStrip1.Items.Add(new ToolStripSeparator());
            //contextMenuStrip1.Items.Add(doNotAddToQueueToolStripMenuItem);
            //contextMenuStrip1.Items.Add(manageQueueAndSchedulerToolStripMenuItem);
            //contextMenuStrip1.Show(btnLater, new Point(0, btnLater.Height));
            ////DownloadLaterClicked?.Invoke(sender, e);
        }

        private void btnCancel_Click(object sender, EventArgs e)
        {
            AdvancedDialogHelper.Show(ref authentication, ref proxy, ref enableSpeedLimit, ref speedLimit, this);

            //using var dlg = new AdvancedDownloadDialog();
            //dlg.Authentication = Authentication;
            //dlg.Proxy = Proxy;
            //dlg.EnableSpeedLimit = EnableSpeedLimit;
            //dlg.SpeedLimit = SpeedLimit;
            //if (dlg.ShowDialog(this) == DialogResult.OK)
            //{
            //    Authentication = dlg.Authentication;
            //    Proxy = dlg.Proxy;
            //    EnableSpeedLimit = dlg.EnableSpeedLimit;
            //    SpeedLimit = dlg.SpeedLimit;
            //}
        }

        public void SetFolderValues(string[] values)
        {
            previousIndex = 0;
            comboBox1.Items.Clear();
            comboBox1.Items.AddRange(values);
        }

        public int SeletedFolderIndex
        {
            get => comboBox1.SelectedIndex;
            set
            {
                comboBox1.SelectedIndex = value;
                previousIndex = value;
            }
        }

        private void doNotAddToQueueToolStripMenuItem_Click(object sender, EventArgs e)
        {
            this.DownloadLaterClicked?.Invoke(this, new DownloadLaterEventArgs(string.Empty));
        }

        private void manageQueueAndSchedulerToolStripMenuItem_Click(object sender, EventArgs e)
        {
            this.QueueSchedulerClicked?.Invoke(this, EventArgs.Empty);
        }

        public void ShowMessageBox(string text)
        {
            MessageBox.Show(this, text);
        }

        private void LoadTexts()
        {
            lblAddress.Text = TextResource.GetText("LBL_NEW_QUEUE");
            lblFile.Text = TextResource.GetText("LBL_SAVE_IN");
            btnCancel.Text = TextResource.GetText("ND_MORE");
            btnLater.Text = TextResource.GetText("ND_DOWNLOAD_LATER");
            btnDownload.Text = TextResource.GetText("ND_DOWNLOAD_NOW");
            Text = TextResource.GetText("ND_TITLE");
            doNotAddToQueueToolStripMenuItem.Text = TextResource.GetText("LBL_QUEUE_OPT3");
            manageQueueAndSchedulerToolStripMenuItem.Text = TextResource.GetText("DESC_Q_TITLE");
        }
    }
}
