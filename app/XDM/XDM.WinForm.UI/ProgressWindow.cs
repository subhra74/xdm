using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Text;
using System.IO;

using System.Windows.Forms;
using XDM.Common.UI;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Util;
using XDM.WinForm.UI.FormHelper;

#if !(NET472_OR_GREATER || NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI
{
    public partial class ProgressWindow : Form, IProgressWindow
    {
        private PrivateFontCollection fontCollection;
        private Font fontAwesomeFont;
        private string downloadId;
        private bool cancelled, failed;
        private Action<int> actSetProgress;

        public IApp App { get; set; }
        public IAppUI AppUI { get; set; }

        private IFormColors colors;
        private ProgressBar progressBar1;
        private SpeedLimiterDialog? speedLimiterDlg;

        public string FileNameText
        {
            get => label2.Text;
            set
            {
                if (this.InvokeRequired)
                {
                    this.BeginInvoke(new Action(() => SetFileText(value)));
                }
                else
                {
                    SetFileText(value);
                }
            }
        }

        public string UrlText
        {
            get => label3.Text;
            set
            {
                if (this.InvokeRequired)
                {
                    this.BeginInvoke(new Action(() => SetFileUrl(value)));
                }
                else
                {
                    SetFileUrl(value);
                }
            }
        }

        public string FileSizeText
        {
            get => label4.Text;
            set
            {
                if (this.InvokeRequired)
                {
                    this.BeginInvoke(new Action(() => SetFileSize(value)));
                }
                else
                {
                    SetFileSize(value);
                }
            }
        }

        public string DownloadSpeedText
        {
            get => label5.Text;
            set
            {
                if (this.InvokeRequired)
                {
                    this.BeginInvoke(new Action(() => SetSpeed(value)));
                }
                else
                {
                    SetSpeed(value);
                }
            }
        }

        public string DownloadETAText
        {
            get => label6.Text;
            set
            {
                UIRunner.RunOnUiThread<string>(this, SetETA, value);
            }
        }

        public int DownloadProgress
        {
            get => progressBar1.Value;
            set
            {
                UIRunner.RunOnUiThread<int>(this, actSetProgress, value);
            }
        }

        public string DownloadId
        {
            get => this.downloadId;
            set => this.downloadId = value;
        }

        public ProgressWindow()
        {
            InitializeComponent();
            progressBar1 = AppWinPeer.AppsUseLightTheme ? new ProgressBar() : new DarkProgressBar();
            progressBar1.Dock = DockStyle.Fill;
            this.tableLayoutPanel1.SetColumnSpan(this.progressBar1, 3);
            this.tableLayoutPanel1.Controls.Add(this.progressBar1, 1, 3);
            this.progressBar1.Margin = new Padding(LogicalToDeviceUnits(3), LogicalToDeviceUnits(10),
                LogicalToDeviceUnits(3), LogicalToDeviceUnits(10));
            this.progressBar1.Size = new Size(LogicalToDeviceUnits(351), LogicalToDeviceUnits(5));
            fontCollection = new PrivateFontCollection();
            //fontCollection.AddFontFile("fontawesome-webfont.ttf");

            fontCollection.AddFontFile(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, @"FontAwesome\remixicon.ttf"));
            //fontCollection.AddFontFile(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "FontAwesome", "fa-solid-900.ttf"));
            fontAwesomeFont = new Font(fontCollection.Families[0], 32);

            lblMainIcon.Font = fontAwesomeFont;
            lblMainIcon.Text = ((char)Int32.Parse("ecd9"/*"ec28"*//*"eb99"*/, System.Globalization.NumberStyles.HexNumber)).ToString();

            FormClosing += Win32ProgressWindow_FormClosing;

            actSetProgress = new Action<int>(SetProgress);

            if (!AppWinPeer.AppsUseLightTheme)
            {
                colors = new FormColorsDark();
                if (!this.IsHandleCreated)
                {
                    this.CreateHandle();
                }

                DarkModeHelper.UseImmersiveDarkMode(this.Handle, true);
                this.lblMainIcon.ForeColor = colors.SearchButtonColor;
                this.lblMainIcon.BackColor = colors.ToolbarBackColor;
                label2.ForeColor = label3.ForeColor = label4.ForeColor =
                    label5.ForeColor = label6.ForeColor = linkLabel1.ForeColor = colors.ToolbarButtonForeColor;
                label2.BackColor = label3.BackColor = label4.BackColor =
                    label5.BackColor = label6.BackColor = linkLabel1.BackColor = colors.ToolbarBackColor;
                panel1.BackColor = tableLayoutPanel1.BackColor = colors.ToolbarBackColor;
                tableLayoutPanel2.BackColor = colors.DataGridViewBackColor;
                DarkModeHelper.StyleFlatButton(button1, colors);
                DarkModeHelper.StyleFlatButton(button2, colors);
                DarkModeHelper.StyleFlatButton(button3, colors);
                progressBar1.BackColor = colors.DataGridViewBackColor;
                progressBar1.ForeColor = Color.DodgerBlue;
            }
        }

        private void Win32ProgressWindow_FormClosing(object? sender, FormClosingEventArgs e)
        {
            StopDownload(true);
            speedLimiterDlg?.Close();
            speedLimiterDlg = null;
        }

        private void button2_Click(object sender, EventArgs e)
        {
            if (button2.Tag != null)
            {
                AppUI.ResumeDownload(downloadId);
                button2.Text = "Pause";
                button2.Tag = null;
            }
            else
            {
                StopDownload(false);
                button2.Text = "Resume";
                button2.Tag = new();
            }
        }

        private void button3_Click(object sender, EventArgs e)
        {
            StopDownload(true);
        }

        private void button1_Click(object sender, EventArgs e)
        {
            //contextMenuStrip1.Show(button1, new Point(button1.Left, button1.Height));
            FormClosing -= Win32ProgressWindow_FormClosing;
            App.HideProgressWindow(downloadId);
            Dispose();
        }

        private void StopDownload(bool close)
        {
            App?.StopDownloads(new List<string> { downloadId }, close);
        }

        public void Destroy()
        {
            if (this.InvokeRequired)
            {
                this.BeginInvoke(new Action(() => this.Dispose()));
            }
            else
            {
                this.Dispose();
            }
        }

        public void ShowProgressWindow()
        {
            if (this.InvokeRequired)
            {
                this.BeginInvoke(new Action(() => this.Show()));
            }
            else
            {
                this.Show();
            }
        }

        private void SetProgress(int value)
        {
            progressBar1.Value = value >= 0 && value <= 100 ? value : 0;
            var prg = value >= 0 && value <= 100 ? value + "% " : "";
            this.Text = $"{prg}{FileNameText}";
        }

        private void SetFileText(string value)
        {
            label2.Text = value;
            var prg = progressBar1.Value >= 0 && progressBar1.Value <= 100 ? progressBar1.Value + "% " : "";
            this.Text = $"{prg}{value}";
        }

        private void SetFileUrl(string value)
        {
            label3.Text = value;
        }

        private void SetFileSize(string value)
        {
            label4.Text = value;
        }

        private void SetETA(string value)
        {
            label6.Text = value;
        }

        private void SetSpeed(string value)
        {
            label5.Text = value;
        }

        private void linkLabel1_LinkClicked(object sender, LinkLabelLinkClickedEventArgs e)
        {
            if (speedLimiterDlg == null)
            {
                speedLimiterDlg = new SpeedLimiterDialog();
                if (App.GetLiveDownloadSpeedLimit(downloadId, out bool enable, out int limit))
                {
                    speedLimiterDlg.EnableSpeedLimit = enable;
                    speedLimiterDlg.SpeedLimit = limit;
                }
                speedLimiterDlg.FormClosed += (a, b) =>
                {
                    speedLimiterDlg = null;
                };
                speedLimiterDlg.OkClicked += (a, b) =>
                {
                    var limit2 = speedLimiterDlg.SpeedLimit;
                    App.UpdateSpeedLimit(DownloadId, speedLimiterDlg.EnableSpeedLimit, limit2);
                    SetSpeedLimitText(speedLimiterDlg.EnableSpeedLimit, limit2);
                };
            }

            if (!speedLimiterDlg.Visible)
            {
                speedLimiterDlg.Show(this);
            }
            else
            {
                speedLimiterDlg.BringToFront();
            }
        }

        public void DownloadFailed(ErrorDetails error)
        {
            UIRunner.RunOnUiThread(this,
                new Action<ErrorDetails>(error =>
                {
                    label4.Text = error.Message;
                    button2.Text = "Resume";
                    label6.Text = string.Empty;
                    button2.Tag = new();
                    linkLabel1.Visible = false;
                    speedLimiterDlg?.Close();
                    speedLimiterDlg = null;
                }), error);
        }

        public void DownloadCancelled()
        {
            UIRunner.RunOnUiThread(this,
                new Action(() =>
                {
                    label4.Text = "Download cancelled";
                    label6.Text = string.Empty;
                    button2.Text = "Resume";
                    button2.Tag = new();
                    linkLabel1.Visible = false;
                    speedLimiterDlg?.Close();
                    speedLimiterDlg = null;
                }));
        }

        public void DownloadStarted()
        {
            UIRunner.RunOnUiThread(this, () =>
             {
                 button2.Text = "Pause";
                 button2.Tag = null;
                 linkLabel1.Visible = true;

                 if(App.GetLiveDownloadSpeedLimit(downloadId, out bool enable, out int limit))
                 {
                     SetSpeedLimitText(enable, limit);
                 }
             });
        }

        private void SetSpeedLimitText(bool enable, int limit)
        {
            if (enable && limit > 0)
            {
                linkLabel1.Text = $"Speed Limit - {limit}K/S";
            }
            else
            {
                linkLabel1.Text = "No speed limit";
            }
        }
    }
}
