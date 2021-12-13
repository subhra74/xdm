using System;
using System.Drawing;
using System.Drawing.Text;
using System.IO;
using System.Threading;
using System.Windows.Forms;
using TraceLog;
using Translations;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;
using XDM.WinForm.UI.FormHelper;
using XDM.WinForm.UI.Win32;

#if !(NET472_OR_GREATER || NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI
{
    public partial class NewDownloadDialogView : Form, INewDownloadDialogSkeleton
    {
        private PrivateFontCollection fontCollection;
        private Font fontAwesomeFont;
        private QueueSelectionDialog dlg;
        private int previousIndex = 0;
        private bool empty;

        public event EventHandler<DownloadLaterEventArgs> DownloadLaterClicked;
        public event EventHandler DownloadClicked;
        public event EventHandler CancelClicked;
        public event EventHandler DestroyEvent;
        public event EventHandler BlockHostEvent;
        public event EventHandler UrlChangedEvent;
        public event EventHandler UrlBlockedEvent;
        public event EventHandler QueueSchedulerClicked;
        public event EventHandler<FileBrowsedEventArgs> DropdownSelectionChangedEvent;
        public event EventHandler<FileBrowsedEventArgs> FileBrowsedEvent;

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

        public NewDownloadDialogView(bool empty)
        {
            Log.Debug("Thread name: " + Thread.CurrentThread.Name);
            InitializeComponent();
            this.TopMost = true;
            comboBox1 = AppWinPeer.AppsUseLightTheme ? new ComboBox() : new SkinnableComboBox();
            this.tableLayoutPanel2.SetColumnSpan(this.comboBox1, 2);
            comboBox1.SelectedIndexChanged += comboBox1_SelectedIndexChanged;
            comboBox1.DropDownStyle = ComboBoxStyle.DropDownList;
            comboBox1.Dock = DockStyle.Fill;
            this.tableLayoutPanel2.Controls.Add(this.comboBox1, 1, 2);

            this.empty = empty;
            fontCollection = new PrivateFontCollection();
            fontCollection.AddFontFile(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, @"FontAwesome\remixicon.ttf"));
            fontAwesomeFont = new Font(fontCollection.Families[0], 32);

            label5.Font = fontAwesomeFont;
            label5.Text = ((char)Int32.Parse("ecd9"/*"ec28"*//*"eb99"*/, System.Globalization.NumberStyles.HexNumber)).ToString();

            if (this.empty)
            {
                textBox1.ReadOnly = true;
            }

            textBox1.ReadOnly = !empty;

            if (!AppWinPeer.AppsUseLightTheme)
            {
                colors = new FormColorsDark();
                if (!this.IsHandleCreated)
                {
                    this.CreateHandle();
                }

                DarkModeHelper.UseImmersiveDarkMode(this.Handle, true);
                tableLayoutPanel2.BackColor = colors.ToolbarBackColor;
                tableLayoutPanel1.BackColor = colors.DataGridViewBackColor;
                DarkModeHelper.EnabledDarkMode(comboBox1);
                comboBox1.BackColor = colors.TextBackColor;
                comboBox1.ForeColor = colors.ToolbarButtonForeColor;
                ((SkinnableComboBox)comboBox1).BorderColor = colors.ToolbarBackColor;
                ((SkinnableComboBox)comboBox1).ButtonColor = colors.BorderColor;

                DarkModeHelper.StyleFlatTextBox(textBox1, colors);
                DarkModeHelper.StyleFlatTextBox(textBox2, colors);

                DarkModeHelper.StyleFlatButton(button1, colors);
                DarkModeHelper.StyleFlatButton(button2, colors);
                DarkModeHelper.StyleFlatButton(button4, colors);

                label1.ForeColor = label2.ForeColor =
                    label3.ForeColor = label6.ForeColor =
                     colors.ToolbarButtonForeColor;

                label5.ForeColor = Color.FromArgb(50, 50, 50);

                //panel1.BackColor = colors.TextBackColor;
                //panel2.BackColor = colors.TextBackColor;
                //panel1.Padding = panel2.Padding = new Padding(LogicalToDeviceUnits(3));
                //panel1.Margin = panel2.Margin = new Padding(LogicalToDeviceUnits(3));
                //textBox1.Location = new Point(LogicalToDeviceUnits(5), LogicalToDeviceUnits(5));
                //textBox2.Location = new Point(LogicalToDeviceUnits(5), LogicalToDeviceUnits(5));
                linkLabel1.LinkColor = Color.DimGray;
                //panel1.Padding = panel2.Padding = new Padding(LogicalToDeviceUnits(0));
                //panel1.Margin = panel2.Margin = new Padding(LogicalToDeviceUnits(0));

                MenuHelper.CustomizeMenuAppearance(contextMenuStrip1);
                MenuHelper.FixHiDpiMargin(contextMenuStrip1);
            }
#if !NET35
            linkLabel1.Margin = new Padding(5);
#endif

            this.FormClosed += (a, b) =>
            {
                this.DestroyEvent?.Invoke(this, EventArgs.Empty);
            };

            LoadTexts();
        }

        public void DisposeWindow()
        {
            this.Close();
        }

        //private string? SelectFile()
        //{
        //    using var fc = new SaveFileDialog();
        //    fc.FileName = textBox2.Text;
        //    if (fc.ShowDialog(this) == DialogResult.OK)
        //    {
        //        return fc.FileName;
        //    }
        //    return null;
        //}

        public void SetFileSizeText(string text)
        {
            this.label6.Text = text;
        }

        public string Url { get => textBox1.Text; set => textBox1.Text = value; }
        public string SelectedFileName { get => textBox2.Text; set => textBox2.Text = value; }


        //public FolderSelectionMode FolderSelectionMode
        //{
        //    get
        //    {
        //        return comboBox1.SelectedIndex == 0 ? FolderSelectionMode.Auto : FolderSelectionMode.Manual;
        //    }
        //    set
        //    {
        //        //comboBox1.Items.Clear();
        //        //comboBox1.Items.AddRange(new string[] { AutoSelectText, BrowseText });
        //        //comboBox1.Items.AddRange(Config.Instance.RecentFolders.ToArray());
        //        //comboBox1.Items.Add(Config.Instance.DefaultDownloadFolder);

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
        //public FileConflictResolution ConflictResolution
        //{
        //    get => /*comboBox2.SelectedIndex == 0 ?*/ FileConflictResolution.AutoRename /*: FileConflictResolution.Overwrite*/;
        //    set => /*comboBox2.SelectedIndex = value == FileConflictResolution.AutoRename ?*/ value = 0 /*: 1*/;
        //}

        //[STAThread]
        public void ShowWindow()
        {
            this.Show();
            //var f = new Form();
            //f.TopMost = true;
            //f.Show();
            //Log.Debug("Thread: " + Thread.CurrentThread.Name);
            //if (!IsHandleCreated)
            //{
            //    CreateHandle();
            //}
            //NativeMethods.SetWindowTopMost(this);
            //NativeMethods.SetForegroundWindow(this.Handle);
            //this.Shown += (_, _) =>
            //{
            //    TopLevel = true;
            //    TopMost = true;
            //    NativeMethods.SetForegroundWindow(this.Handle);
            //    NativeMethods.SetWindowTopMost(this);
            //    NativeMethods.SetForegroundWindow(this.Handle);
            //};
            //TopMost = true;
            //Application.DoEvents();
            //this.BringToFront();
            //Log.Debug("Thread name: " + Thread.CurrentThread.Name);
            //this.Visible = true;
            //this.Activate();
            //Application.Run(this);
        }

        void INewDownloadDialogSkeleton.Invoke(Action callback)
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

        //private void textBox1_TextChanged(object sender, EventArgs e)
        //{
        //    if (empty)
        //    {
        //        UrlChangedEvent?.Invoke(sender, e);
        //    }
        //}

        private void textBox1_TextChanged(object sender, EventArgs e)
        {
            if (empty)
            {
                UrlChangedEvent?.Invoke(sender, e);
            }
        }

        private void comboBox1_SelectedIndexChanged(object sender, EventArgs e)
        {
            if (comboBox1.SelectedIndex == 1)
            {
                comboBox1.SelectedIndex = previousIndex;
                using var fc = new SaveFileDialog();
                fc.Filter = "All files (*.*)|*.*";
                fc.FileName = textBox2.Text;
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

        //private void comboBox1_SelectedIndexChanged(object sender, EventArgs e)
        //{
        //    if (comboBox1.SelectedIndex == 1)
        //    {
        //        comboBox1.SelectedIndex = previousIndex;
        //        using var fc = new SaveFileDialog();
        //        fc.Filter = "All files (*.*)|*.*";
        //        fc.FileName = textBox2.Text;
        //        if (fc.ShowDialog(this) == DialogResult.OK)
        //        {
        //            this.FileBrowsedEvent?.Invoke(this, new FileBrowsedEventArgs(fc.FileName));
        //        }
        //    }
        //    else
        //    {
        //        previousIndex = comboBox1.SelectedIndex;
        //        this.DropdownSelectionChangedEvent?.Invoke(this, new FileBrowsedEventArgs(comboBox1.Text));
        //    }
        //}

        private void button1_Click(object sender, EventArgs e)
        {
            DownloadClicked?.Invoke(sender, e);
        }

        private void button2_Click(object sender, EventArgs e)
        {
            DownloadLaterMenuHelper.PopulateMenuAndAttachEvents(
                contextMenuStrip1,
                this.DownloadLaterClicked,
                doNotAddToQueueToolStripMenuItem,
                manageQueueAndSchedulersToolStripMenuItem,
                button2,
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
            //contextMenuStrip1.Items.Add(manageQueueAndSchedulersToolStripMenuItem);
            //contextMenuStrip1.Show(button2, new Point(0, button2.Height));
            ////DownloadLaterClicked?.Invoke(sender, e);
        }

        private void button4_Click(object sender, EventArgs e)
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

        private void checkBox1_CheckedChanged(object sender, EventArgs e)
        {
            BlockHostEvent?.Invoke(sender, e);
        }

        private void linkLabel1_LinkClicked(object sender, LinkLabelLinkClickedEventArgs e)
        {
            UrlBlockedEvent?.Invoke(sender, EventArgs.Empty);
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

        private void manageQueueAndSchedulersToolStripMenuItem_Click(object sender, EventArgs e)
        {
            this.QueueSchedulerClicked?.Invoke(this, EventArgs.Empty);
        }

        private void LoadTexts()
        {
            label1.Text = TextResource.GetText("ND_ADDRESS");
            label2.Text = TextResource.GetText("ND_FILE");
            label3.Text = TextResource.GetText("LBL_SAVE_IN");
            linkLabel1.Text = TextResource.GetText("ND_IGNORE_URL");
            button4.Text = TextResource.GetText("ND_MORE");
            button2.Text = TextResource.GetText("ND_DOWNLOAD_LATER");
            button1.Text = TextResource.GetText("ND_DOWNLOAD_NOW");
            Text = TextResource.GetText("ND_TITLE");
            doNotAddToQueueToolStripMenuItem.Text= TextResource.GetText("LBL_QUEUE_OPT3");
            manageQueueAndSchedulersToolStripMenuItem.Text= TextResource.GetText("DESC_Q_TITLE");
        }

        public void ShowMessageBox(string text)
        {
            MessageBox.Show(this, text);
        }
    }
}
