using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using Translations;
using XDM.Core.UI;
using XDM.Core;
using XDM.Wpf.UI.Dialogs.SpeedLimiter;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI.Dialogs.ProgressWindow
{
    /// <summary>
    /// Interaction logic for DownloadProgressWindow.xaml
    /// </summary>
    public partial class DownloadProgressWindow : Window, IProgressWindow
    {
        public DownloadProgressWindow()
        {
            InitializeComponent();
            actSpeedUpdate = value => this.TxtSpeed.Text = value;
            actEtaUpdate = value => this.TxtETA.Text = value;
            actStatusUpdate = value => this.TxtStatus.Text = value;

            ApplicationContext.ApplicationEvent += ApplicationContext_ApplicationEvent;

#if NET45_OR_GREATER
            this.TaskbarItemInfo = new System.Windows.Shell.TaskbarItemInfo
            {
                Description = "",
                ProgressState = System.Windows.Shell.TaskbarItemProgressState.Normal
            };
#endif

            actPrgUpdate = value =>
            {
                var val = value >= 0 && value <= 100 ? value : 0;
                this.PrgProgress.Value = val;
                var prg = value >= 0 && value <= 100 ? value + "% " : "";
                this.Title = $"{prg}{FileNameText}";
#if NET45_OR_GREATER
                this.TaskbarItemInfo.Description = this.Title;
                this.TaskbarItemInfo.ProgressValue = val / 100.0;
#endif
            };
        }

        private void ApplicationContext_ApplicationEvent(object sender, ApplicationEvent e)
        {
            if (e.EventType == "ConfigChanged")
            {
                var speedLimitEnabled = Config.Instance.EnableSpeedLimit ? Config.Instance.DefaltDownloadSpeed > 0 : false;
                var defaultSpeedLimit = Config.Instance.DefaltDownloadSpeed;
                Dispatcher.BeginInvoke(new Action(() => SetSpeedLimitText(speedLimitEnabled, defaultSpeedLimit)));
            }
        }

        public string FileNameText
        {
            get => this.TxtFileName.Text;
            set
            {
                Dispatcher.BeginInvoke(new Action(() => SetFileText(value)));
            }
        }

        public string UrlText
        {
            get => this.TxtUrl.Text;
            set
            {
                Dispatcher.BeginInvoke(new Action(() => TxtUrl.Text = value));
            }
        }

        public string FileSizeText
        {
            get => this.TxtStatus.Text;
            set
            {
                Dispatcher.BeginInvoke(actStatusUpdate, value);
            }
        }

        public string DownloadSpeedText
        {
            get => this.TxtSpeed.Text;
            set
            {
                Dispatcher.BeginInvoke(actSpeedUpdate, value);
            }
        }

        public string DownloadETAText
        {
            get => this.TxtETA.Text;
            set
            {
                Dispatcher.BeginInvoke(actEtaUpdate, value);
            }
        }

        public int DownloadProgress
        {
            get => (int)this.PrgProgress.Value;
            set
            {
                Dispatcher.BeginInvoke(actPrgUpdate, value);
            }
        }

        public string DownloadId
        {
            get => this.downloadId;
            set => this.downloadId = value;
        }

        public void DestroyWindow()
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                try
                {
                    Close();

                    speedLimiterDlg?.Close();
                    speedLimiterDlg = null;
                }
                catch { }
            }));
        }

        public void ShowProgressWindow()
        {
            Dispatcher.BeginInvoke(new Action(() => this.Show()));
        }

        public void DownloadFailed(ErrorDetails error)
        {
            Dispatcher.BeginInvoke(new Action<ErrorDetails>(error =>
                {
                    TxtStatus.Text = error.Message;
                    BtnPause.Content = TextResource.GetText("MENU_RESUME");
                    BtnPause.Tag = new();
                    TxtETA.Text = string.Empty;
                    //TxtSpeedLimit.Visibility = Visibility.Collapsed;
                    speedLimiterDlg?.Close();
                    speedLimiterDlg = null;
                }), error);
        }

        public void DownloadCancelled()
        {
            Dispatcher.BeginInvoke(new Action(() =>
                {
                    TxtStatus.Text = TextResource.GetText("MSG_DWN_STOP");
                    TxtETA.Text = string.Empty;
                    BtnPause.Content = TextResource.GetText("MENU_RESUME");
                    BtnPause.Tag = new();
                    //TxtSpeedLimit.Visibility = Visibility.Collapsed;
                    speedLimiterDlg?.Close();
                    speedLimiterDlg = null;
                }));
        }

        public void DownloadStarted()
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                BtnPause.Content = TextResource.GetText("MENU_PAUSE");
                BtnPause.Tag = null;
                //TxtSpeedLimit.Visibility = Visibility.Visible;

                if (ApplicationContext.CoreService.GetLiveDownloadSpeedLimit(downloadId, out bool enable, out int limit))
                {
                    SetSpeedLimitText(enable, limit);
                }
            }));
        }

        private void SetSpeedLimitText(bool enable, int limit)
        {
            if (enable && limit > 0)
            {
                TxtSpeedLimit.Text = $"{TextResource.GetText("SPEED_LIMIT_TITLE")} - {limit}K/S";
            }
            else
            {
                TxtSpeedLimit.Text = TextResource.GetText("MSG_NO_SPEED_LIMIT");
            }
        }

        private void SetFileText(string value)
        {
            TxtFileName.Text = value;
            var prg = PrgProgress.Value >= 0 && PrgProgress.Value <= 100 ? PrgProgress.Value + "% " : "";
            this.Title = $"{prg}{value}";
        }

        private void StopDownload(bool close)
        {
            if (downloadId != null)
            {
                ApplicationContext.CoreService.StopDownloads(new List<string> { downloadId }, close);
            }
        }

        private Action<string> actSpeedUpdate, actEtaUpdate, actStatusUpdate;

        private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
        {
            StopDownload(true);
        }

        private void BtnPause_Click(object sender, RoutedEventArgs e)
        {
            if (BtnPause.Tag != null)
            {
                ApplicationContext.Application.ResumeDownload(downloadId);
                BtnPause.Content = TextResource.GetText("MENU_PAUSE");
                BtnPause.Tag = null;
                //TxtSpeedLimit.Visibility = Visibility.Visible;
            }
            else
            {
                StopDownload(false);
                BtnPause.Content = TextResource.GetText("MENU_RESUME");
                BtnPause.Tag = new();
            }
        }

        private void BtnStop_Click(object sender, RoutedEventArgs e)
        {
            StopDownload(true);
        }

        private void BtnHide_Click(object sender, RoutedEventArgs e)
        {
            Closing -= Window_Closing;
            ApplicationContext.CoreService.HideProgressWindow(downloadId);
            //Close();
        }

        private void TxtSpeedLimit_MouseDown(object sender, MouseButtonEventArgs e)
        {
            ApplicationContext.PlatformUIService.ShowSpeedLimiterWindow();
            //if (speedLimiterDlg == null)
            //{
            //    speedLimiterDlg = new SpeedLimiterWindow
            //    {
            //        Owner = this
            //    };
            //    if (ApplicationContext.CoreService.GetLiveDownloadSpeedLimit(downloadId, out bool enable, out int limit))
            //    {
            //        speedLimiterDlg.EnableSpeedLimit = enable;
            //        speedLimiterDlg.SpeedLimit = limit;
            //    }
            //    speedLimiterDlg.Closed += (_, _) =>
            //    {
            //        speedLimiterDlg = null;
            //    };
            //    speedLimiterDlg.OkClicked += (a, b) =>
            //    {
            //        var limit2 = speedLimiterDlg.SpeedLimit;
            //        ApplicationContext.CoreService.UpdateSpeedLimit(DownloadId, speedLimiterDlg.EnableSpeedLimit, limit2);
            //        SetSpeedLimitText(speedLimiterDlg.EnableSpeedLimit, limit2);
            //    };
            //}

            //if (!speedLimiterDlg.IsVisible)
            //{
            //    speedLimiterDlg.Show();
            //}
            //else
            //{
            //    speedLimiterDlg.Activate();
            //}
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

        private Action<int> actPrgUpdate;

        private void Window_Loaded(object sender, RoutedEventArgs e)
        {
            var speedLimitEnabled = Config.Instance.EnableSpeedLimit ? Config.Instance.DefaltDownloadSpeed > 0 : false;
            var defaultSpeedLimit = Config.Instance.DefaltDownloadSpeed;
            SetSpeedLimitText(speedLimitEnabled, defaultSpeedLimit);
        }

        private string downloadId = string.Empty;
        private SpeedLimiterWindow? speedLimiterDlg;
    }
}

