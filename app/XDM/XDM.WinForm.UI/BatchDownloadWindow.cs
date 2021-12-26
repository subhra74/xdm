using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Drawing;
using System.Linq;
using System.Text;

using System.Windows.Forms;
using Translations;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Downloader;
using XDM.Core.Lib.Downloader.Progressive.SingleHttp;
using XDM.Core.Lib.Util;
using XDM.WinForm.UI.BachDownloadPages;

namespace XDM.WinForm.UI
{
    public partial class BatchDownloadWindow : Form
    {
        BatchDownloadPage1 page1;
        BatchDownloadPage2 page2;
        public BatchDownloadWindow(IApp app, IAppUI appUi)
        {
            InitializeComponent();

            page1 = new BatchDownloadPage1
            {
                Dock = DockStyle.Fill,
            };

            page1.LinksAdded += (sender, args) =>
            {
                page2.BringToFront();
                page2.SetBatchLinks(args.Links);
            };

            page1.Cancelled += (_, _) =>
            {
                Dispose();
            };

            page2 = new BatchDownloadPage2(appUi)
            {
                Dock = DockStyle.Fill,
            };

            page2.DownloadNow += (sender, args) =>
            {
                var list = new List<string>();
                foreach (var link in args.Links)
                {
                    list.Add(app.StartDownload(
                        new SingleSourceHTTPDownloadInfo
                        {
                            Uri = link.ToString()
                        },
                        Helpers.GetFileName(link),
                        FileNameFetchMode.FileNameAndExtension,
                        args.TargetFolder,
                        true, args.Authentication, args.Proxy,
                        args.EnableSpeedLimit ? args.SpeedLimit : 0, args.QueueId
                    ));
                }

                foreach (var id in list)
                {
                    app.ResumeNonInteractiveDownloads(list);
                }

                Dispose();
            };

            page2.Cancelled += (_, _) =>
            {
                Dispose();
            };

            page2.DownloadLater += (sender, args) =>
            {
                foreach (var link in args.Links)
                {
                    app.StartDownload(
                        new SingleSourceHTTPDownloadInfo
                        {
                            Uri = link.ToString()
                        },
                        Helpers.GetFileName(link),
                        FileNameFetchMode.FileNameAndExtension,
                        args.TargetFolder,
                        true, args.Authentication, args.Proxy,
                        args.EnableSpeedLimit ? args.SpeedLimit : 0, args.QueueId
                    );
                }

                Dispose();
            };

            this.Controls.Add(page1);
            this.Controls.Add(page2);
            page1.BringToFront();

            Text = TextResource.GetText("MENU_BATCH_DOWNLOAD");
        }
    }

    internal class BatchLinkEventArgs : EventArgs
    {
        public IEnumerable<Uri> Links { get; set; }
    }

    internal class BatchLinkDownloadEventArgs : EventArgs
    {
        public IEnumerable<Uri> Links { get; set; }
        public string TargetFolder { get; set; }
        public string? QueueId { get; set; }
        public AuthenticationInfo? Authentication { get; set; }
        public ProxyInfo? Proxy { get; set; }
        public int SpeedLimit { get; set; }
        public bool EnableSpeedLimit { get; set; }
    }
}
