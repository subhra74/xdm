using System;
using System.Collections;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using Translations;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Downloader;
using XDM.Core.Lib.Downloader.Adaptive.Dash;
using XDM.Core.Lib.Downloader.Adaptive.Hls;
using XDM.Core.Lib.Downloader.Progressive.DualHttp;
using XDM.Core.Lib.Downloader.Progressive.SingleHttp;
using XDM.Core.Lib.Util;
using XDM.Wpf.UI.Common.Helpers;
using XDM.Wpf.UI.Dialogs.AdvancedDownloadOption;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI.Dialogs.DownloadSelection
{
    /// <summary>
    /// Interaction logic for DownloadSelectionWindow.xaml
    /// </summary>
    public partial class DownloadSelectionWindow : Window
    {
        private IAppUI AppUI;
        private IApp App;
        private List<DownloadEntryWrapper> downloadList;
        private AuthenticationInfo? authentication;
        private ProxyInfo? proxy = Config.Instance.Proxy;
        private int speedLimit = Config.Instance.DefaltDownloadSpeed;
        private bool enableSpeedLimit = Config.Instance.EnableSpeedLimit;
        private FileNameFetchMode mode;

        public DownloadSelectionWindow(IApp app, IAppUI appUI, FileNameFetchMode mode, IEnumerable<object> downloads)
        {
            InitializeComponent();
            this.App = app;
            this.AppUI = appUI;
            this.mode = mode;
            downloadList = new(downloads.Select(o => new DownloadEntryWrapper(o)));
            this.LbDownloadList.ItemsSource = downloadList;

            if (Config.Instance.RecentFolders != null && Config.Instance.RecentFolders.Count > 0)
            {
                this.TxtSaveIn.Text = Config.Instance.RecentFolders[0];
            }
            else
            {
                this.TxtSaveIn.Text = Config.Instance.DefaultDownloadFolder;
            }
        }

        private void ChkSelectAll_Checked(object sender, RoutedEventArgs e)
        {
            foreach (var item in downloadList)
            {
                item.IsSelected = ChkSelectAll.IsChecked ?? false;
            }
        }

        private void AddDownload(DownloadEntryWrapper wrapper, bool startImmediately, string? queueId)
        {
            switch (wrapper.EntryType)
            {
                case "Http":
                    App.StartDownload(
                        (SingleSourceHTTPDownloadInfo)wrapper.DownloadEntry,
                        wrapper.Name,
                        mode,
                        TxtSaveIn.Text,
                        startImmediately,
                        authentication, proxy ?? Config.Instance.Proxy,
                        enableSpeedLimit ? speedLimit : 0, queueId
                    );
                    break;
                case "Dash":
                    App.StartDownload(
                        (DualSourceHTTPDownloadInfo)wrapper.DownloadEntry,
                        wrapper.Name,
                        mode,
                        TxtSaveIn.Text,
                        startImmediately,
                        authentication, proxy ?? Config.Instance.Proxy,
                        enableSpeedLimit ? speedLimit : 0, queueId
                    );
                    break;
                case "Hls":
                    App.StartDownload(
                        (MultiSourceHLSDownloadInfo)wrapper.DownloadEntry,
                        wrapper.Name,
                        mode,
                        TxtSaveIn.Text,
                        startImmediately,
                        authentication, proxy ?? Config.Instance.Proxy,
                        enableSpeedLimit ? speedLimit : 0, queueId
                    );
                    break;
                case "MpegDash":
                    App.StartDownload(
                        (MultiSourceDASHDownloadInfo)wrapper.DownloadEntry,
                        wrapper.Name,
                        mode,
                        TxtSaveIn.Text,
                        startImmediately,
                        authentication, proxy ?? Config.Instance.Proxy,
                        enableSpeedLimit ? speedLimit : 0, queueId
                        );
                    break;
            }
        }

        private void DownloadSelectedItems(bool startImmediately, string? queueId)
        {
            if (string.IsNullOrEmpty(TxtSaveIn.Text))
            {
                AppUI.ShowMessageBox(this, TextResource.GetText("MSG_CAT_FOLDER_MISSING"));
                return;
            }
            if (this.downloadList.Select(x => x.IsSelected).Count() == 0)
            {
                AppUI.ShowMessageBox(this, TextResource.GetText("BAT_SELECT_ITEMS"));
                return;
            }

            foreach (var item in this.downloadList)
            {
                if (item.IsSelected)
                {
                    AddDownload(item, startImmediately, queueId);
                }
            }
            Close();
        }

        private void QueueAndSchedulerMenuItem_Click(object sender, RoutedEventArgs e)
        {
            AppUI!.ShowQueueWindow(this);
        }

        private void DontAddToQueueMenuItem_Click(object sender, RoutedEventArgs e)
        {
            DownloadSelectedItems(false, null);
        }

        private void ShowQueuesContextMenu()
        {
            DownloadLaterMenuHelper.PopulateMenuAndAttachEvents((s, e) =>
            {
                DownloadSelectedItems(false, e.QueueId);
            }, BtnDownloadLater, this);
        }

        private void BtnDownloadNow_Click(object sender, RoutedEventArgs e)
        {
            DownloadSelectedItems(true, null);
        }

        private void BtnMore_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new AdvancedDownloadOptionDialog
            {
                Authentication = authentication,
                Proxy = proxy,
                EnableSpeedLimit = enableSpeedLimit,
                SpeedLimit = speedLimit,
                Owner = this
            };
            var ret = dlg.ShowDialog(this);

            if (ret.HasValue && ret.Value)
            {
                authentication = dlg.Authentication;
                proxy = dlg.Proxy;
                enableSpeedLimit = dlg.EnableSpeedLimit;
                speedLimit = dlg.SpeedLimit;
            }
        }

        private void BtnDownloadLater_Click(object sender, RoutedEventArgs e)
        {
            ShowQueuesContextMenu();
        }

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            NativeMethods.DisableMinMaxButton(this);
#if NET45_OR_GREATER
            if (XDM.Wpf.UI.App.Skin == Skin.Dark)
            {
                var helper = new WindowInteropHelper(this);
                helper.EnsureHandle();
                DarkModeHelper.UseImmersiveDarkMode(helper.Handle, true);
            }
#endif
        }
    }

    internal class DownloadEntryWrapper : INotifyPropertyChanged
    {
        public event PropertyChangedEventHandler? PropertyChanged;
        public string Name { get; set; }
        private bool selected = true;
        public bool IsSelected
        {
            get
            {
                return selected;
            }
            set
            {
                selected = value;
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs("IsSelected"));
            }
        }
        public object DownloadEntry { get; set; }
        public string EntryType { get; set; }

        public DownloadEntryWrapper(object obj)
        {
            this.DownloadEntry = obj;
            if (obj is SingleSourceHTTPDownloadInfo shi)
            {
                this.EntryType = "Http";
                this.Name = shi.File ?? Helpers.GetFileName(new Uri(shi.Uri));
            }
            else if (obj is DualSourceHTTPDownloadInfo dhi)
            {
                this.EntryType = "Dash";
                this.Name = dhi.File ?? Helpers.GetFileName(new Uri(dhi.Uri1));
            }
            else if (obj is MultiSourceHLSDownloadInfo mhi)
            {
                this.EntryType = "Hls";
                this.Name = mhi.File ?? Helpers.GetFileName(new Uri(mhi.VideoUri));
            }
            else if (obj is MultiSourceDASHDownloadInfo mdi)
            {
                this.EntryType = "MpegDash";
                this.Name = mdi.File ?? Helpers.GetFileName(new Uri(mdi.Url));
            }
        }
    }
}
