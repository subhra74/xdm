using TraceLog;
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Drawing;
using System.Windows.Forms;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Util;
using XDM.WinForm.UI.VideoDownloaderPages;
using YDLWrapper;
using System.Threading;
using XDM.WinForm.UI.FormHelper;
using Translations;

namespace XDM.WinForm.UI
{
    public partial class VideoDownloaderWindow : Form
    {
        private VideoDownloaderPage1 page1;
        private VideoDownloaderPage2 page2;
        private VideoDownloaderPage3 page3;
        private YDLProcess ydl;
        private IFormColors colors;

        public VideoDownloaderWindow(Font searchFont, IApp app, IAppUI appUi)
        {
            InitializeComponent();
            page1 = new VideoDownloaderPage1(searchFont, appUi);
            page1.Dock = DockStyle.Fill;
            page2 = new VideoDownloaderPage2();
            page2.Dock = DockStyle.Fill;
            page2.App = app;
            page3 = new VideoDownloaderPage3();
            page3.Dock = DockStyle.Fill;
            this.Controls.Add(page1);
            this.Controls.Add(page2);
            this.Controls.Add(page3);
            page1.BringToFront();

            page1.SearchClicked += (a, b) =>
            {
                if (Helpers.IsUriValid(page1.UrlText))
                {
                    page3.BringToFront();
                    ProcessVideo(page1.UrlText, result => UIRunner.RunOnUiThread(this, () =>
                        {
                            page2.BringToFront();
                            page2.SetVideoResultList(result);
                        }));
                }
                else
                {
                    MessageBox.Show(TextResource.GetText("MSG_INVALID_URL"));
                }
            };

            page3.CancelClicked += (a, b) =>
            {
                try
                {
                    if (ydl != null)
                    {
                        ydl.Cancel();
                    }
                }
                catch (Exception e)
                {
                    Log.Debug(e, "Error cancelling ydl");
                }
                page1.BringToFront();
            };

            if (!AppWinPeer.AppsUseLightTheme)
            {
                colors = new FormColorsDark();
                if (!this.IsHandleCreated)
                {
                    this.CreateHandle();
                }

                DarkModeHelper.UseImmersiveDarkMode(this.Handle, true);
                this.BackColor = colors.ToolbarBackColor;
            }

            Text = TextResource.GetText("LBL_VIDEO_DOWNLOAD");
        }

        protected override void OnClosing(CancelEventArgs e)
        {
            base.OnClosing(e);
            try
            {
                if (ydl != null)
                {
                    ydl.Cancel();
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error cancelling ydl");
            }
        }

        private void ProcessVideo(string url, Action<List<YDLVideoEntry>> callback)
        {
            ydl = new YDLProcess
            {
                Uri = new Uri(url)
            };
            new Thread(() =>
            {
                try
                {
                    ydl.Start();
                    callback.Invoke(YDLOutputParser.Parse(ydl.JsonOutputFile));
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, "Error while running youtube-dl");
                }
                callback.Invoke(new());
            }).Start();
        }
    }
}
