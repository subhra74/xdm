using System;
using System.Collections.Generic;
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
using XDM.Core.Lib.Util;
using XDM.Wpf.UI.Win32;
using YDLWrapper;

namespace XDM.Wpf.UI.Dialogs.VideoDownloader
{
    /// <summary>
    /// Interaction logic for VideoDownloaderWindow.xaml
    /// </summary>
    public partial class VideoDownloaderWindow : Window
    {
        private YDLProcess? ydl;

        public VideoDownloaderWindow(IApp app, IAppUI appUi)
        {
            InitializeComponent();
            Page1.InitPage(appUi);

            Page1.SearchClicked += (a, b) =>
            {
                if (Helpers.IsUriValid(Page1.UrlText))
                {
                    Page1.Visibility = Visibility.Collapsed;
                    Page2.Visibility = Visibility.Visible;
                    ProcessVideo(Page1.UrlText, result => Dispatcher.BeginInvoke(new Action(() =>
                    {
                        Page2.Visibility = Visibility.Collapsed;
                        Page3.Visibility = Visibility.Visible;
                        //page2.SetVideoResultList(result);
                    })));
                }
                else
                {
                    appUi.ShowMessageBox(this, TextResource.GetText("MSG_INVALID_URL"));
                }
            };

            Page2.CancelClicked += (a, b) =>
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
                Page2.Visibility = Visibility.Collapsed;
                Page1.Visibility = Visibility.Visible;
            };
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
    }
}
