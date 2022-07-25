using System;
using System.Collections;
using System.Collections.Generic;
using System.Collections.ObjectModel;
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
using XDM.Core;
using XDM.Core.Downloader;
using XDM.Core.Downloader.Adaptive.Dash;
using XDM.Core.Downloader.Adaptive.Hls;
using XDM.Core.Downloader.Progressive.DualHttp;
using XDM.Core.Downloader.Progressive.SingleHttp;
using XDM.Core.UI;
using XDM.Core.Util;
using XDM.Wpf.UI.Common.Helpers;
using XDM.Wpf.UI.Dialogs.AdvancedDownloadOption;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI.Dialogs.DownloadSelection
{
    /// <summary>
    /// Interaction logic for DownloadSelectionWindow.xaml
    /// </summary>
    public partial class DownloadSelectionWindow : Window, IDownloadSelectionView
    {
        private AuthenticationInfo? authentication;
        private ProxyInfo? proxy = Config.Instance.Proxy;
        private int speedLimit = Config.Instance.DefaltDownloadSpeed;
        private bool enableSpeedLimit = Config.Instance.EnableSpeedLimit;

        public string DownloadLocation { get => this.TxtSaveIn.Text; set => this.TxtSaveIn.Text = value; }
        public AuthenticationInfo? Authentication { get => this.authentication; set => this.authentication = value; }
        public ProxyInfo? Proxy { get => this.proxy; set => this.proxy = value; }
        public int SpeedLimit { get => this.speedLimit; set => this.speedLimit = value; }
        public bool EnableSpeedLimit { get => this.enableSpeedLimit; set => this.enableSpeedLimit = value; }
        public int SelectedRowCount => this.GetSelectedItems().Count();
        public IEnumerable<IDownloadEntryWrapper> SelectedItems => this.GetSelectedItems();

        public event EventHandler? BrowseClicked;
        public event EventHandler? DownloadClicked;
        public event EventHandler? QueueSchedulerClicked;
        public event EventHandler<DownloadLaterEventArgs>? DownloadLaterClicked;

        public string? SelectFolder()
        {
            using var fb = new System.Windows.Forms.FolderBrowserDialog();
            if (fb.ShowDialog(new WinformsWindow(this)) == System.Windows.Forms.DialogResult.OK)
            {
                return fb.SelectedPath;
            }
            return null;
        }

        public void CloseWindow()
        {
            this.Close();
        }

        public void ShowWindow()
        {
            this.Show();
        }

        public void SetData(FileNameFetchMode mode, IEnumerable<object> downloads,
            Func<object, IDownloadEntryWrapper, bool> populateEntryWrapper)
        {
            this.LbDownloadList.ItemsSource = new ObservableCollection<DownloadEntryWrapper>(
                downloads.Select(o =>
            {
                var ent = new DownloadEntryWrapper();
                populateEntryWrapper.Invoke(o, ent);
                return ent;
            }));
        }

        private IEnumerable<IDownloadEntryWrapper> GetSelectedItems()
        {
            foreach (IDownloadEntryWrapper item in LbDownloadList.ItemsSource)
            {
                if (item.IsSelected)
                {
                    yield return item;
                }
            }
        }

        public DownloadSelectionWindow()
        {
            InitializeComponent();
        }

        private void ChkSelectAll_Checked(object sender, RoutedEventArgs e)
        {
            foreach (IDownloadEntryWrapper item in LbDownloadList.ItemsSource)
            {
                item.IsSelected = ChkSelectAll.IsChecked ?? false;
            }
        }

        private void QueueAndSchedulerMenuItem_Click(object sender, RoutedEventArgs e)
        {
            QueueSchedulerClicked?.Invoke(this, EventArgs.Empty);
        }

        private void DontAddToQueueMenuItem_Click(object sender, RoutedEventArgs e)
        {
            DownloadLaterClicked?.Invoke(this, new DownloadLaterEventArgs(null));
            //DownloadSelectedItems(false, null);
        }

        private void ShowQueuesContextMenu()
        {
            DownloadLaterMenuHelper.PopulateMenuAndAttachEvents((s, e) =>
            {
                DownloadLaterClicked?.Invoke(this, new DownloadLaterEventArgs(e.QueueId));
                //DownloadSelectedItems(false, e.QueueId);
            }, BtnDownloadLater, this);
        }

        private void BtnDownloadNow_Click(object sender, RoutedEventArgs e)
        {
            DownloadClicked?.Invoke(this, EventArgs.Empty);
            //DownloadSelectedItems(true, null);
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
            if (App.Skin == Skin.Dark)
            {
                var helper = new WindowInteropHelper(this);
                helper.EnsureHandle();
                DarkModeHelper.UseImmersiveDarkMode(helper.Handle, true);
            }
#endif
        }

        private void BtnBrowse_Click(object sender, RoutedEventArgs e)
        {
            BrowseClicked?.Invoke(this, EventArgs.Empty);
        }
    }

    internal class DownloadEntryWrapper : IDownloadEntryWrapper, INotifyPropertyChanged
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
    }
}
