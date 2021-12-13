using TraceLog;
using System;
using System.Collections.Generic;
using System.Data;
using System.Linq;
using System.Windows.Forms;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Common.Dash;
using XDM.Core.Lib.Common.Hls;
using XDM.Core.Lib.Common.Segmented;
using XDM.Core.Lib.Util;
using YDLWrapper;
using XDM.WinForm.UI.FormHelper;
using System.Drawing;
using Translations;

#if !(NET472_OR_GREATER || NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI.VideoDownloaderPages
{
    public partial class VideoDownloaderPage2 : UserControl
    {
        private EventHandler FormatChanged;
        public IApp App { get; set; }

        public AuthenticationInfo? Authentication { get => authentication; set => authentication = value; }
        public ProxyInfo? Proxy { get => proxy; set => proxy = value; }
        public int SpeedLimit { get => speedLimit; set => speedLimit = value; }
        public bool EnableSpeedLimit { get => enableSpeedLimit; set => enableSpeedLimit = value; }

        private AuthenticationInfo? authentication;
        private ProxyInfo? proxy = Config.Instance.Proxy;
        private int speedLimit = Config.Instance.DefaltDownloadSpeed;
        private bool enableSpeedLimit = Config.Instance.EnableSpeedLimit;
        private IFormColors colors;

        public VideoDownloaderPage2()
        {
            InitializeComponent();
            comboBox1 = AppWinPeer.AppsUseLightTheme ? new ComboBox() : new SkinnableComboBox();
            comboBox1.DropDownStyle = ComboBoxStyle.DropDownList;
            this.tableLayoutPanel3.Controls.Add(this.comboBox1, 2, 0);

            textBox1.Text = Helpers.GetDownloadFolderByFileName("video.mp4");
            FormatChanged = new EventHandler((s, e) =>
                {
                    var selectedFormatIndex = (s as ComboBox).SelectedIndex;
                    dataGridView1.SelectedRows[0].Tag = selectedFormatIndex;
                    Log.Debug("Format index: " + selectedFormatIndex + " Row index: " + dataGridView1.SelectedRows[0].Index);
                }
            );
            dataGridView1.EditingControlShowing += (s, e) =>
            {
                if (e.Control is ComboBox combo)
                {
                    // Remove an existing event-handler, if present, to avoid 
                    // adding multiple handlers when the editing control is reused.
                    combo.SelectedIndexChanged -= FormatChanged;

                    // Add the event handler. 
                    combo.SelectedIndexChanged += FormatChanged;
                }
            };
            comboBox1.SelectedValueChanged += (a, b) =>
            {
                dataGridView1.SuspendLayout();
                foreach (DataGridViewRow row in dataGridView1.Rows)
                {
                    var cell = row.Cells[2] as DataGridViewComboBoxCell;
                    var sel = comboBox1.SelectedItem?.ToString();
                    if (!string.IsNullOrEmpty(sel))
                    {
                        var s = MatchingCellValue(cell, sel);
                        if (!string.IsNullOrEmpty(s))
                        {
                            row.Cells[2].Value = s;
                        }
                    }
                }
                dataGridView1.ResumeLayout();
            };
            checkBox1.CheckedChanged += (a, b) =>
            {
                dataGridView1.SuspendLayout();
                foreach (DataGridViewRow row in dataGridView1.Rows)
                {
                    row.Cells[0].Value = checkBox1.Checked;
                }
                dataGridView1.ResumeLayout();
            };

            dataGridView1.DataError += (a, b) =>
            {
                Log.Debug(b.Exception, "Error while setting format selection");
            };

            button1.Padding = button2.Padding = button4.Padding = new Padding(
                LogicalToDeviceUnits(10),
                LogicalToDeviceUnits(5),
                LogicalToDeviceUnits(10),
                LogicalToDeviceUnits(5));

            //dataGridView1.Margin = new Padding(LogicalToDeviceUnits(5));
            textBox1.Margin = new Padding(LogicalToDeviceUnits(10));
            button3.Margin = new Padding(LogicalToDeviceUnits(5));
            button3.Padding = new Padding(0);

            dataGridView1.DefaultCellStyle.Padding = new Padding(LogicalToDeviceUnits(5));
            dataGridView1.ColumnHeadersDefaultCellStyle.Padding = new Padding(LogicalToDeviceUnits(5));

            if (!AppWinPeer.AppsUseLightTheme)
            {
                DarkModeHelper.EnableDarkMode(dataGridView1);
                colors = new FormColorsDark();
                comboBox1.FlatStyle = FlatStyle.Flat;
                var bg = colors.BackColor;
                var fg = colors.ForeColor;
                var bg2 = colors.ButtonColor;
                DarkModeHelper.EnableDarkMode(comboBox1, bg, fg);
                this.BackColor =
                    this.checkBox1.BackColor =
                    this.tableLayoutPanel1.BackColor = this.label2.BackColor = bg;
                this.checkBox1.ForeColor = this.label1.ForeColor = this.label2.ForeColor = Color.White;
                DarkModeHelper.StyleFlatButton(button1, bg2, fg);
                DarkModeHelper.StyleFlatButton(button2, bg2, fg);
                DarkModeHelper.StyleFlatButton(button3, bg2, fg);
                DarkModeHelper.StyleFlatButton(button4, bg2, fg);
                textBox1.BackColor = bg;
                textBox1.ForeColor = fg;

                MenuHelper.CustomizeMenuAppearance(contextMenuStrip1);
                MenuHelper.FixHiDpiMargin(contextMenuStrip1);
            }

            LoadTexts();
        }

        public void SetVideoResultList(List<YDLVideoEntry> items)
        {
            if (items == null) return;
            var formatSet = new HashSet<string>();
            dataGridView1.SuspendLayout();
            foreach (var item in items)
            {
                var index = dataGridView1.Rows.Add(
                    true, item, ""
                    );
                var cell = dataGridView1.Rows[index].Cells[2] as DataGridViewComboBoxCell;
                var arr = item.Formats.Select(item => item.ToString()).ToArray();
                cell.Items.AddRange(arr);
                if (arr.Length > 0)
                {
                    cell.Value = arr.Last();
                    dataGridView1.Rows[index].Tag = arr.Length - 1;
                }
                item.Formats.ForEach(item =>
                {
                    if (!string.IsNullOrEmpty(item.Height))
                    {
                        formatSet.Add(item.Height + " [" + item.FileExt?.ToUpperInvariant() + "]");
                    }
                });
            }
            dataGridView1.ResumeLayout();
            var list = new List<string>(formatSet);
            list.Sort();
            comboBox1.Items.AddRange(list.ToArray());
        }

        //private static string FormatVideoItem(YDLVideoEntry item)
        //{
        //    var textBuf = new StringBuilder();
        //    if (item.Height != null)
        //    {
        //        textBuf.Append(item.Height + "p ");
        //    }
        //    if (textBuf.Length == 0 && item.VideoFormat != null)
        //    {
        //        textBuf.Append(item.VideoFormat);
        //    }
        //    if (item.Abr != null)
        //    {
        //        textBuf.Append(item.Abr + " kbps ");
        //    }
        //    if (item.FileExt != null)
        //    {
        //        textBuf.Append(" [" + item.FileExt.ToUpperInvariant() + "]");
        //    }
        //    return textBuf.ToString();
        //}
        private static string MatchingCellValue(DataGridViewComboBoxCell cell, string text)
        {
            var arr = text.Split(' ');
            if (arr.Length < 2) return null;
            return cell.Items.Cast<string>().Where(val => val.StartsWith(arr[0]) && val.EndsWith(arr[1])).FirstOrDefault();
        }

        private void button1_Click(object sender, EventArgs e)
        {
            AddDownload(true, null);
        }

        private void AddDownload(bool startImmediately, string? queueId)
        {
            if (string.IsNullOrEmpty(textBox1.Text))
            {
                MessageBox.Show("Please select a download location");
                return;
            }
            foreach (DataGridViewRow row in dataGridView1.Rows)
            {
                var isChecked = (bool)row.Cells[0].Value;
                if (isChecked)
                {
                    Log.Debug("tag for " + row.Index + " is: " + row.Tag);
                    if (row.Tag != null)
                    {
                        var formatIndex = (int)row.Tag;
                        var format = (YDLVideoEntry)row.Cells[1].Value;
                        var selectedFormat = format.Formats[formatIndex];
                        AddDownload(selectedFormat, startImmediately, queueId);
                    }
                }
            }
        }

        private void AddDownload(YDLVideoFormatEntry videoEntry, bool startImmediately, string queueId)
        {
            IBaseDownloader downloader = null;
            switch (videoEntry.YDLEntryType)
            {
                case YDLEntryType.Http:
                    App.StartDownload(
                        new SingleSourceHTTPDownloadInfo
                        {
                            Uri = videoEntry.VideoUrl
                        },
                        videoEntry.Title + "." + videoEntry.FileExt,
                        FileNameFetchMode.None,
                        textBox1.Text,
                        startImmediately,
                        Authentication, Proxy ?? Config.Instance.Proxy,
                        EnableSpeedLimit ? SpeedLimit : 0, queueId
                    );
                    break;
                case YDLEntryType.Dash:
                    App.StartDownload(
                        new DualSourceHTTPDownloadInfo
                        {
                            Uri1 = videoEntry.VideoUrl,
                            Uri2 = videoEntry.AudioUrl
                        },
                        videoEntry.Title + "." + videoEntry.FileExt,
                        FileNameFetchMode.None,
                        textBox1.Text,
                        startImmediately,
                        Authentication, Proxy ?? Config.Instance.Proxy,
                        EnableSpeedLimit ? SpeedLimit : 0, queueId
                    );

                    //var dash = new DualSourceHTTPDownloader(
                    //    new DualSourceHTTPDownloadInfo
                    //    {
                    //        Uri1 = videoEntry.VideoUrl,
                    //        Uri2 = videoEntry.AudioUrl
                    //    },
                    //    mediaProcessor: new FFmpegMediaProcessor());
                    //dash.SetFileName(
                    //    videoEntry.Title + "." + videoEntry.FileExt, FileNameFetchMode.None);
                    //downloader = dash;
                    break;
                case YDLEntryType.Hls:
                    App.StartDownload(
                        new MultiSourceHLSDownloadInfo
                        {
                            VideoUri = videoEntry.VideoUrl,
                            AudioUri = videoEntry.AudioUrl
                        },
                        videoEntry.Title + "." + videoEntry.FileExt,
                        FileNameFetchMode.None,
                        textBox1.Text,
                        startImmediately,
                        Authentication, Proxy ?? Config.Instance.Proxy,
                        EnableSpeedLimit ? SpeedLimit : 0, queueId
                    );

                    //var hls = new MultiSourceHLSDownloader(
                    //    new MultiSourceHLSDownloadInfo
                    //    {
                    //        VideoUri = videoEntry.VideoUrl,
                    //        AudioUri = videoEntry.AudioUrl
                    //    },
                    //    mediaProcessor: new FFmpegMediaProcessor());
                    //hls.SetFileName(
                    //    videoEntry.Title + "." + videoEntry.FileExt, FileNameFetchMode.None);
                    //downloader = hls;
                    break;
                case YDLEntryType.MpegDash:
                    App.StartDownload(
                        new MultiSourceDASHDownloadInfo
                        {
                            VideoSegments = videoEntry.VideoFragments?.Select(x => new Uri(new Uri(videoEntry.FragmentBaseUrl), x.Path)).ToList(),
                            AudioSegments = videoEntry.AudioFragments?.Select(x => new Uri(new Uri(videoEntry.FragmentBaseUrl), x.Path)).ToList(),
                            AudioFormat = videoEntry.AudioFormat != null ? "." + videoEntry.AudioFormat : null,
                            VideoFormat = videoEntry.VideoFormat != null ? "." + videoEntry.VideoFormat : null,
                            Url = videoEntry.VideoUrl
                        },
                        videoEntry.Title + "." + videoEntry.FileExt,
                        FileNameFetchMode.None,
                        textBox1.Text,
                        startImmediately, Authentication, Proxy ?? Config.Instance.Proxy,
                        EnableSpeedLimit ? SpeedLimit : 0, queueId
                    );

                    //var mpeg = new MultiSourceDASHDownloader(
                    //    new MultiSourceDASHDownloadInfo
                    //    {
                    //        VideoSegments = videoEntry.VideoFragments?.Select(x => new Uri(new Uri(videoEntry.FragmentBaseUrl), x.Path)).ToList(),
                    //        AudioSegments = videoEntry.AudioFragments?.Select(x => new Uri(new Uri(videoEntry.FragmentBaseUrl), x.Path)).ToList(),
                    //        AudioFormat = videoEntry.AudioFormat != null ? "." + videoEntry.AudioFormat : null,
                    //        VideoFormat = videoEntry.VideoFormat != null ? "." + videoEntry.VideoFormat : null,
                    //        Url = videoEntry.VideoUrl
                    //    },
                    //    mediaProcessor: new FFmpegMediaProcessor());
                    //mpeg.SetFileName(
                    //    videoEntry.Title + "." + videoEntry.FileExt, FileNameFetchMode.None);
                    //downloader = mpeg;
                    break;
            }
        }

        private void button2_Click(object sender, EventArgs e)
        {
            DownloadLaterMenuHelper.PopulateMenuAndAttachEvents(
                   contextMenuStrip1,
                   (s, e) =>
                   {
                       AddDownload(false, e.QueueId);
                   },
                   doNotAddToQueueToolStripMenuItem,
                   manageQueueAndSchedulersToolStripMenuItem,
                   button2,
                   this);
            //AddDownload(false);
        }

        public string? SelectFolder()
        {
            using var fc = new FolderBrowserDialog();
            fc.SelectedPath = textBox1.Text;
            if (fc.ShowDialog(this) == DialogResult.OK)
            {
                return fc.SelectedPath;
            }
            return null;
        }

        private void button3_Click(object sender, EventArgs e)
        {
            var selected = SelectFolder();
            if (!string.IsNullOrEmpty(selected))
            {
                textBox1.Text = SelectFolder();
            }
        }

        private void button4_Click(object sender, EventArgs e)
        {
            AdvancedDialogHelper.Show(ref authentication, ref proxy, ref enableSpeedLimit, ref speedLimit, this);
        }

        private void doNotAddToQueueToolStripMenuItem_Click(object sender, EventArgs e)
        {
            AddDownload(false, null);
        }

        private void manageQueueAndSchedulersToolStripMenuItem_Click(object sender, EventArgs e)
        {
            App.AppUI.ShowQueueWindow(this.ParentForm);
        }

        private void LoadTexts()
        {
            label2.Text = TextResource.GetText("O_VID_FMT");
            checkBox1.Text = TextResource.GetText("VID_CHK");
            label1.Text = TextResource.GetText("LBL_SAVE_IN");
            button4.Text = TextResource.GetText("ND_MORE");
            button2.Text = TextResource.GetText("ND_DOWNLOAD_LATER");
            button1.Text = TextResource.GetText("ND_DOWNLOAD_NOW");

            dataGridView1.Columns["Column2"].HeaderText = TextResource.GetText("SORT_NAME");
            dataGridView1.Columns["Column4"].HeaderText = TextResource.GetText("O_VID_FMT");

            doNotAddToQueueToolStripMenuItem.Text = TextResource.GetText("LBL_QUEUE_OPT3");
            manageQueueAndSchedulersToolStripMenuItem.Text = TextResource.GetText("DESC_Q_TITLE");
        }
    }
}
