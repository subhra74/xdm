using XDM.Core.BrowserMonitoring;
using System;
using System.Collections.Generic;
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
using TraceLog;
using Translations;
using XDM.Core;
using XDM.Core.UI;
using XDM.Core.Util;

namespace XDM.Wpf.UI.Dialogs.Settings
{
    /// <summary>
    /// Interaction logic for BrowserMonitoringView.xaml
    /// </summary>
    public partial class BrowserMonitoringView : UserControl, ISettingsPage
    {
        public BrowserMonitoringView()
        {
            InitializeComponent();
            CmbMinVidSize.ItemsSource = new int[] { 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768 };
        }

        public void PopulateUI()
        {
            TxtChromeWebStoreUrl.Text = Config.ChromeWebstoreUrl;
            TxtFirefoxAMOUrl.Text = Config.FirefoxAMOUrl;
            TxtDefaultFileTypes.Text = string.Join(",", Config.Instance.FileExtensions);
            TxtDefaultVideoFormats.Text = string.Join(",", Config.Instance.VideoExtensions);
            TxtExceptions.Text = string.Join(",", Config.Instance.BlockedHosts);
            CmbMinVidSize.SelectedItem = Config.Instance.MinVideoSize;
            ChkMonitorClipboard.IsChecked = Config.Instance.MonitorClipboard;
            ChkTimestamp.IsChecked = Config.Instance.FetchServerTimeStamp;
        }

        public void UpdateConfig()
        {
            //Browser monitoring
            Config.Instance.FileExtensions = TxtDefaultFileTypes.Text.Split(',').Select(x => x.Trim()).Where(x => x.Length > 0).ToArray();
            Config.Instance.VideoExtensions = TxtDefaultVideoFormats.Text.Split(',').Select(x => x.Trim()).Where(x => x.Length > 0).ToArray();
            Config.Instance.BlockedHosts = TxtExceptions.Text.Split(',').Select(x => x.Trim()).Where(x => x.Length > 0).ToArray();
            Config.Instance.FetchServerTimeStamp = ChkTimestamp.IsChecked.HasValue ? ChkTimestamp.IsChecked.Value : false;
            Config.Instance.MonitorClipboard = ChkMonitorClipboard.IsChecked.HasValue ? ChkMonitorClipboard.IsChecked.Value : false;
            Config.Instance.MinVideoSize = (int)CmbMinVidSize.SelectedItem;
        }

        private void BtnChrome_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                NativeMessagingConfigurer.InstallNativeMessagingHost(Browser.Chrome);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error installing native host");
                MessageBox.Show(TextResource.GetText("MSG_NATIVE_HOST_FAILED"));
                return;
            }

            try
            {
                BrowserLauncher.LaunchGoogleChrome(AppInstance.Core.ChromeExtensionUrl);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error launching Google Chrome");
                MessageBox.Show($"{TextResource.GetText("MSG_BROWSER_LAUNCH_FAILED")} Google Chrome");
            }
        }

        private void BtnFirefox_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                NativeMessagingConfigurer.InstallNativeMessagingHost(Browser.Firefox);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error installing native host");
                MessageBox.Show(TextResource.GetText("MSG_NATIVE_HOST_FAILED"));
                return;
            }

            try
            {
                BrowserLauncher.LaunchFirefox(AppInstance.Core.FirefoxExtensionUrl);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error launching Firefox");
                MessageBox.Show($"{TextResource.GetText("MSG_BROWSER_LAUNCH_FAILED")} Firefox");
            }
        }

        private void BtnEdge_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                NativeMessagingConfigurer.InstallNativeMessagingHost(Browser.Chrome);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error installing native host");
                MessageBox.Show(TextResource.GetText("MSG_NATIVE_HOST_FAILED"));
                return;
            }

            try
            {
                BrowserLauncher.LaunchMicrosoftEdge(AppInstance.Core.ChromeExtensionUrl);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error Microsoft Edge");
                MessageBox.Show($"{TextResource.GetText("MSG_BROWSER_LAUNCH_FAILED")} Microsoft Edge");
            }
        }

        private void BtnOpera_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                NativeMessagingConfigurer.InstallNativeMessagingHost(Browser.Chrome);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error installing native host");
                MessageBox.Show(TextResource.GetText("MSG_NATIVE_HOST_FAILED"));
                return;
            }

            try
            {
                BrowserLauncher.LaunchOperaBrowser(AppInstance.Core.ChromeExtensionUrl);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error launching Opera");
                MessageBox.Show($"{TextResource.GetText("MSG_BROWSER_LAUNCH_FAILED")} Opera");
            }
        }

        private void BtnCopy1_Click(object sender, RoutedEventArgs e)
        {
            Clipboard.SetText(TxtChromeWebStoreUrl.Text);
        }

        private void BtnCopy2_Click(object sender, RoutedEventArgs e)
        {
            Clipboard.SetText(TxtFirefoxAMOUrl.Text);
        }

        private void BtnDefault1_Click(object sender, RoutedEventArgs e)
        {
            TxtDefaultFileTypes.Text = string.Join(",", Config.DefaultFileExtensions);
        }

        private void BtnDefault2_Click(object sender, RoutedEventArgs e)
        {
            TxtDefaultVideoFormats.Text = string.Join(",", Config.DefaultVideoExtensions);
        }

        private void BtnDefault3_Click(object sender, RoutedEventArgs e)
        {
            TxtExceptions.Text = string.Join(",", Config.DefaultBlockedHosts);
        }

        private void VideoWikiLink_MouseDown(object sender, MouseButtonEventArgs e)
        {
            Helpers.OpenBrowser("https://subhra74.github.io/xdm/redirect-support.html?path=video");
        }
    }
}
