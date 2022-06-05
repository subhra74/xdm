using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using UI = Gtk.Builder.ObjectAttribute;
using Gtk;
using Application = Gtk.Application;
using IoPath = System.IO.Path;
using XDM.Common.UI;
using Translations;
using XDM.Core.Lib.Common;
using XDM.GtkUI.Utils;

namespace XDM.GtkUI.Dialogs.ProgressWindow
{
    public class DownloadProgressWindow : Window, IProgressWindow
    {
        public string FileNameText
        {
            get => this.TxtFileName.Text;
            set
            {
                Application.Invoke((a, b) => SetFileText(value));
                //Dispatcher.Invoke(new Action(() => SetFileText(value)));
            }
        }
        public string UrlText
        {
            get => this.TxtUrl.Text;
            set
            {
                Application.Invoke((a, b) => TxtUrl.Text = value);
                //Dispatcher.Invoke(new Action(() => TxtUrl.Text = value));
            }
        }
        public string FileSizeText
        {
            get => this.TxtStatus.Text;
            set
            {
                Application.Invoke((a, b) => actStatusUpdate.Invoke(value));
                //Dispatcher.Invoke(actStatusUpdate, value);
            }
        }
        public string DownloadSpeedText
        {
            get => this.TxtSpeed.Text;
            set
            {
                Application.Invoke((a, b) => actSpeedUpdate.Invoke(value));
                //Dispatcher.Invoke(actSpeedUpdate, value);
            }
        }
        public string DownloadETAText
        {
            get => this.TxtETA.Text;
            set
            {
                Application.Invoke((a, b) => actEtaUpdate.Invoke(value));
                //Dispatcher.Invoke(actEtaUpdate, value);
            }
        }
        public int DownloadProgress
        {
            get => (int)(this.PrgProgress.Fraction * 100);
            set
            {
                Application.Invoke((a, b) => actPrgUpdate.Invoke(value));
                //Dispatcher.Invoke(actPrgUpdate, value);
            }
        }
        public string DownloadId
        {
            get => this.downloadId;
            set => this.downloadId = value;
        }

        public IApp App { get; set; }

        public IAppUI AppUI { get; set; }

        public void DestroyWindow()
        {
            Application.Invoke((a, b) =>
            {
                try
                {
                    Destroy();
                    Dispose();
                }
                catch { }
            });
            //Dispatcher.Invoke(new Action(() =>
            //{
            //    try
            //    {
            //        Close();
            //    }
            //    catch { }
            //}));
        }

        public void ShowProgressWindow()
        {
            Application.Invoke((a, b) =>
            {
                this.SetDefaultSize(450, 280);
                this.ShowAll();
            });
            //Dispatcher.Invoke(new Action(() => this.Show()));
        }

        public void DownloadFailed(ErrorDetails error)
        {
            Application.Invoke((a, b) =>
            {
                TxtStatus.Text = error.Message;
                BtnPause.Label = TextResource.GetText("MENU_RESUME");
                BtnPause.Name = "Paused";
                TxtETA.Text = string.Empty;
                TxtSpeedLimit.Visible = false;
                //speedLimiterDlg?.Close();
                //speedLimiterDlg = null;
            });
            //Dispatcher.Invoke(new Action<ErrorDetails>(error =>
            //{
            //    TxtStatus.Text = error.Message;
            //    BtnPause.Content = TextResource.GetText("MENU_RESUME");
            //    BtnPause.Tag = new();
            //    TxtETA.Text = string.Empty;
            //    TxtSpeedLimit.Visibility = Visibility.Collapsed;
            //    speedLimiterDlg?.Close();
            //    speedLimiterDlg = null;
            //}), error);
        }

        public void DownloadCancelled()
        {
            Application.Invoke((a, b) =>
            {
                TxtStatus.Text = TextResource.GetText("MSG_DWN_STOP");
                TxtETA.Text = string.Empty;
                BtnPause.Label = TextResource.GetText("MENU_RESUME");
                BtnPause.Name = "Paused";
                TxtSpeedLimit.Visible = false;
                //speedLimiterDlg?.Close();
                //speedLimiterDlg = null;
            });

            //Dispatcher.Invoke(new Action(() =>
            //{
            //    TxtStatus.Text = TextResource.GetText("MSG_DWN_STOP");
            //    TxtETA.Text = string.Empty;
            //    BtnPause.Content = TextResource.GetText("MENU_RESUME");
            //    BtnPause.Tag = new();
            //    TxtSpeedLimit.Visibility = Visibility.Collapsed;
            //    speedLimiterDlg?.Close();
            //    speedLimiterDlg = null;
            //}));
        }

        public void DownloadStarted()
        {
            Application.Invoke((a, b) =>
            {
                BtnPause.Label = TextResource.GetText("MENU_PAUSE");
                BtnPause.Name = string.Empty;
                TxtSpeedLimit.Visible = false;

                if (App.GetLiveDownloadSpeedLimit(downloadId, out bool enable, out int limit))
                {
                    SetSpeedLimitText(enable, limit);
                }
            });

            //Dispatcher.Invoke(new Action(() =>
            //{
            //    BtnPause.Content = TextResource.GetText("MENU_PAUSE");
            //    BtnPause.Tag = null;
            //    TxtSpeedLimit.Visibility = Visibility.Visible;

            //    if (App.GetLiveDownloadSpeedLimit(downloadId, out bool enable, out int limit))
            //    {
            //        SetSpeedLimitText(enable, limit);
            //    }
            //}));
        }

        private void SetSpeedLimitText(bool enable, int limit)
        {
            if (enable && limit > 0)
            {
                TxtSpeedLimit.Label = $"{TextResource.GetText("SPEED_LIMIT_TITLE")} - {limit}K/S";
            }
            else
            {
                TxtSpeedLimit.Label = TextResource.GetText("MSG_NO_SPEED_LIMIT");
            }
        }

