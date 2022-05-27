using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Text;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using TraceLog;
using Translations;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;
using XDM.Core.Lib.Util;
using XDM.Wpf.UI.Dialogs.AdvancedDownloadOption;
using XDM.Wpf.UI.Win32;
using YDLWrapper;

namespace XDM.Wpf.UI.Dialogs.VideoDownloader
{
    /// <summary>
    /// Interaction logic for VideoDownloaderWindow.xaml
    /// </summary>
    public partial class VideoDownloaderWindow : Window, IVideoDownloadView
    {
        public event EventHandler? CancelClicked;
        public event EventHandler? WindowClosed;
        public event EventHandler? BrowseClicked;
        public event EventHandler? SearchClicked;
        public event EventHandler? DownloadClicked;
        public event EventHandler<DownloadLaterEventArgs>? DownloadLaterClicked;
        public event EventHandler? QueueSchedulerClicked;

        public string DownloadLocation { get => Page3.TxtSaveIn.Text; set => Page3.TxtSaveIn.Text = value; }
        public string Url { get => Page1.TxtUrl.Text; set => Page1.TxtUrl.Text = value; }
        public int SelectedFormat { get => Page3.LbQuality.SelectedIndex; set => Page3.LbQuality.SelectedIndex = value; }
        public IEnumerable<int> SelectedRows => GetSelectedVideoList();
        public int SelectedItemCount => GetSelectedVideoListCount();

        public AuthenticationInfo? Authentication { get => authentication; set => authentication = value; }
        public ProxyInfo? Proxy { get => proxy; set => proxy = value; }
        public int SpeedLimit { get => speedLimit; set => speedLimit = value; }
        public bool EnableSpeedLimit { get => enableSpeedLimit; set => enableSpeedLimit = value; }

        private AuthenticationInfo? authentication;
        private ProxyInfo? proxy = Config.Instance.Proxy;
        private int speedLimit = Config.Instance.DefaltDownloadSpeed;
        private bool enableSpeedLimit = Config.Instance.EnableSpeedLimit;

        public void SwitchToInitialPage()
        {
            Page2.Visibility = Visibility.Collapsed;
            Page3.Visibility = Visibility.Collapsed;
            Page1.Visibility = Visibility.Visible;
        }

        public void SwitchToProcessingPage()
        {
            Page3.Visibility = Visibility.Collapsed;
            Page1.Visibility = Visibility.Collapsed;
            Page2.Visibility = Visibility.Visible;
        }

        public void SwitchToFinalPage()
        {
            Page1.Visibility = Visibility.Collapsed;
            Page2.Visibility = Visibility.Collapsed;
            Page3.Visibility = Visibility.Visible;
        }

        public string? SelectFolder()
        {
            using var fb = new System.Windows.Forms.FolderBrowserDialog();
            if (fb.ShowDialog(new WinformsWindow(this)) == System.Windows.Forms.DialogResult.OK)
            {
                return fb.SelectedPath;
            }
            return null;
        }

        public void SetVideoResultList(IEnumerable<string> items, IEnumerable<string> formats)
        {
            var videoList = new ObservableCollection<VideoEntryViewModel>(
                items.Select(x => new VideoEntryViewModel { Name = x, IsSelected = true }));
            Page3.LvVideoList.ItemsSource = videoList;
            Page3.LbQuality.ItemsSource = formats;
        }

        public void CloseWindow()
        {
            this.Close();
        }

        public void ShowWindow()
        {
            this.Show();
        }

        public VideoDownloaderWindow(IApp app, IAppUI appUi)
        {
            InitializeComponent();
            Page1.InitPage(appUi);

            Page3.ParentWindow = this;
            Page3.BtnMore.Click += BtnMore_Click;
            Page3.BtnBrowse.Click += BtnBrowse_Click;
            Page3.DownloadNowClicked += () => DownloadClicked?.Invoke(this, EventArgs.Empty);
            Page3.DownloadLaterClicked += q => DownloadLaterClicked?.Invoke(this, new DownloadLaterEventArgs(q));
            Page3.DontAddToQueue += () => DownloadLaterClicked?.Invoke(this, new DownloadLaterEventArgs(null));
            Page3.QueueAndScheduler += () => QueueSchedulerClicked?.Invoke(this, EventArgs.Empty);
            Page3.ChkSelectAll.Checked += ChkSelectAll_Checked;
            Page3.ChkSelectAll.Unchecked += ChkSelectAll_Checked;

            Page1.SearchClicked += (a, b) =>
            {
                SearchClicked?.Invoke(this, EventArgs.Empty);
            };

            Page2.CancelClicked += (a, b) =>
            {
                CancelClicked?.Invoke(this, EventArgs.Empty);
            };
        }

        private void ChkSelectAll_Checked(object sender, RoutedEventArgs e)
        {
            foreach (VideoEntryViewModel item in Page3.LvVideoList.Items)
            {
                item.IsSelected = Page3.ChkSelectAll.IsChecked ?? false;
            }
        }

        //event EventHandler<DownloadLaterEventArgs>? IVideoDownloadView.DownloadLaterClicked
        //{
        //    add
        //    {
        //        throw new NotImplementedException();
        //    }

        //    remove
        //    {
        //        throw new NotImplementedException();
        //    }
        //}

        private void BtnBrowse_Click(object sender, RoutedEventArgs e)
        {
            BrowseClicked?.Invoke(this, EventArgs.Empty);
        }

        private void BtnMore_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new AdvancedDownloadOptionDialog
            {
                Authentication = Authentication,
                Proxy = Proxy,
                EnableSpeedLimit = EnableSpeedLimit,
                SpeedLimit = SpeedLimit,
                Owner = this
            };
            var ret = dlg.ShowDialog(this);
            if (ret.HasValue && ret.Value)
            {
                Authentication = dlg.Authentication;
                Proxy = dlg.Proxy;
                EnableSpeedLimit = dlg.EnableSpeedLimit;
                SpeedLimit = dlg.SpeedLimit;
            }
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

        private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
        {
            WindowClosed?.Invoke(this, EventArgs.Empty);
        }

        private List<int> GetSelectedVideoList()
        {
            var list = new List<int>();
            var count = 0;
            foreach (VideoEntryViewModel item in Page3.LvVideoList.Items)
            {
                if (item.IsSelected)
                {
                    list.Add(count);
                }
                count++;
            }
            return list;
        }

        private int GetSelectedVideoListCount()
        {
            var count = 0;
            foreach (VideoEntryViewModel item in Page3.LvVideoList.Items)
            {
                if (item.IsSelected)
                {
                    count++;
                }
            }
            return count;
        }
    }
}
