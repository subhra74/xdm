using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using XDM.Core;
using XDM.Core.BrowserMonitoring;
using XDM.Core.Util;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI.Dialogs.MediaCapture
{
    /// <summary>
    /// Interaction logic for MediaCaptureWindow.xaml
    /// </summary>
    public partial class MediaCaptureWindow : Window
    {
        private void AddMedia(MediaInfo info)
        {
            lvVideos.Add(info);
        }

        private void UpdateMedia(MediaInfo info)
        {
            var index = -1;
            var k = 0;
            foreach (var item in lvVideos)
            {
                if (item.ID == info.ID)
                {
                    index = k;
                    break;
                }
                k++;
            }
            lvVideos[index] = info;
        }

        private void VideoTracker_MediaAdded(object sender, MediaInfoEventArgs e)
        {
            //Run code in UI thread
            Dispatcher.BeginInvoke(mediaAction, e.MediaInfo);
        }

        private void VideoTracker_MediaUpdated(object sender, MediaInfoEventArgs e)
        {
            //Run code in UI thread
            Dispatcher.BeginInvoke(mediaUpdated, e.MediaInfo);
        }

        private ObservableCollection<MediaInfo> lvVideos = new();
        private Action<MediaInfo> mediaAction;
        private Action<MediaInfo> mediaUpdated;

        public MediaCaptureWindow()
        {
            InitializeComponent();
            ApplicationContext.VideoTracker.MediaAdded += VideoTracker_MediaAdded;
            ApplicationContext.VideoTracker.MediaUpdated += VideoTracker_MediaUpdated;
            this.mediaAction = new(this.AddMedia);
            this.mediaUpdated = new(this.UpdateMedia);
            var list = ApplicationContext.VideoTracker.GetVideoList();
            if (list.Count > 0)
            {
                foreach (var mi in list)
                {
                    lvVideos.Add(mi);
                }
            }
            this.LvVideos.ItemsSource = this.lvVideos;
        }

        private void BtnClear_Click(object sender, RoutedEventArgs e)
        {
            ApplicationContext.VideoTracker.ClearVideoList();
            this.lvVideos.Clear();
        }

        private void BtnDownload_Click(object sender, RoutedEventArgs e)
        {
            var index = this.LvVideos.SelectedIndex;
            if (index >= 0)
            {
                var item = this.lvVideos[index];
                ApplicationContext.VideoTracker.AddVideoDownload(item.ID);
            }
        }

        private void BtnClose_Click(object sender, RoutedEventArgs e)
        {
            this.Close();
        }

        private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
        {
            ApplicationContext.VideoTracker.MediaAdded -= VideoTracker_MediaAdded;
            ApplicationContext.VideoTracker.MediaUpdated -= VideoTracker_MediaUpdated;
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

        private void HowToLink_MouseDown(object sender, MouseButtonEventArgs e)
        {
            PlatformHelper.OpenBrowser(Links.MediaGrabberHowToUrl);
        }

        private void ChkTopMost_Checked(object sender, RoutedEventArgs e)
        {
            this.Topmost = true;
        }

        private void ChkTopMost_Unchecked(object sender, RoutedEventArgs e)
        {
            this.Topmost = false;
        }
    }
}
