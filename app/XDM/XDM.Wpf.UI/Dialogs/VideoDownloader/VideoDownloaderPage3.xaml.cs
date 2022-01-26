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
using YDLWrapper;

namespace XDM.Wpf.UI.Dialogs.VideoDownloader
{
    /// <summary>
    /// Interaction logic for VideoDownloaderPage3.xaml
    /// </summary>
    public partial class VideoDownloaderPage3 : UserControl
    {
        private AuthenticationInfo? authentication;
        private ProxyInfo? proxy = Config.Instance.Proxy;
        private int speedLimit = Config.Instance.DefaltDownloadSpeed;
        private bool enableSpeedLimit = Config.Instance.EnableSpeedLimit;
        private EventHandler? FormatChanged;
        private List<int> videoQualities;
        private ObservableCollection<VideoEntryViewModel> videos;

        public IApp? App { get; set; }
        public IAppUI? AppUI { get; set; }
        public Window ParentWindow { get; set; }
        public AuthenticationInfo? Authentication { get => authentication; set => authentication = value; }
        public ProxyInfo? Proxy { get => proxy; set => proxy = value; }
        public int SpeedLimit { get => speedLimit; set => speedLimit = value; }
        public bool EnableSpeedLimit { get => enableSpeedLimit; set => enableSpeedLimit = value; }

        public VideoDownloaderPage3()
        {
            InitializeComponent();
            if (Config.Instance.FolderSelectionMode == FolderSelectionMode.Manual)
            {
                if (Config.Instance.RecentFolders != null && Config.Instance.RecentFolders.Count > 0)
                {
                    this.TxtSaveIn.Text = Config.Instance.RecentFolders[0];
                }
                else
                {
                    this.TxtSaveIn.Text = Config.Instance.DefaultDownloadFolder;
                }
            }
            else
            {
                this.TxtSaveIn.Text = Helpers.GetDownloadFolderByFileName("video.mp4");
            }
        }

        public void SetVideoResultList(List<YDLVideoEntry> items)
        {
            if (items == null) return;
            var formatSet = new HashSet<int>();
            this.videos = new(items.Select(x => new VideoEntryViewModel(x)));
            LvVideoList.ItemsSource = this.videos;
            foreach (var item in items)
            {
                if (item.Formats != null)
                {
                    item.Formats.ForEach(item =>
                    {
                        if (!string.IsNullOrEmpty(item.Height))
                        {
                            if (Int32.TryParse(item.Height, out int height))
                            {
                                formatSet.Add(height);
                            }
                        }
                    });
                }
            }
            var formatsList = new List<int>(formatSet);
            formatsList.Sort();
            formatsList.Reverse();
            this.videoQualities = formatsList;
            LbQuality.ItemsSource = this.videoQualities.Select(n => $"{n}p");
            if (this.videoQualities.Count > 0)
            {
                LbQuality.SelectedIndex = 0;
            }
        }

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

        private void AddDownload(YDLVideoFormatEntry videoEntry, bool startImmediately, string? queueId)
        {
            switch (videoEntry.YDLEntryType)
            {
                case YDLEntryType.Http:
                    App!.StartDownload(
                        new SingleSourceHTTPDownloadInfo
                        {
                            Uri = videoEntry.VideoUrl
                        },
                        videoEntry.Title + "." + videoEntry.FileExt,
                        FileNameFetchMode.None,
                        TxtSaveIn.Text,
                        startImmediately,
                        Authentication, Proxy ?? Config.Instance.Proxy,
                        EnableSpeedLimit ? SpeedLimit : 0, queueId
                    );
                    break;
                case YDLEntryType.Dash:
                    App!.StartDownload(
                        new DualSourceHTTPDownloadInfo
                        {
                            Uri1 = videoEntry.VideoUrl,
                            Uri2 = videoEntry.AudioUrl
                        },
                        videoEntry.Title + "." + videoEntry.FileExt,
                        FileNameFetchMode.None,
                        TxtSaveIn.Text,
                        startImmediately,
                        Authentication, Proxy ?? Config.Instance.Proxy,
                        EnableSpeedLimit ? SpeedLimit : 0, queueId
                    );
                    break;
                case YDLEntryType.Hls:
                    App!.StartDownload(
                        new MultiSourceHLSDownloadInfo
                        {
                            VideoUri = videoEntry.VideoUrl,
                            AudioUri = videoEntry.AudioUrl
                        },
                        videoEntry.Title + "." + videoEntry.FileExt,
                        FileNameFetchMode.None,
                        TxtSaveIn.Text,
                        startImmediately,
                        Authentication, Proxy ?? Config.Instance.Proxy,
                        EnableSpeedLimit ? SpeedLimit : 0, queueId
                    );
                    break;
                case YDLEntryType.MpegDash:
                    App!.StartDownload(
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
                        TxtSaveIn.Text,
                        startImmediately, Authentication, Proxy ?? Config.Instance.Proxy,
                        EnableSpeedLimit ? SpeedLimit : 0, queueId
                    );
                    break;
            }
        }


