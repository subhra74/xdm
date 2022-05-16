using System;
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
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Navigation;
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
using YDLWrapper;

namespace XDM.Wpf.UI.Dialogs.VideoDownloader
{
    /// <summary>
    /// Interaction logic for VideoDownloaderPage3.xaml
    /// </summary>
    public partial class VideoDownloaderPage3 : UserControl
    {

        private EventHandler? FormatChanged;
        private List<int> videoQualities;
        private ObservableCollection<VideoEntryViewModel> videos;

        public IApp? App { get; set; }
        public IAppUI? AppUI { get; set; }
        public Window ParentWindow { get; set; }
        public Action DownloadNowClicked { get => downloadNowClicked; set => downloadNowClicked = value; }
        public Action DontAddToQueue { get => dontAddToQueue; set => dontAddToQueue = value; }
        public Action QueueAndScheduler { get => queueAndScheduler; set => queueAndScheduler = value; }
        public Action<string> DownloadLaterClicked { get => downloadLaterClicked; set => downloadLaterClicked = value; }

        private Action downloadNowClicked;
        private Action dontAddToQueue;
        private Action queueAndScheduler;
        private Action<string> downloadLaterClicked;

        public VideoDownloaderPage3()
        {
            InitializeComponent();
        }

        //public void SetVideoResultList(List<YDLVideoEntry> items)
        //{
        //    if (items == null) return;
        //    var formatSet = new HashSet<int>();
        //    this.videos = new(items.Select(x => new VideoEntryViewModel { x.}));
        //    LvVideoList.ItemsSource = this.videos;
        //    foreach (var item in items)
        //    {
        //        if (item.Formats != null)
        //        {
        //            item.Formats.ForEach(item =>
        //            {
        //                if (!string.IsNullOrEmpty(item.Height))
        //                {
        //                    if (Int32.TryParse(item.Height, out int height))
        //                    {
        //                        formatSet.Add(height);
        //                    }
        //                }
        //            });
        //        }
        //    }
        //    var formatsList = new List<int>(formatSet);
        //    formatsList.Sort();
        //    formatsList.Reverse();
        //    this.videoQualities = formatsList;
        //    LbQuality.ItemsSource = this.videoQualities.Select(n => $"{n}p");
        //    if (this.videoQualities.Count > 0)
        //    {
        //        LbQuality.SelectedIndex = 0;
        //    }
        //}

        private void LbFormat_SelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            //if (LbFormat.SelectedIndex == 0)
            //{
            //    LbQuality.ItemsSource = this.videoQualities.Select(n => $"{n}p");
            //    if (this.videoQualities.Count > 0)
            //    {
            //        LbQuality.SelectedIndex = 0;
            //    }
            //}
            //else
            //{
            //    LbQuality.ItemsSource = new string[] { "320Kbps", "128kbps" };
            //    LbQuality.SelectedIndex = 0;
            //}
        }

        private void ChkSelectAll_Checked(object sender, RoutedEventArgs e)
        {
            foreach (var item in videos)
            {
                item.IsSelected = ChkSelectAll.IsChecked ?? false;
            }
        }

        private void BtnDownloadNow_Click(object sender, RoutedEventArgs e)
        {
            DownloadNowClicked.Invoke();
        }

        private void DontAddToQueueMenuItem_Click(object sender, RoutedEventArgs e)
        {
            DontAddToQueue.Invoke();
        }

        private void QueueAndSchedulerMenuItem_Click(object sender, RoutedEventArgs e)
        {
            QueueAndScheduler.Invoke();
            AppUI!.ShowQueueWindow(this.ParentWindow);
        }

        private void ShowQueuesContextMenu()
        {
            DownloadLaterMenuHelper.PopulateMenuAndAttachEvents((s, e) =>
            {
                DownloadLaterClicked.Invoke(e.QueueId);
            }, BtnDownloadLater, this);
        }

        private void BtnDownloadLater_Click(object sender, RoutedEventArgs e)
        {
            ShowQueuesContextMenu();
        }
    }

    internal class VideoEntryViewModel : INotifyPropertyChanged
    {
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
        public string Name { get; set; }

        public event PropertyChangedEventHandler? PropertyChanged;
    }
}