        private void SetFileText(string value)
        {
            TxtFileName.Text = value;
            var prg = PrgProgress.Fraction >= 0 && PrgProgress.Fraction <= 1 ? (int)(PrgProgress.Fraction * 100) + "% " : "";
            this.Title = $"{prg}{value}";
        }

        private void StopDownload(bool close)
        {
            if (downloadId != null)
            {
                App?.StopDownloads(new List<string> { downloadId }, close);
            }
        }

        private void DownloadProgressWindow_DeleteEvent(object o, DeleteEventArgs args)
        {
            args.RetVal = true;
            StopDownload(true);
            //Close();
            //Dispose();
            //Destroy();
        }

        private void BtnPause_Click(object? sender, EventArgs e)
        {
            if (!string.IsNullOrEmpty(BtnPause.Name))
            {
                AppUI.ResumeDownload(downloadId);
                BtnPause.Label = TextResource.GetText("MENU_PAUSE");
                BtnPause.Name = string.Empty;
                TxtSpeedLimit.Visible = true;
            }
            else
            {
                StopDownload(false);
                BtnPause.Label = TextResource.GetText("MENU_RESUME");
                BtnPause.Name = "Paused";
            }
        }

        private void BtnStop_Click(object? sender, EventArgs e)
        {
            StopDownload(true);
            //Close();
            //Dispose();
            //Destroy();
        }

        private void BtnHide_Click(object? sender, EventArgs e)
        {
            DeleteEvent -= DownloadProgressWindow_DeleteEvent;
            App.HideProgressWindow(downloadId);
            //Close();
            //Destroy();
            //Dispose();
        }

        //private void TxtSpeedLimit_Click(object sender, EventArgs e)
        //{
        //    if (speedLimiterDlg == null)
        //    {
        //        speedLimiterDlg = new SpeedLimiterWindow
        //        {
        //            Owner = this
        //        };
        //        if (App.GetLiveDownloadSpeedLimit(downloadId, out bool enable, out int limit))
        //        {
        //            speedLimiterDlg.EnableSpeedLimit = enable;
        //            speedLimiterDlg.SpeedLimit = limit;
        //        }
        //        speedLimiterDlg.Closed += (_, _) =>
        //        {
        //            speedLimiterDlg = null;
        //        };
        //        speedLimiterDlg.OkClicked += (a, b) =>
        //        {
        //            var limit2 = speedLimiterDlg.SpeedLimit;
        //            App.UpdateSpeedLimit(DownloadId, speedLimiterDlg.EnableSpeedLimit, limit2);
        //            SetSpeedLimitText(speedLimiterDlg.EnableSpeedLimit, limit2);
        //        };
        //    }

        //    if (!speedLimiterDlg.IsVisible)
        //    {
        //        speedLimiterDlg.Show();
        //    }
        //    else
        //    {
        //        speedLimiterDlg.Activate();
        //    }
        //}

        [UI] private Label TxtFileName;
        [UI] private Label TxtUrl;
        [UI] private Label TxtStatus;
        [UI] private Label TxtSpeed;
        [UI] private Label TxtETA;
        [UI] private LinkButton TxtSpeedLimit;
        [UI] private ProgressBar PrgProgress;
        [UI] private Button BtnHide;
        [UI] private Button BtnStop;
        [UI] private Button BtnPause;
        [UI] private Image ImgIcon;

        private Action<string> actSpeedUpdate, actEtaUpdate, actStatusUpdate;
        private Action<int> actPrgUpdate;
        private string downloadId = string.Empty;
        private WindowGroup windowGroup;

        private DownloadProgressWindow(Builder builder) : base(builder.GetRawOwnedObject("window"))
        {
            builder.Autoconnect(this);
            Title = TextResource.GetText("STAT_DOWNLOADING");
            SetPosition(WindowPosition.CenterAlways);

            this.windowGroup = new WindowGroup();
            this.windowGroup.AddWindow(this);

            actSpeedUpdate = value => this.TxtSpeed.Text = value;
            actEtaUpdate = value => this.TxtETA.Text = value;
            actStatusUpdate = value => this.TxtStatus.Text = value;

            actPrgUpdate = value =>
            {
                var val = value >= 0 && value <= 100 ? value : 0;
                this.PrgProgress.Fraction = val / 100.0f;
                var prg = value >= 0 && value <= 100 ? value + "% " : "";
                this.Title = $"{prg}{FileNameText}";
            };

            this.DeleteEvent += DownloadProgressWindow_DeleteEvent;
            this.BtnPause.Clicked += BtnPause_Click;
            this.BtnStop.Clicked += BtnStop_Click;
            this.BtnHide.Clicked += BtnHide_Click;

            this.BtnHide.Label = TextResource.GetText("DWN_HIDE");
            this.BtnStop.Label = TextResource.GetText("BTN_STOP_PROCESSING");
            this.BtnPause.Label = TextResource.GetText("MENU_PAUSE");
            this.TxtSpeedLimit.Label = TextResource.GetText("MSG_NO_SPEED_LIMIT");
            this.ImgIcon.Pixbuf = GtkHelper.LoadSvg("file-download-line", 48);

            this.BtnPause.Name = string.Empty;
            this.TxtFileName.StyleContext.AddClass("medium-font");

            TxtUrl.Ellipsize = Pango.EllipsizeMode.End;
            TxtFileName.Ellipsize = Pango.EllipsizeMode.End;

            GtkHelper.AttachSafeDispose(this);
        }

        public static DownloadProgressWindow CreateFromGladeFile()
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "download-progress-window.glade"));
            return new DownloadProgressWindow(builder);
        }
    }
}