        private void DownloadSelectedItems(bool startImmediately, string? queueId)
        {
            if (string.IsNullOrEmpty(TxtSaveIn.Text))
            {
                AppUI!.ShowMessageBox(AppUI, TextResource.GetText("MSG_CAT_FOLDER_MISSING"));
                return;
            }
            if (this.videos.Select(x => x.IsSelected).Count() == 0)
            {
                AppUI!.ShowMessageBox(AppUI, TextResource.GetText("BAT_SELECT_ITEMS"));
                return;
            }
            var quality = -1;
            if (LbQuality.SelectedIndex >= 0)
            {
                quality = this.videoQualities[LbQuality.SelectedIndex];
            }

            foreach (var item in this.videos)
            {
                if (item.IsSelected)
                {
                    var fmt = FindMatchingFormatByQuality(item.VideoEntry, quality);
                    if (fmt.HasValue)
                    {
                        AddDownload(fmt.Value, startImmediately, queueId);
                    }
                }
            }
            ParentWindow.Close();
        }

        private YDLVideoFormatEntry? FindOnlyMatchingMp4(YDLVideoEntry videoEntry, int quality)
        {
            if (videoEntry.Formats.Count == 0) return null;
            foreach (var format in videoEntry.Formats)
            {
                if (!string.IsNullOrEmpty(format.Height) &&
                    Int32.TryParse(format.Height, out int height) &&
                    height == quality &&
                    (format.FileExt?.ToLowerInvariant()?.EndsWith("mp4") ?? false))
                {
                    return format;
                }
            }
            return null;
        }

        private YDLVideoFormatEntry? FindMatchingFormatByQuality(YDLVideoEntry videoEntry, int quality = -1)
        {
            if (videoEntry.Formats.Count == 0) return null;
            if (quality == -1)
            {
                return videoEntry.Formats[0];
            }
            //if we find an mp4 video with desired height/resolution return it
            var fmt = FindOnlyMatchingMp4(videoEntry, quality);
            if (fmt != null)
            {
                return fmt;
            }
            //if no mp4 is found look for other formats like mkv or webm
            foreach (var format in videoEntry.Formats)
            {
                if (!string.IsNullOrEmpty(format.Height) &&
                    Int32.TryParse(format.Height, out int height) &&
                    height == quality)
                {
                    return format;
                }
            }
            //so far no luck, try to find next best resoultion
            var max = -1;
            foreach (var format in videoEntry.Formats)
            {
                if (!string.IsNullOrEmpty(format.Height) &&
                    Int32.TryParse(format.Height, out int height) &&
                    height > 0 &&
                    quality > height)
                {
                    if (height > max)
                    {
                        max = height;
                        fmt = format;
                    }
                }
            }
            if (fmt != null)
            {
                return fmt;
            }
            //could not found anything as per criteria, return the first format
            return videoEntry.Formats[0];
        }

        private void BtnDownloadNow_Click(object sender, RoutedEventArgs e)
        {
            DownloadSelectedItems(true, null);
        }

        private void DontAddToQueueMenuItem_Click(object sender, RoutedEventArgs e)
        {
            DownloadSelectedItems(false, null);
        }

        private void QueueAndSchedulerMenuItem_Click(object sender, RoutedEventArgs e)
        {
            AppUI!.ShowQueueWindow(this.ParentWindow);
        }

        private void ShowQueuesContextMenu()
        {
            DownloadLaterMenuHelper.PopulateMenuAndAttachEvents((s, e) =>
            {
                DownloadSelectedItems(false, e.QueueId);
            }, BtnDownloadLater, this);
        }

        private void BtnDownloadLater_Click(object sender, RoutedEventArgs e)
        {
            ShowQueuesContextMenu();
        }
    }

    internal class VideoEntryViewModel : INotifyPropertyChanged
    {
        private YDLVideoEntry videoEntry;
        public VideoEntryViewModel(YDLVideoEntry videoEntry)
        {
            this.videoEntry = videoEntry;
        }
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
        public string Name => this.videoEntry.Title;
        public YDLVideoEntry VideoEntry => videoEntry;

        public event PropertyChangedEventHandler? PropertyChanged;
    }
}
